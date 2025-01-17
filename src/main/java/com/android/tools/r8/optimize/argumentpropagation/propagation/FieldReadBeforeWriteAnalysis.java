// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.optimize.argumentpropagation.propagation;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClassAndField;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.ProgramField;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.fieldvalueanalysis.AbstractFieldSet;
import com.android.tools.r8.ir.analysis.fieldvalueanalysis.ConcreteMutableFieldSet;
import com.android.tools.r8.ir.analysis.fieldvalueanalysis.EmptyFieldSet;
import com.android.tools.r8.ir.analysis.fieldvalueanalysis.KnownFieldSet;
import com.android.tools.r8.ir.analysis.fieldvalueanalysis.UnknownFieldSet;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.DominatorTree;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstancePut;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.DequeUtils;
import com.android.tools.r8.utils.LazyBox;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

class FieldReadBeforeWriteAnalysis {

  private final AppView<AppInfoWithLiveness> appView;
  private final IRCode code;
  private final ProgramMethod context;
  private final LazyBox<DominatorTree> lazyDominatorTree;
  private final List<BasicBlock> returnBlocks;

  private Map<BasicBlock, AbstractFieldSet> fieldsMaybeReadBeforeBlockInclusiveCache;

  public FieldReadBeforeWriteAnalysis(AppView<AppInfoWithLiveness> appView, IRCode code) {
    this.appView = appView;
    this.code = code;
    this.context = code.context();
    this.lazyDominatorTree = new LazyBox<>(() -> new DominatorTree(code));
    this.returnBlocks = code.computeNormalExitBlocks();
  }

  public boolean isInstanceFieldMaybeReadBeforeWrite(ProgramField field) {
    return !isInstanceFieldNeverReadBeforeWrite(field);
  }

  public boolean isInstanceFieldNeverReadBeforeWrite(ProgramField field) {
    assert field.getHolder() == context.getHolder();
    InstancePut instancePut = null;
    for (InstancePut candidate :
        code.getThis().<InstancePut>uniqueUsers(Instruction::isInstancePut)) {
      if (candidate.getField().isIdenticalTo(field.getReference())) {
        if (instancePut != null) {
          // In the somewhat unusual case (?) that the same constructor assigns the same field
          // multiple times, we simply bail out and conservatively report that the field is maybe
          // read before it is written.
          return false;
        }
        instancePut = candidate;
      }
    }
    // TODO(b/296030319): Improve precision using escape analysis for receiver.
    return instancePut != null
        && !isFieldMaybeReadBeforeInstructionInInitializer(field, instancePut)
        && lazyDominatorTree.computeIfAbsent().dominatesAllOf(instancePut.getBlock(), returnBlocks);
  }

  public boolean isStaticFieldNeverReadBeforeWrite(ProgramField field) {
    assert field.getHolder() == context.getHolder();
    StaticPut staticPut = null;
    for (StaticPut candidate : code.<StaticPut>instructions(Instruction::isStaticPut)) {
      if (candidate.getField().isIdenticalTo(field.getReference())) {
        if (staticPut != null) {
          // In the somewhat unusual case (?) that the same constructor assigns the same field
          // multiple times, we simply bail out and conservatively report that the field is maybe
          // read before it is written.
          return false;
        }
        staticPut = candidate;
      }
    }
    return staticPut != null
        && !isFieldMaybeReadBeforeInstructionInInitializer(field, staticPut)
        && lazyDominatorTree.computeIfAbsent().dominatesAllOf(staticPut.getBlock(), returnBlocks);
  }

  private boolean isFieldMaybeReadBeforeInstructionInInitializer(
      DexClassAndField field, Instruction instruction) {
    BasicBlock block = instruction.getBlock();

    // First check if the field may be read in any of the (transitive) predecessor blocks.
    if (fieldMaybeReadBeforeBlock(field, block)) {
      return true;
    }

    // Then check if any of the instructions that precede the given instruction in the current block
    // may read the field.
    InstructionIterator instructionIterator = block.iterator();
    while (instructionIterator.hasNext()) {
      Instruction current = instructionIterator.next();
      if (current == instruction) {
        break;
      }
      if (current.readSet(appView, context).contains(field)) {
        return true;
      }
    }

    // Otherwise, the field is not read prior to the given instruction.
    return false;
  }

  private boolean fieldMaybeReadBeforeBlock(DexClassAndField field, BasicBlock block) {
    for (BasicBlock predecessor : block.getPredecessors()) {
      if (fieldMaybeReadBeforeBlockInclusive(field, predecessor)) {
        return true;
      }
    }
    return false;
  }

  private boolean fieldMaybeReadBeforeBlockInclusive(DexClassAndField field, BasicBlock block) {
    return getOrCreateFieldsMaybeReadBeforeBlockInclusive().get(block).contains(field);
  }

  private Map<BasicBlock, AbstractFieldSet> getOrCreateFieldsMaybeReadBeforeBlockInclusive() {
    if (fieldsMaybeReadBeforeBlockInclusiveCache == null) {
      fieldsMaybeReadBeforeBlockInclusiveCache = createFieldsMaybeReadBeforeBlockInclusive();
    }
    return fieldsMaybeReadBeforeBlockInclusiveCache;
  }

  /**
   * Eagerly creates a mapping from each block to the set of fields that may be read in that block
   * and its transitive predecessors.
   */
  private Map<BasicBlock, AbstractFieldSet> createFieldsMaybeReadBeforeBlockInclusive() {
    Map<BasicBlock, AbstractFieldSet> result = new IdentityHashMap<>();
    Deque<BasicBlock> worklist = DequeUtils.newArrayDeque(code.entryBlock());
    while (!worklist.isEmpty()) {
      BasicBlock block = worklist.removeFirst();
      boolean seenBefore = result.containsKey(block);
      AbstractFieldSet readSet =
          result.computeIfAbsent(block, ignore -> EmptyFieldSet.getInstance());
      if (readSet.isTop()) {
        // We already have unknown information for this block.
        continue;
      }

      assert readSet.isKnownFieldSet();
      KnownFieldSet knownReadSet = readSet.asKnownFieldSet();
      int oldSize = seenBefore ? knownReadSet.size() : -1;

      // Everything that is read in the predecessor blocks should also be included in the read set
      // for the current block, so here we join the information from the predecessor blocks into the
      // current read set.
      boolean blockOrPredecessorMaybeReadAnyField = false;
      for (BasicBlock predecessor : block.getPredecessors()) {
        AbstractFieldSet predecessorReadSet =
            result.getOrDefault(predecessor, EmptyFieldSet.getInstance());
        if (predecessorReadSet.isBottom()) {
          continue;
        }
        if (predecessorReadSet.isTop()) {
          blockOrPredecessorMaybeReadAnyField = true;
          break;
        }
        assert predecessorReadSet.isConcreteFieldSet();
        if (!knownReadSet.isConcreteFieldSet()) {
          knownReadSet = new ConcreteMutableFieldSet();
        }
        knownReadSet.asConcreteFieldSet().addAll(predecessorReadSet.asConcreteFieldSet());
      }

      if (!blockOrPredecessorMaybeReadAnyField) {
        // Finally, we update the read set with the fields that are read by the instructions in the
        // current block. This can be skipped if the block has already been processed.
        if (seenBefore) {
          assert verifyFieldSetContainsAllFieldReadsInBlock(knownReadSet, block, context);
        } else {
          for (Instruction instruction : block.getInstructions()) {
            AbstractFieldSet instructionReadSet = instruction.readSet(appView, context);
            if (instructionReadSet.isBottom()) {
              continue;
            }
            if (instructionReadSet.isTop()) {
              blockOrPredecessorMaybeReadAnyField = true;
              break;
            }
            if (!knownReadSet.isConcreteFieldSet()) {
              knownReadSet = new ConcreteMutableFieldSet();
            }
            knownReadSet.asConcreteFieldSet().addAll(instructionReadSet.asConcreteFieldSet());
          }
        }
      }

      boolean changed = false;
      if (blockOrPredecessorMaybeReadAnyField) {
        // Record that this block reads all fields.
        result.put(block, UnknownFieldSet.getInstance());
        changed = true;
      } else {
        if (knownReadSet != readSet) {
          result.put(block, knownReadSet.asConcreteFieldSet());
        }
        if (knownReadSet.size() != oldSize) {
          assert knownReadSet.size() > oldSize;
          changed = true;
        }
      }

      if (changed) {
        // Rerun the analysis for all successors because the state of the current block changed.
        worklist.addAll(block.getSuccessors());
      }
    }
    return result;
  }

  private boolean verifyFieldSetContainsAllFieldReadsInBlock(
      KnownFieldSet readSet, BasicBlock block, ProgramMethod context) {
    for (Instruction instruction : block.getInstructions()) {
      AbstractFieldSet instructionReadSet = instruction.readSet(appView, context);
      assert !instructionReadSet.isTop();
      if (instructionReadSet.isBottom()) {
        continue;
      }
      for (DexEncodedField field : instructionReadSet.asConcreteFieldSet().getFields()) {
        assert readSet.contains(field);
      }
    }
    return true;
  }
}
