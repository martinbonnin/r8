// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.passes;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;

import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.graph.AccessControl;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.type.DynamicType;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.analysis.type.TypeUtils;
import com.android.tools.r8.ir.code.Assume;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.CheckCast;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstanceOf;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.conversion.MethodProcessor;
import com.android.tools.r8.ir.conversion.passes.result.CodeRewriterResult;
import com.android.tools.r8.ir.optimize.AffectedValues;
import com.android.tools.r8.ir.optimize.CodeRewriter;
import com.android.tools.r8.ir.optimize.UtilityMethodsForCodeOptimizations;
import com.android.tools.r8.ir.optimize.UtilityMethodsForCodeOptimizations.UtilityMethodForCodeOptimizations;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.Set;

public class TrivialCheckCastAndInstanceOfRemover extends CodeRewriterPass<AppInfoWithLiveness> {

  public TrivialCheckCastAndInstanceOfRemover(AppView<?> appView) {
    super(appView);
  }

  @Override
  protected String getRewriterId() {
    return "TrivialCheckCastAndInstanceOfRemover";
  }

  @Override
  protected boolean shouldRewriteCode(IRCode code, MethodProcessor methodProcessor) {
    return appView.enableWholeProgramOptimizations()
        && appView.options().testing.enableCheckCastAndInstanceOfRemoval
        && (code.metadata().mayHaveCheckCast() || code.metadata().mayHaveInstanceOf());
  }

  @Override
  protected CodeRewriterResult rewriteCode(
      IRCode code,
      MethodProcessor methodProcessor,
      MethodProcessingContext methodProcessingContext) {
    assert appView.appInfo().hasLiveness();
    AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();
    boolean hasChanged = false;

    // If we can remove a CheckCast it is due to us having at least as much information about the
    // type as the CheckCast gives. We then need to propagate that information to the users of
    // the CheckCast to ensure further optimizations and removals of CheckCast:
    //
    //    : 1: NewArrayEmpty        v2 <- v1(1) java.lang.String[]  <-- v2 = String[]
    // ...
    //    : 2: CheckCast            v5 <- v2; java.lang.Object[]    <-- v5 = Object[]
    // ...
    //    : 3: ArrayGet             v7 <- v5, v6(0)                 <-- v7 = Object
    //    : 4: CheckCast            v8 <- v7; java.lang.String      <-- v8 = String
    // ...
    //
    // When looking at line 2 we can conclude that the CheckCast is trivial because v2 is String[]
    // and remove it. However, v7 is still only known to be Object and we cannot remove the
    // CheckCast at line 4 unless we update v7 with the most precise information by narrowing the
    // affected values of v5. We therefore have to run the type analysis after each CheckCast
    // removal.
    boolean needToRemoveTrivialPhis = false;
    for (BasicBlock block : code.getBlocks()) {
      InstructionListIterator it = block.listIterator(code);
      while (it.hasNext()) {
        Instruction current = it.next();
        if (current.isCheckCast()) {
          boolean hasPhiUsers = current.outValue().hasPhiUsers();
          AffectedValues affectedValues = new AffectedValues();
          RemoveCheckCastInstructionIfTrivialResult removeResult =
              removeCheckCastInstructionIfTrivial(
                  appViewWithLiveness,
                  current.asCheckCast(),
                  it,
                  code,
                  code.context(),
                  affectedValues,
                  methodProcessor,
                  methodProcessingContext);
          if (removeResult != RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS) {
            hasChanged = true;
            needToRemoveTrivialPhis |= hasPhiUsers;
            int blockSizeBeforeAssumeRemoval = block.size();
            Instruction previous = it.peekPrevious();
            if (removeResult == RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW) {
              affectedValues.narrowingWithAssumeRemoval(
                  appView,
                  code,
                  typeAnalysis -> typeAnalysis.setKeepRedundantBlocksAfterAssumeRemoval(true));
            } else {
              assert removeResult
                  == RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_PROPAGATE;
              affectedValues.propagateWithAssumeRemoval(
                  appView,
                  code,
                  typeAnalysis -> typeAnalysis.setKeepRedundantBlocksAfterAssumeRemoval(true));
            }
            if (block.size() != blockSizeBeforeAssumeRemoval) {
              it = previous != null ? block.listIterator(code, previous) : block.listIterator(code);
            }
          }
        } else if (current.isInstanceOf()) {
          boolean hasPhiUsers = current.outValue().hasPhiUsers();
          if (removeInstanceOfInstructionIfTrivial(
              appViewWithLiveness, current.asInstanceOf(), it, code)) {
            hasChanged = true;
            needToRemoveTrivialPhis |= hasPhiUsers;
          }
        }
      }
    }
    // ... v1
    // ...
    // v2 <- check-cast v1, T
    // v3 <- phi(v1, v2)
    // Removing check-cast may result in a trivial phi:
    // v3 <- phi(v1, v1)
    if (needToRemoveTrivialPhis) {
      AffectedValues affectedValues = new AffectedValues();
      code.removeAllDeadAndTrivialPhis(affectedValues);
      affectedValues.narrowingWithAssumeRemoval(
          appView,
          code,
          typeAnalysis -> typeAnalysis.setKeepRedundantBlocksAfterAssumeRemoval(true));
    }
    if (hasChanged) {
      code.removeRedundantBlocks();
    }
    return CodeRewriterResult.hasChanged(hasChanged);
  }

  enum RemoveCheckCastInstructionIfTrivialResult {
    NO_REMOVALS,
    REMOVED_CAST_DO_NARROW,
    REMOVED_CAST_DO_PROPAGATE
  }

  // Returns true if the given check-cast instruction was removed.
  private RemoveCheckCastInstructionIfTrivialResult removeCheckCastInstructionIfTrivial(
      AppView<AppInfoWithLiveness> appViewWithLiveness,
      CheckCast checkCast,
      InstructionListIterator it,
      IRCode code,
      ProgramMethod context,
      Set<Value> affectedValues,
      MethodProcessor methodProcessor,
      MethodProcessingContext methodProcessingContext) {
    Value inValue = checkCast.object();
    Value outValue = checkCast.outValue();
    DexType castType = checkCast.getType();
    DexType baseCastType = castType.toBaseType(dexItemFactory);

    // If the cast type is not accessible in the current context, we should not remove the cast
    // in order to preserve runtime errors. Note that JVM and ART behave differently: see
    // {@link com.android.tools.r8.ir.optimize.checkcast.IllegalAccessErrorTest}.
    if (baseCastType.isClassType()) {
      DexClass baseCastClass = appView.definitionFor(baseCastType);
      if (baseCastClass == null
          || AccessControl.isClassAccessible(baseCastClass, code.context(), appViewWithLiveness)
              .isPossiblyFalse()) {
        return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
      }
    }

    if (!appView
        .getOpenClosedInterfacesCollection()
        .isDefinitelyInstanceOfStaticType(appViewWithLiveness, inValue)) {
      return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
    }

    // If the in-value is `null` and the cast-type is a float-array type, then trivial check-cast
    // elimination may lead to verification errors. See b/123269162.
    if (options.canHaveArtCheckCastVerifierBug()) {
      if (inValue.getType().isNullType()
          && castType.isArrayType()
          && castType.toBaseType(dexItemFactory).isFloatType()) {
        return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
      }
    }

    // If casting to an array of an interface type elimination may lead to verification errors.
    // See b/132420510 and b/223424356.
    if (options.canHaveIncorrectJoinForArrayOfInterfacesBug()) {
      if (castType.isArrayType()) {
        DexType baseType = castType.toBaseType(dexItemFactory);
        if (baseType.isClassType() && baseType.isInterface(appViewWithLiveness)) {
          return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
        }
      }
    }

    TypeElement inTypeLattice = inValue.getType();
    TypeElement outTypeLattice = outValue.getType();
    TypeElement castTypeLattice = castType.toTypeElement(appView, inTypeLattice.nullability());

    assert inTypeLattice.nullability().lessThanOrEqual(outTypeLattice.nullability());

    if (inTypeLattice.lessThanOrEqual(castTypeLattice, appView)) {
      // 1) Trivial cast.
      //   A a = ...
      //   A a' = (A) a;
      // 2) Up-cast: we already have finer type info.
      //   A < B
      //   A a = ...
      //   B b = (B) a;
      assert inTypeLattice.lessThanOrEqual(outTypeLattice, appView);
      // The removeOrReplaceByDebugLocalWrite will propagate the incoming value for the CheckCast
      // to the users of the CheckCast's out value.
      //
      // v2 = CheckCast A v1  ~~>  DebugLocalWrite $v0 <- v1
      //
      // The DebugLocalWrite is not a user of the outvalue, we therefore have to wait and take the
      // CheckCast invalue users that includes the potential DebugLocalWrite.
      CodeRewriter.removeOrReplaceByDebugLocalWrite(checkCast, it, inValue, outValue);
      affectedValues.addAll(inValue.affectedValues());
      return RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW;
    }

    // If values of cast type are guaranteed to be null, then the out-value must be null if the cast
    // succeeds. After removing all usages of the out-value, the check-cast instruction is replaced
    // by a call to throwClassCastExceptionIfNotNull() to allow dead code elimination of the cast
    // type.
    if (castType.isClassType()
        && castType.isAlwaysNull(appViewWithLiveness)
        && !outValue.hasLocalInfo()
        && !appView.getSyntheticItems().isFinalized()) {
      // Replace all usages of the out-value by null.
      it.previous();
      Value nullValue = it.insertConstNullInstruction(code, options);
      it.next();
      checkCast.outValue().replaceUsers(nullValue);
      affectedValues.addAll(nullValue.affectedValues());

      // Replace the check-cast instruction by throwClassCastExceptionIfNotNull().
      UtilityMethodForCodeOptimizations throwClassCastExceptionIfNotNullMethod =
          UtilityMethodsForCodeOptimizations.synthesizeThrowClassCastExceptionIfNotNullMethod(
              appView, methodProcessor.getEventConsumer(), methodProcessingContext);
      throwClassCastExceptionIfNotNullMethod.optimize(methodProcessor);
      InvokeStatic replacement =
          InvokeStatic.builder()
              .setMethod(throwClassCastExceptionIfNotNullMethod.getMethod())
              .setSingleArgument(checkCast.object())
              .setPosition(checkCast)
              .build();
      it.replaceCurrentInstruction(replacement);
      assert replacement.lookupSingleTarget(appView, context) != null;
      if (checkCast.object().getType().isNullable()) {
        return RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW;
      }
      return RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_PROPAGATE;
    }

    // If the cast is guaranteed to succeed and only there to ensure the program type checks, then
    // check if the program would still type check after removing the cast.
    if (checkCast.isSafeCheckCast()
        || checkCast
            .getFirstOperand()
            .getDynamicUpperBoundType(appViewWithLiveness)
            .lessThanOrEqualUpToNullability(castTypeLattice, appView)) {
      TypeElement useType =
          TypeUtils.computeUseType(appViewWithLiveness, context, checkCast.outValue());
      if (inTypeLattice.lessThanOrEqualUpToNullability(useType, appView)) {
        return RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW;
      }
    }

    // Otherwise, keep the checkcast to preserve verification errors. E.g., down-cast:
    // A < B < C
    // c = ...        // Even though we know c is of type A,
    // a' = (B) c;    // (this could be removed, since chained below.)
    // a'' = (A) a';  // this should remain for runtime verification.
    assert !inTypeLattice.isDefinitelyNull() || (inValue.isPhi() && !inTypeLattice.isNullType());
    assert outTypeLattice.equalUpToNullability(castTypeLattice);
    return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
  }

  // Returns true if the given instance-of instruction was removed.
  private boolean removeInstanceOfInstructionIfTrivial(
      AppView<AppInfoWithLiveness> appViewWithLiveness,
      InstanceOf instanceOf,
      InstructionListIterator it,
      IRCode code) {
    ProgramMethod context = code.context();

    // If the instance-of type is not accessible in the current context, we should not remove the
    // instance-of instruction in order to preserve IllegalAccessError.
    DexType instanceOfBaseType = instanceOf.type().toBaseType(dexItemFactory);
    if (instanceOfBaseType.isClassType()) {
      DexClass instanceOfClass = appView.definitionFor(instanceOfBaseType);
      if (instanceOfClass == null
          || AccessControl.isClassAccessible(instanceOfClass, context, appViewWithLiveness)
              .isPossiblyFalse()) {
        return false;
      }
    }

    Value inValue = instanceOf.value();
    if (!appView
        .getOpenClosedInterfacesCollection()
        .isDefinitelyInstanceOfStaticType(appViewWithLiveness, inValue)) {
      return false;
    }

    TypeElement inType = inValue.getType();
    TypeElement instanceOfType =
        TypeElement.fromDexType(instanceOf.type(), inType.nullability(), appView);
    Value aliasValue = inValue.getAliasedValue();
    if (inType.lessThanOrEqual(instanceOfType, appView)) {
      if (inType.isDefinitelyNull()) {
        return replaceInstanceOfByFalse(code, it);
      }
      if (inType.isDefinitelyNotNull()) {
        return replaceInstanceOfByTrue(code, it);
      }
      if (options.canUseJavaUtilObjectsNonNull()) {
        return replaceInstanceOfByNonNull(it, instanceOf);
      }
    }
    if (aliasValue.isDefinedByInstructionSatisfying(Instruction::isCreatingInstanceOrArray)
        && instanceOfType.strictlyLessThan(inType, appView)) {
      return replaceInstanceOfByFalse(code, it);
    }
    if (instanceOf.type().isClassType()
        && isNeverInstantiatedDirectlyOrIndirectly(instanceOf.type())) {
      // The type of the instance-of instruction is a program class, and is never instantiated
      // directly or indirectly. Thus, the in-value must be null, meaning that the instance-of
      // instruction will always evaluate to false.
      return replaceInstanceOfByFalse(code, it);
    }

    if (inType.isClassType()
        && isNeverInstantiatedDirectlyOrIndirectly(inType.asClassType().getClassType())) {
      // The type of the in-value is a program class, and is never instantiated directly or
      // indirectly. This, the in-value must be null, meaning that the instance-of instruction
      // will always evaluate to false.
      return replaceInstanceOfByFalse(code, it);
    }

    Value aliasedValue =
        inValue.getSpecificAliasedValue(
            value ->
                value.isDefinedByInstructionSatisfying(
                    Instruction::isAssumeWithDynamicTypeAssumption));
    if (aliasedValue != null) {
      Assume assumeInstruction = aliasedValue.getDefinition().asAssume();
      DynamicType dynamicType = assumeInstruction.getDynamicType();
      if (dynamicType.getNullability().isDefinitelyNull()) {
        return replaceInstanceOfByFalse(code, it);
      } else if (dynamicType.isDynamicTypeWithUpperBound()
          && dynamicType
              .asDynamicTypeWithUpperBound()
              .getDynamicUpperBoundType()
              .lessThanOrEqual(instanceOfType, appView)
          && (!inType.isNullable() || dynamicType.getNullability().isDefinitelyNotNull())) {
        return replaceInstanceOfByTrue(code, it);
      }
    }
    return false;
  }

  private boolean replaceInstanceOfByFalse(
      IRCode code, InstructionListIterator instructionIterator) {
    instructionIterator.replaceCurrentInstructionWithConstBoolean(code, false);
    return true;
  }

  private boolean replaceInstanceOfByTrue(
      IRCode code, InstructionListIterator instructionIterator) {
    instructionIterator.replaceCurrentInstructionWithConstBoolean(code, true);
    return true;
  }

  private boolean replaceInstanceOfByNonNull(
      InstructionListIterator instructionIterator, InstanceOf instanceOf) {
    InvokeStatic replacement =
        InvokeStatic.builder()
            .setMethod(dexItemFactory.objectsMethods.nonNull)
            .setSingleArgument(instanceOf.value())
            .setOutValue(instanceOf.outValue())
            .build();
    instructionIterator.replaceCurrentInstruction(replacement);
    return true;
  }

  private boolean isNeverInstantiatedDirectlyOrIndirectly(DexType type) {
    assert appView.appInfo().hasLiveness();
    assert type.isClassType();
    DexProgramClass clazz = asProgramClassOrNull(appView.definitionFor(type));
    return clazz != null
        && !appView.appInfo().withLiveness().isInstantiatedDirectlyOrIndirectly(clazz);
  }
}
