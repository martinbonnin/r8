// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.lightir;

import com.android.tools.r8.dex.code.CfOrDexInstruction;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ArgumentUse;
import com.android.tools.r8.graph.ClasspathMethod;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.graph.bytecodemetadata.BytecodeInstructionMetadata;
import com.android.tools.r8.graph.bytecodemetadata.BytecodeMetadata;
import com.android.tools.r8.graph.lens.GraphLens;
import com.android.tools.r8.graph.proto.RewrittenPrototypeDescription;
import com.android.tools.r8.ir.code.CanonicalPositions;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.NumberGenerator;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.Position.SourcePosition;
import com.android.tools.r8.ir.code.Position.SyntheticPosition;
import com.android.tools.r8.ir.conversion.LensCodeRewriterUtils;
import com.android.tools.r8.ir.conversion.MethodConversionOptions;
import com.android.tools.r8.ir.conversion.MethodConversionOptions.MutableMethodConversionOptions;
import com.android.tools.r8.lightir.LirConstant.LirConstantStructuralAcceptor;
import com.android.tools.r8.utils.ArrayUtils;
import com.android.tools.r8.utils.ComparatorUtils;
import com.android.tools.r8.utils.FastMapUtils;
import com.android.tools.r8.utils.IntBox;
import com.android.tools.r8.utils.IntObjPredicate;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.RetracerForCodePrinting;
import com.android.tools.r8.utils.structural.CompareToVisitor;
import com.android.tools.r8.utils.structural.HashingVisitor;
import com.android.tools.r8.utils.structural.StructuralAcceptor;
import com.android.tools.r8.utils.structural.StructuralItem;
import com.android.tools.r8.utils.structural.StructuralMapping;
import com.android.tools.r8.utils.structural.StructuralSpecification;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class LirCode<EV> extends Code
    implements StructuralItem<LirCode<EV>>, Iterable<LirInstructionView> {

  public abstract static class PositionEntry implements StructuralItem<PositionEntry> {

    public static final PositionEntry[] EMPTY_ARRAY = new PositionEntry[0];

    private final int fromInstructionIndex;

    PositionEntry(int fromInstructionIndex) {
      this.fromInstructionIndex = fromInstructionIndex;
    }

    public int getFromInstructionIndex() {
      return fromInstructionIndex;
    }

    public abstract Position getPosition(DexMethod method, boolean isD8R8Synthesized);

    public abstract boolean hasCallerPosition();

    abstract int getOrder();

    abstract int internalAcceptCompareTo(PositionEntry other, CompareToVisitor visitor);

    abstract void internalAcceptHashing(HashingVisitor visitor);

    @Override
    public final PositionEntry self() {
      return this;
    }

    @Override
    public final StructuralMapping<PositionEntry> getStructuralMapping() {
      throw new Unreachable();
    }

    @Override
    public final int acceptCompareTo(PositionEntry other, CompareToVisitor visitor) {
      int diff = visitor.visitInt(getOrder(), other.getOrder());
      if (diff != 0) {
        return diff;
      }
      return internalAcceptCompareTo(other, visitor);
    }

    @Override
    public final void acceptHashing(HashingVisitor visitor) {
      visitor.visitInt(getOrder());
      internalAcceptHashing(visitor);
    }
  }

  public static class LinePositionEntry extends PositionEntry {
    private final int line;

    public LinePositionEntry(int fromInstructionIndex, int line) {
      super(fromInstructionIndex);
      this.line = line;
    }

    public int getLine() {
      return line;
    }

    @Override
    public boolean hasCallerPosition() {
      return false;
    }

    @Override
    public Position getPosition(DexMethod method, boolean isD8R8Synthesized) {
      return (isD8R8Synthesized ? SyntheticPosition.builder() : SourcePosition.builder())
          .setMethod(method)
          .setIsD8R8Synthesized(isD8R8Synthesized)
          .setLine(line)
          .build();
    }

    @Override
    int getOrder() {
      return 0;
    }

    @Override
    int internalAcceptCompareTo(PositionEntry other, CompareToVisitor visitor) {
      return visitor.visitInt(line, ((LinePositionEntry) other).line);
    }

    @Override
    void internalAcceptHashing(HashingVisitor visitor) {
      visitor.visitInt(line);
    }
  }

  public static class StructuredPositionEntry extends PositionEntry {
    private final Position position;

    public StructuredPositionEntry(int fromInstructionIndex, Position position) {
      super(fromInstructionIndex);
      this.position = position;
    }

    @Override
    public boolean hasCallerPosition() {
      return position.hasCallerPosition();
    }

    @Override
    public Position getPosition(DexMethod method, boolean isD8R8Synthesized) {
      return position;
    }

    @Override
    int getOrder() {
      return 1;
    }

    @Override
    int internalAcceptCompareTo(PositionEntry other, CompareToVisitor visitor) {
      return position.acceptCompareTo(((StructuredPositionEntry) other).position, visitor);
    }

    @Override
    void internalAcceptHashing(HashingVisitor visitor) {
      position.acceptHashing(visitor);
    }
  }

  public static class TryCatchTable implements StructuralItem<TryCatchTable> {
    private final Int2ReferenceMap<CatchHandlers<Integer>> tryCatchHandlers;

    public TryCatchTable(Int2ReferenceMap<CatchHandlers<Integer>> tryCatchHandlers) {
      assert !tryCatchHandlers.isEmpty();
      // Copy the map to ensure it has not over-allocated the backing store.
      this.tryCatchHandlers = new Int2ReferenceOpenHashMap<>(tryCatchHandlers);
    }

    public boolean hasHandlerThatMatches(IntObjPredicate<CatchHandlers<Integer>> predicate) {
      return Iterables.any(
          tryCatchHandlers.int2ReferenceEntrySet(),
          entry -> predicate.test(entry.getIntKey(), entry.getValue()));
    }

    public CatchHandlers<Integer> getHandlersForBlock(int blockIndex) {
      return tryCatchHandlers.get(blockIndex);
    }

    public void forEachHandler(BiConsumer<Integer, CatchHandlers<Integer>> fn) {
      tryCatchHandlers.forEach(fn);
    }

    @Override
    public TryCatchTable self() {
      return this;
    }

    @Override
    public StructuralMapping<TryCatchTable> getStructuralMapping() {
      return TryCatchTable::specify;
    }

    public TryCatchTable rewriteWithLens(GraphLens graphLens, GraphLens codeLens) {
      Int2ReferenceMap<CatchHandlers<Integer>> newTryCatchHandlers =
          FastMapUtils.mapInt2ReferenceOpenHashMapOrElse(
              tryCatchHandlers,
              (block, blockHandlers) -> blockHandlers.rewriteWithLens(graphLens, codeLens),
              null);
      return newTryCatchHandlers != null ? new TryCatchTable(newTryCatchHandlers) : this;
    }

    private static void specify(StructuralSpecification<TryCatchTable, ?> spec) {
      spec.withInt2CustomItemMap(
          s -> s.tryCatchHandlers,
          new StructuralAcceptor<>() {
            @Override
            public int acceptCompareTo(
                CatchHandlers<Integer> item1,
                CatchHandlers<Integer> item2,
                CompareToVisitor visitor) {
              int diff = visitor.visitItemCollection(item1.getGuards(), item2.getGuards());
              if (diff != 0) {
                return diff;
              }
              return ComparatorUtils.compareLists(item1.getAllTargets(), item2.getAllTargets());
            }

            @Override
            public void acceptHashing(CatchHandlers<Integer> item, HashingVisitor visitor) {
              List<Integer> targets = item.getAllTargets();
              for (int i = 0; i < targets.size(); i++) {
                visitor.visitDexType(item.getGuard(i));
                visitor.visitInt(targets.get(i));
              }
            }
          });
    }
  }

  public static class DebugLocalInfoTable<EV> implements StructuralItem<DebugLocalInfoTable<EV>> {
    // TODO(b/225838009): Once EV is removed use an int2ref map here.
    private final Map<EV, DebugLocalInfo> valueToLocalMap;
    private final Int2ReferenceMap<int[]> instructionToEndUseMap;

    public DebugLocalInfoTable(
        Map<EV, DebugLocalInfo> valueToLocalMap, Int2ReferenceMap<int[]> instructionToEndUseMap) {
      assert !valueToLocalMap.isEmpty();
      // TODO(b/283049198): Debug ends may not be maintained so we can't assume they are non-empty.
      // Copy the maps to ensure they have not over-allocated the backing store.
      this.valueToLocalMap = ImmutableMap.copyOf(valueToLocalMap);
      this.instructionToEndUseMap =
          instructionToEndUseMap.isEmpty()
              ? null
              : new Int2ReferenceOpenHashMap<>(instructionToEndUseMap);
    }

    public int[] getEnds(int index) {
      if (instructionToEndUseMap == null) {
        return null;
      }
      return instructionToEndUseMap.get(index);
    }

    public void forEachLocalDefinition(BiConsumer<EV, DebugLocalInfo> fn) {
      valueToLocalMap.forEach(fn);
    }

    @Override
    public DebugLocalInfoTable<EV> self() {
      return this;
    }

    @Override
    public StructuralMapping<DebugLocalInfoTable<EV>> getStructuralMapping() {
      throw new Unreachable();
    }

    @Override
    public int acceptCompareTo(DebugLocalInfoTable<EV> other, CompareToVisitor visitor) {
      int size = valueToLocalMap.size();
      int diff = Integer.compare(size, other.valueToLocalMap.size());
      if (diff != 0) {
        return diff;
      }
      if ((instructionToEndUseMap == null) != (other.instructionToEndUseMap == null)) {
        return instructionToEndUseMap == null ? -1 : 1;
      }
      if (instructionToEndUseMap != null) {
        assert other.instructionToEndUseMap != null;
        diff =
            ComparatorUtils.compareInt2ReferenceMap(
                instructionToEndUseMap,
                other.instructionToEndUseMap,
                ComparatorUtils::compareIntArray);
        if (diff != 0) {
          return diff;
        }
      }
      // We know EV is only instantiated with Integer so this is safe.
      assert !(valueToLocalMap instanceof Int2ReferenceMap);
      Int2ReferenceOpenHashMap<DebugLocalInfo> map1 = new Int2ReferenceOpenHashMap<>(size);
      Int2ReferenceOpenHashMap<DebugLocalInfo> map2 = new Int2ReferenceOpenHashMap<>(size);
      valueToLocalMap.forEach((k, v) -> map1.put((int) k, v));
      other.valueToLocalMap.forEach((k, v) -> map2.put((int) k, v));
      return ComparatorUtils.compareInt2ReferenceMap(
          map1, map2, (i1, i2) -> i1.acceptCompareTo(i2, visitor));
    }

    @Override
    public void acceptHashing(HashingVisitor visitor) {
      visitor.visitInt(valueToLocalMap.size());
      ArrayList<Integer> keys = new ArrayList<>(valueToLocalMap.size());
      valueToLocalMap.forEach((k, v) -> keys.add((int) k));
      keys.sort(Integer::compareTo);
      for (int key : keys) {
        visitor.visitInt(key);
        valueToLocalMap.get(key).acceptHashing(visitor);
      }
      if (instructionToEndUseMap != null) {
        // Instead of sorting the end map we just sum the keys and values which is commutative.
        IntBox keySum = new IntBox();
        IntBox valueSum = new IntBox();
        instructionToEndUseMap.forEach(
            (k, v) -> {
              keySum.increment(k);
              valueSum.increment(Arrays.hashCode(v));
            });
        visitor.visitInt(keySum.get());
        visitor.visitInt(valueSum.get());
      }
    }
  }

  private final LirStrategyInfo<EV> strategyInfo;

  private final boolean useDexEstimationStrategy;

  /** Constant pool of items. */
  private final LirConstant[] constants;

  private final PositionEntry[] positionTable;

  /** Full number of arguments (including receiver for non-static methods). */
  private final int argumentCount;

  /** Byte encoding of the instructions (excludes arguments, includes phis). */
  private final byte[] instructions;

  /** Cached value for the number of logical instructions (excludes arguments, includes phis). */
  private final int instructionCount;

  /** Table of try-catch handlers for each basic block (if present). */
  private final TryCatchTable tryCatchTable;

  /** Table of debug local information for each SSA value (if present). */
  private final DebugLocalInfoTable<EV> debugLocalInfoTable;

  /** Table of metadata for each instruction (if present). */
  private Int2ReferenceMap<BytecodeInstructionMetadata> metadataMap;

  public static <V, EV> LirBuilder<V, EV> builder(
      DexMethod method,
      boolean isD8R8Synthesized,
      LirEncodingStrategy<V, EV> strategy,
      InternalOptions options) {
    return new LirBuilder<>(method, isD8R8Synthesized, strategy, options);
  }

  private static <EV> void specify(StructuralSpecification<LirCode<EV>, ?> spec) {
    // strategyInfo is compiler meta-data (constant for a given compilation unit).
    // useDexEstimationStrategy is compiler meta-data (constant for a given compilation unit).
    spec.withCustomItemArray(c -> c.constants, LirConstantStructuralAcceptor.getInstance())
        .withItemArray(c -> c.positionTable)
        .withInt(c -> c.argumentCount)
        .withByteArray(c -> c.instructions)
        .withInt(c -> c.instructionCount)
        .withNullableItem(c -> c.tryCatchTable)
        .withNullableItem(c -> c.debugLocalInfoTable)
        .withAssert(c -> c.metadataMap == null);
  }

  protected LirCode(LirCode<EV> code) {
    this(
        code.constants,
        code.positionTable,
        code.argumentCount,
        code.instructions,
        code.instructionCount,
        code.tryCatchTable,
        code.debugLocalInfoTable,
        code.strategyInfo,
        code.useDexEstimationStrategy,
        code.metadataMap);
  }

  /** Should be constructed using {@link LirBuilder}. */
  LirCode(
      LirConstant[] constants,
      PositionEntry[] positionTable,
      int argumentCount,
      byte[] instructions,
      int instructionCount,
      TryCatchTable tryCatchTable,
      DebugLocalInfoTable<EV> debugLocalInfoTable,
      LirStrategyInfo<EV> strategyInfo,
      boolean useDexEstimationStrategy,
      Int2ReferenceMap<BytecodeInstructionMetadata> metadataMap) {
    assert positionTable != null;
    this.constants = constants;
    this.positionTable = positionTable;
    this.argumentCount = argumentCount;
    this.instructions = instructions;
    this.instructionCount = instructionCount;
    this.tryCatchTable = tryCatchTable;
    this.debugLocalInfoTable = debugLocalInfoTable;
    this.strategyInfo = strategyInfo;
    this.useDexEstimationStrategy = useDexEstimationStrategy;
    this.metadataMap = metadataMap;
  }

  @SuppressWarnings("unchecked")
  @Override
  public LirCode<Integer> asLirCode() {
    // TODO(b/225838009): Unchecked cast will be removed once the encoding strategy is definitive.
    return (LirCode<Integer>) this;
  }

  @Override
  public LirCode<EV> self() {
    return this;
  }

  @Override
  public StructuralMapping<LirCode<EV>> getStructuralMapping() {
    return LirCode::specify;
  }

  @Override
  protected int computeHashCode() {
    throw new Unreachable("LIR code should not be subject to hashing.");
  }

  @Override
  protected boolean computeEquals(Object other) {
    throw new Unreachable("LIR code should not be subject to equality checks.");
  }

  public EV decodeValueIndex(int encodedValueIndex, int currentValueIndex) {
    return strategyInfo
        .getReferenceStrategy()
        .decodeValueIndex(encodedValueIndex, currentValueIndex);
  }

  public LirStrategyInfo<EV> getStrategyInfo() {
    return strategyInfo;
  }

  public int getArgumentCount() {
    return argumentCount;
  }

  public byte[] getInstructionBytes() {
    return instructions;
  }

  public int getInstructionCount() {
    return instructionCount;
  }

  public boolean hasConstantItemThatMatches(Predicate<LirConstant> predicate) {
    return ArrayUtils.any(constants, predicate);
  }

  public LirConstant getConstantItem(int index) {
    return constants[index];
  }

  public LirConstant[] getConstantPool() {
    return constants;
  }

  public PositionEntry[] getPositionTable() {
    return positionTable;
  }

  public boolean hasTryCatchTable() {
    return tryCatchTable != null;
  }

  public TryCatchTable getTryCatchTable() {
    return tryCatchTable;
  }

  public DebugLocalInfoTable<EV> getDebugLocalInfoTable() {
    return debugLocalInfoTable;
  }

  public DebugLocalInfo getDebugLocalInfo(EV valueIndex) {
    return debugLocalInfoTable == null ? null : debugLocalInfoTable.valueToLocalMap.get(valueIndex);
  }

  public int[] getDebugLocalEnds(int instructionValueIndex) {
    return debugLocalInfoTable == null ? null : debugLocalInfoTable.getEnds(instructionValueIndex);
  }

  @Override
  public BytecodeMetadata<? extends CfOrDexInstruction> getMetadata() {
    // Bytecode metadata is recomputed when finalizing via IR.
    throw new Unreachable();
  }

  @Override
  public BytecodeInstructionMetadata getMetadata(CfOrDexInstruction instruction) {
    // Bytecode metadata is recomputed when finalizing via IR.
    throw new Unreachable();
  }

  @Override
  public void clearMetadata() {
    metadataMap = null;
  }

  @Override
  public LirIterator iterator() {
    return new LirIterator(new ByteArrayIterator(instructions));
  }

  @Override
  public IRCode buildIR(
      ProgramMethod method,
      AppView<?> appView,
      MutableMethodConversionOptions conversionOptions) {
    GraphLens codeLens = method.getDefinition().getCode().getCodeLens(appView);
    RewrittenPrototypeDescription protoChanges =
        appView
            .graphLens()
            .lookupPrototypeChangesForMethodDefinition(method.getReference(), codeLens);
    return internalBuildIR(
        method, appView, new NumberGenerator(), null, protoChanges, conversionOptions);
  }

  @Override
  public IRCode buildInliningIR(
      ProgramMethod context,
      ProgramMethod method,
      AppView<?> appView,
      GraphLens codeLens,
      NumberGenerator valueNumberGenerator,
      Position callerPosition,
      RewrittenPrototypeDescription protoChanges) {
    assert valueNumberGenerator != null;
    assert callerPosition != null;
    assert protoChanges != null;
    return internalBuildIR(
        method,
        appView,
        valueNumberGenerator,
        callerPosition,
        protoChanges,
        MethodConversionOptions.nonConverting());
  }

  private IRCode internalBuildIR(
      ProgramMethod method,
      AppView<?> appView,
      NumberGenerator valueNumberGenerator,
      Position callerPosition,
      RewrittenPrototypeDescription protoChanges,
      MutableMethodConversionOptions conversionOptions) {
    LirCode<Integer> typedLir = asLirCode();
    return Lir2IRConverter.translate(
        method,
        typedLir,
        LirStrategy.getDefaultStrategy().getDecodingStrategy(typedLir, valueNumberGenerator),
        appView,
        callerPosition,
        protoChanges,
        conversionOptions);
  }

  @Override
  public void registerCodeReferences(ProgramMethod method, UseRegistry registry) {
    assert registry.getTraversalContinuation().shouldContinue();
    for (PositionEntry positionEntry : positionTable) {
      if (positionEntry.hasCallerPosition()) {
        registry.registerInliningPosition(
            positionEntry.getPosition(
                method.getReference(), method.getDefinition().isD8R8Synthesized()));
      }
    }
    LirUseRegistryCallback<EV> registryCallbacks = new LirUseRegistryCallback<>(this, registry);
    for (LirInstructionView view : this) {
      if (metadataMap != null) {
        registryCallbacks.setCurrentMetadata(metadataMap.get(view.getInstructionIndex()));
      }
      registryCallbacks.onInstructionView(view);
      if (registry.getTraversalContinuation().shouldBreak()) {
        return;
      }
    }
    if (tryCatchTable != null) {
      for (CatchHandlers<Integer> handler : tryCatchTable.tryCatchHandlers.values()) {
        for (DexType guard : handler.getGuards()) {
          registry.registerExceptionGuard(guard);
          if (registry.getTraversalContinuation().shouldBreak()) {
            return;
          }
        }
      }
    }
  }

  @Override
  public void registerCodeReferencesForDesugaring(ClasspathMethod method, UseRegistry registry) {
    throw new Unimplemented();
  }

  @Override
  public Int2ReferenceMap<DebugLocalInfo> collectParameterInfo(
      DexEncodedMethod encodedMethod, AppView<?> appView) {
    throw new Unimplemented();
  }

  @Override
  public void registerArgumentReferences(DexEncodedMethod method, ArgumentUse registry) {
    throw new Unimplemented();
  }

  @Override
  public String toString() {
    return new LirPrinter<>(this).prettyPrint();
  }

  @Override
  public String toString(DexEncodedMethod method, RetracerForCodePrinting retracer) {
    // TODO(b/225838009): Add retracing to printer.
    return toString();
  }

  @Override
  public int estimatedDexCodeSizeUpperBoundInBytes() {
    return getEstimatedDexSizeForInlining();
  }

  @Override
  public int getEstimatedSizeForInliningIfLessThanOrEquals(int threshold) {
    if (useDexEstimationStrategy) {
      return getEstimatedDexSizeForInliningIfLessThanOrEquals(threshold);
    } else {
      // TODO(b/225838009): Currently the size estimation for CF has size one for each instruction
      //  (even switches!) and ignores stack instructions, thus loads to arguments are not included.
      //  The result is a much smaller estimate than for DEX. Once LIR is in place we should use the
      //  same estimate for both.
      int estimatedSizedForInlining = instructionCount;
      if (estimatedSizedForInlining <= threshold) {
        return estimatedSizedForInlining;
      }
      return -1;
    }
  }

  private int getEstimatedDexSizeForInlining() {
    return getEstimatedDexSizeForInliningIfLessThanOrEquals(Integer.MAX_VALUE);
  }

  private int getEstimatedDexSizeForInliningIfLessThanOrEquals(int threshold) {
    LirSizeEstimation<EV> estimation = new LirSizeEstimation<>(this);
    for (LirInstructionView view : this) {
      estimation.onInstructionView(view);
      if (estimation.getSizeEstimate() > threshold) {
        return -1;
      }
    }
    return estimation.getSizeEstimate();
  }

  public Position getPreamblePosition(DexMethod method, boolean isD8R8Synthesized) {
    if (positionTable.length > 0 && positionTable[0].fromInstructionIndex == 0) {
      return positionTable[0].getPosition(method, isD8R8Synthesized);
    }
    return SyntheticPosition.builder()
        .setLine(0)
        .setMethod(method)
        .setIsD8R8Synthesized(isD8R8Synthesized)
        .build();
  }

  public PositionEntry[] getPositionTableAsInlining(
      Position callerPosition,
      DexMethod callee,
      boolean isCalleeD8R8Synthesized,
      Consumer<Position> preamblePositionConsumer) {
    // Fast path for moving a synthetic method with no actual line info.
    if (isCalleeD8R8Synthesized && positionTable.length == 0) {
      preamblePositionConsumer.accept(callerPosition);
      return PositionEntry.EMPTY_ARRAY;
    }
    Position calleePreamble = getPreamblePosition(callee, isCalleeD8R8Synthesized);
    CanonicalPositions canonicalPositions =
        new CanonicalPositions(
            callerPosition, positionTable.length, callee, isCalleeD8R8Synthesized, calleePreamble);
    PositionEntry[] newPositionTable;
    if (positionTable.length == 0) {
      newPositionTable =
          new PositionEntry[] {
            new StructuredPositionEntry(0, canonicalPositions.getPreamblePosition())
          };
    } else {
      newPositionTable = new PositionEntry[positionTable.length];
      for (int i = 0; i < positionTable.length; i++) {
        PositionEntry inlineeEntry = positionTable[i];
        Position inlineePosition = inlineeEntry.getPosition(callee, isCalleeD8R8Synthesized);
        newPositionTable[i] =
            new StructuredPositionEntry(
                inlineeEntry.getFromInstructionIndex(),
                canonicalPositions.canonicalizePositionWithCaller(inlineePosition));
      }
    }
    preamblePositionConsumer.accept(canonicalPositions.getPreamblePosition());
    return newPositionTable;
  }

  @Override
  public Code getCodeAsInlining(
      DexMethod caller,
      boolean isCallerD8R8Synthesized,
      DexMethod callee,
      boolean isCalleeD8R8Synthesized,
      DexItemFactory factory) {
    Position callerPosition =
        SyntheticPosition.builder().setLine(0).setMethod(caller).setIsD8R8Synthesized(true).build();
    PositionEntry[] newPositionTable =
        getPositionTableAsInlining(callerPosition, callee, isCalleeD8R8Synthesized, unused -> {});
    if (Arrays.equals(positionTable, newPositionTable)) {
      return this;
    }
    return new LirCode<>(
        constants,
        newPositionTable,
        argumentCount,
        instructions,
        instructionCount,
        tryCatchTable,
        debugLocalInfoTable,
        strategyInfo,
        useDexEstimationStrategy,
        metadataMap);
  }

  @Override
  public boolean isEmptyVoidMethod() {
    for (LirInstructionView view : this) {
      int opcode = view.getOpcode();
      if (opcode != LirOpcodes.RETURN && opcode != LirOpcodes.DEBUGPOS) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean hasMonitorInstructions() {
    for (LirInstructionView view : this) {
      int opcode = view.getOpcode();
      if (opcode == LirOpcodes.MONITORENTER || opcode == LirOpcodes.MONITOREXIT) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void forEachPosition(
      DexMethod method, boolean isD8R8Synthesized, Consumer<Position> positionConsumer) {
    for (PositionEntry entry : positionTable) {
      positionConsumer.accept(entry.getPosition(method, isD8R8Synthesized));
    }
  }

  public LirCode<EV> newCodeWithRewrittenConstantPool(Function<LirConstant, LirConstant> rewriter) {
    LirConstant[] rewrittenConstants = ArrayUtils.map(constants, rewriter, new LirConstant[0]);
    if (constants == rewrittenConstants) {
      return this;
    }
    return new LirCode<>(
        rewrittenConstants,
        positionTable,
        argumentCount,
        instructions,
        instructionCount,
        tryCatchTable,
        debugLocalInfoTable,
        strategyInfo,
        useDexEstimationStrategy,
        metadataMap);
  }

  public LirCode<EV> newCodeWithRewrittenTryCatchTable(TryCatchTable rewrittenTryCatchTable) {
    if (rewrittenTryCatchTable == tryCatchTable) {
      return this;
    }
    return new LirCode<>(
        constants,
        positionTable,
        argumentCount,
        instructions,
        instructionCount,
        rewrittenTryCatchTable,
        debugLocalInfoTable,
        strategyInfo,
        useDexEstimationStrategy,
        metadataMap);
  }

  public LirCode<EV> rewriteWithLens(
      ProgramMethod context,
      AppView<? extends AppInfoWithClassHierarchy> appView,
      LensCodeRewriterUtils rewriterUtils) {
    GraphLens graphLens = appView.graphLens();
    assert graphLens.isNonIdentityLens();
    if (graphLens.isMemberRebindingIdentityLens()) {
      return this;
    }

    LirLensCodeRewriter<EV> rewriter =
        new LirLensCodeRewriter<>(appView, this, context, rewriterUtils);
    return rewriter.rewrite();
  }

  public LirCode<EV> copyWithNewConstantsAndInstructions(
      LirConstant[] constants, byte[] instructions) {
    return new LirCode<>(
        constants,
        positionTable,
        argumentCount,
        instructions,
        instructionCount,
        tryCatchTable,
        debugLocalInfoTable,
        strategyInfo,
        useDexEstimationStrategy,
        metadataMap);
  }
}
