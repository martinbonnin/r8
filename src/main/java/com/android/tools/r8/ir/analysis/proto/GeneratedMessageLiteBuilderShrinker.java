// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.proto;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;
import static com.android.tools.r8.ir.analysis.type.Nullability.definitelyNotNull;
import static com.android.tools.r8.ir.analysis.type.Nullability.maybeNull;

import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.SubtypingInfo;
import com.android.tools.r8.graph.analysis.EnqueuerAnalysis;
import com.android.tools.r8.ir.analysis.type.ClassTypeElement;
import com.android.tools.r8.ir.code.CheckCast;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.LinearFlowInstructionListIterator;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.SafeCheckCast;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.conversion.MethodConversionOptions.MutableMethodConversionOptions;
import com.android.tools.r8.ir.conversion.MethodProcessor;
import com.android.tools.r8.ir.conversion.callgraph.Node;
import com.android.tools.r8.ir.optimize.AffectedValues;
import com.android.tools.r8.ir.optimize.Inliner;
import com.android.tools.r8.ir.optimize.Inliner.Reason;
import com.android.tools.r8.ir.optimize.enums.EnumValueOptimizer;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedback;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedbackSimple;
import com.android.tools.r8.ir.optimize.inliner.FixedInliningReasonStrategy;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.DependentMinimumKeepInfoCollection;
import com.android.tools.r8.shaking.Enqueuer;
import com.android.tools.r8.shaking.EnqueuerWorklist;
import com.android.tools.r8.utils.Box;
import com.android.tools.r8.utils.ObjectUtils;
import com.android.tools.r8.utils.PredicateSet;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

public class GeneratedMessageLiteBuilderShrinker {

  private final AppView<? extends AppInfoWithClassHierarchy> appView;
  private final ProtoReferences references;
  private final boolean enableAggressiveBuilderOptimization;

  private final Map<DexProgramClass, ProgramMethod> builders = new IdentityHashMap<>();
  private final Set<DexMethod> bypassClinitForInlining = Sets.newIdentityHashSet();

  GeneratedMessageLiteBuilderShrinker(
      AppView<? extends AppInfoWithClassHierarchy> appView, ProtoReferences references) {
    this.appView = appView;
    this.references = references;
    this.enableAggressiveBuilderOptimization = computeEnableAggressiveBuilderOptimization();
    // If this fails it is likely an unsupported version of the protobuf library.
    assert enableAggressiveBuilderOptimization;
  }

  private boolean computeEnableAggressiveBuilderOptimization() {
    DexClass generatedMessageLiteBuilderClass =
        appView
            .appInfo()
            .definitionForWithoutExistenceAssert(references.generatedMessageLiteBuilderType);
    DexClass generatedMessageLiteExtendableBuilderClass =
        appView
            .appInfo()
            .definitionForWithoutExistenceAssert(
                references.generatedMessageLiteExtendableBuilderType);
    if (generatedMessageLiteBuilderClass == null
        && generatedMessageLiteExtendableBuilderClass == null) {
      // This build likely doesn't contain any proto, so disable the optimization. Don't report a
      // warning in this case.
      return false;
    }
    boolean unexpectedGeneratedMessageLiteBuilder =
        ObjectUtils.getBooleanOrElse(
            generatedMessageLiteBuilderClass,
            clazz -> clazz.getMethodCollection().hasMethods(DexEncodedMethod::isAbstract),
            true);
    if (unexpectedGeneratedMessageLiteBuilder) {
      appView
          .options()
          .reporter
          .warning(
              "Unexpected implementation of `"
                  + references.generatedMessageLiteBuilderType.toSourceString()
                  + "`: disabling aggressive protobuf builder optimization.");
      return false;
    }
    boolean unexpectedGeneratedMessageLiteExtendableBuilder =
        ObjectUtils.getBooleanOrElse(
            generatedMessageLiteExtendableBuilderClass,
            clazz -> clazz.getMethodCollection().hasMethods(DexEncodedMethod::isAbstract),
            true);
    if (unexpectedGeneratedMessageLiteExtendableBuilder) {
      appView
          .options()
          .reporter
          .warning(
              "Unexpected implementation of `"
                  + references.generatedMessageLiteExtendableBuilderType.toSourceString()
                  + "`: disabling aggressive protobuf builder optimization.");
      return false;
    }
    return true;
  }

  public EnqueuerAnalysis createEnqueuerAnalysis() {
    Set<DexProgramClass> seen = Sets.newIdentityHashSet();
    return new EnqueuerAnalysis() {
      @Override
      @SuppressWarnings("ReferenceEquality")
      public void notifyFixpoint(
          Enqueuer enqueuer,
          EnqueuerWorklist worklist,
          ExecutorService executorService,
          Timing timing) {
        builders.forEach(
            (builder, dynamicMethod) -> {
              if (seen.add(builder)) {
                // This builder class is never used in the program except from dynamicMethod(),
                // which creates an instance of the builder. Instead of creating an instance of the
                // builder class, we just instantiate the parent builder class. For this to work,
                // we make the parent builder non-abstract and its constructor public.
                DexProgramClass superClass =
                    asProgramClassOrNull(appView.definitionFor(builder.superType));
                assert superClass != null;

                ProgramMethod constructorMethod =
                    superClass.lookupProgramMethod(
                        superClass
                                .getType()
                                .isIdenticalTo(references.generatedMessageLiteBuilderType)
                            ? references.generatedMessageLiteBuilderMethods.constructorMethod
                            : references
                                .generatedMessageLiteExtendableBuilderMethods
                                .constructorMethod);
                if (constructorMethod != null) {
                  MethodAccessFlags constructorFlags = constructorMethod.getAccessFlags();
                  if (!constructorFlags.isPublic()) {
                    constructorFlags.unsetPrivate();
                    constructorFlags.unsetProtected();
                    constructorFlags.setPublic();
                  }
                }

                superClass.accessFlags.demoteFromAbstract();
                if (superClass.type == references.generatedMessageLiteBuilderType) {
                  // Manually trace `new GeneratedMessageLite.Builder(DEFAULT_INSTANCE)` since we
                  // haven't rewritten the code yet.
                  worklist.enqueueTraceNewInstanceAction(
                      references.generatedMessageLiteBuilderType, dynamicMethod);
                  worklist.enqueueTraceInvokeDirectAction(
                      references.generatedMessageLiteBuilderMethods.constructorMethod,
                      dynamicMethod,
                      null);
                } else {
                  assert superClass.type == references.generatedMessageLiteExtendableBuilderType;
                  // Manually trace `new GeneratedMessageLite.ExtendableBuilder(DEFAULT_INSTANCE)`
                  // since we haven't rewritten the code yet.
                  worklist.enqueueTraceNewInstanceAction(
                      references.generatedMessageLiteExtendableBuilderType, dynamicMethod);
                  worklist.enqueueTraceInvokeDirectAction(
                      references.generatedMessageLiteExtendableBuilderMethods.constructorMethod,
                      dynamicMethod,
                      null);
                }
                worklist.enqueueTraceStaticFieldRead(
                    references.getDefaultInstanceField(dynamicMethod.getHolder()), dynamicMethod);
              }
            });
      }
    };
  }

  /** Returns true if an action was deferred. */
  @SuppressWarnings("ReferenceEquality")
  public boolean deferDeadProtoBuilders(
      DexProgramClass clazz, ProgramMethod method, BooleanSupplier register) {
    if (!enableAggressiveBuilderOptimization) {
      return false;
    }
    DexEncodedMethod definition = method.getDefinition();
    if (references.isDynamicMethod(definition) && references.isGeneratedMessageLiteBuilder(clazz)) {
      if (register.getAsBoolean()) {
        assert !builders.containsKey(clazz) || builders.get(clazz).getDefinition() == definition;
        builders.put(clazz, method);
        return true;
      }
    }
    return false;
  }

  /**
   * Reprocesses each dynamicMethod() that references a dead builder to rewrite the dead builder
   * references.
   */
  public void rewriteDeadBuilderReferencesFromDynamicMethods(
      MutableMethodConversionOptions conversionOptions,
      AppView<AppInfoWithLiveness> appView,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    if (builders.isEmpty()) {
      return;
    }
    timing.begin("Remove dead builder references");
    AppInfoWithLiveness appInfo = appView.appInfo();
    IRConverter converter = new IRConverter(appView);
    ThreadUtils.processMap(
        builders,
        (builder, dynamicMethod) -> {
          if (!appInfo.isLiveProgramClass(builder)) {
            rewriteDeadBuilderReferencesFromDynamicMethod(
                appView, builder, dynamicMethod, converter, conversionOptions);
          }
        },
        appView.options().getThreadingModule(),
        executorService);
    builders.clear();
    timing.end(); // Remove dead builder references
  }

  @SuppressWarnings("ReferenceEquality")
  private void rewriteDeadBuilderReferencesFromDynamicMethod(
      AppView<AppInfoWithLiveness> appView,
      DexProgramClass builder,
      ProgramMethod dynamicMethod,
      IRConverter converter,
      MutableMethodConversionOptions conversionOptions) {
    IRCode code = dynamicMethod.buildIR(appView, conversionOptions);
    InstructionListIterator instructionIterator = code.instructionListIterator();

    assert builder.superType == references.generatedMessageLiteBuilderType
        || builder.superType == references.generatedMessageLiteExtendableBuilderType;

    Value builderValue =
        code.createValue(ClassTypeElement.create(builder.superType, definitelyNotNull(), appView));

    // Replace `new Message.Builder()` by `new GeneratedMessageLite.Builder()`
    // (or `new GeneratedMessageLite.ExtendableBuilder()`).
    NewInstance newInstance =
        instructionIterator.nextUntil(
            instruction ->
                instruction.isNewInstance() && instruction.asNewInstance().clazz == builder.type);
    assert newInstance != null;
    // Once the new instance is found, create a new linear iterator to allow subsequent instructions
    // to be in trivially split blocks.
    instructionIterator = new LinearFlowInstructionListIterator(code, newInstance.getBlock());
    instructionIterator.nextUntil(i -> i == newInstance);
    instructionIterator.replaceCurrentInstruction(new NewInstance(builder.superType, builderValue));

    // Replace `builder.<init>()` by `builder.<init>(Message.DEFAULT_INSTANCE)`.
    //
    // We may also see an accessibility bridge constructor, because the Builder constructor is
    // private. The accessibility bridge takes null as an argument.
    DexField defaultInstanceField = references.getDefaultInstanceField(dynamicMethod.getHolder());
    Box<Value> existingDefaultInstanceValue = new Box<>();
    InvokeDirect constructorInvoke =
        instructionIterator.nextUntil(
            instruction -> {
              // After constructor inlining we may see a load of the DEFAULT_INSTANCE field. This
              // can either be read directly using a StaticGet or accessed indirectly via a
              // synthetic accessor bridge (e.g., due to a -keep,allowshrinking rule).
              if (instruction.isInvokeStatic()) {
                InvokeStatic invoke = instruction.asInvokeStatic();
                if (invoke
                        .getInvokedMethod()
                        .getHolderType()
                        .isIdenticalTo(defaultInstanceField.getHolderType())
                    && invoke
                        .getInvokedMethod()
                        .getReturnType()
                        .isIdenticalTo(defaultInstanceField.getType())) {
                  existingDefaultInstanceValue.set(invoke.outValue());
                  return false;
                }
              }
              if (instruction.isStaticGet()) {
                StaticGet staticGet = instruction.asStaticGet();
                if (staticGet.getField() == defaultInstanceField) {
                  existingDefaultInstanceValue.set(staticGet.outValue());
                  return false;
                }
              }
              assert instruction.isInvokeDirect() || instruction.isConstNumber();
              return instruction.isInvokeDirect();
            });
    assert constructorInvoke != null;

    DexMethod constructorMethod =
        builder.superType == references.generatedMessageLiteBuilderType
            ? references.generatedMessageLiteBuilderMethods.constructorMethod
            : references.generatedMessageLiteExtendableBuilderMethods.constructorMethod;
    if (existingDefaultInstanceValue.isSet()) {
      instructionIterator.replaceCurrentInstruction(
          InvokeDirect.builder()
              .setArguments(builderValue, existingDefaultInstanceValue.get())
              .setMethod(constructorMethod)
              .build());
    } else {
      Value defaultInstanceValue =
          code.createValue(
              ClassTypeElement.create(defaultInstanceField.type, maybeNull(), appView));
      instructionIterator.replaceCurrentInstruction(
          new StaticGet(defaultInstanceValue, defaultInstanceField));
      instructionIterator.setInsertionPosition(constructorInvoke.getPosition());
      instructionIterator.add(
          InvokeDirect.builder()
              .setArguments(builderValue, defaultInstanceValue)
              .setMethod(constructorMethod)
              .build());
    }

    converter.removeDeadCodeAndFinalizeIR(
        code, OptimizationFeedbackSimple.getInstance(), Timing.empty());
  }

  public GeneratedMessageLiteBuilderShrinker addInliningHeuristicsForBuilderInlining(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      SubtypingInfo subtypingInfo,
      PredicateSet<DexType> alwaysClassInline,
      Set<DexMethod> alwaysInline,
      DependentMinimumKeepInfoCollection dependentMinimumKeepInfo) {
    new RootSetExtension(
            appView,
            alwaysClassInline,
            alwaysInline,
            dependentMinimumKeepInfo)
        .extend(subtypingInfo);
    return this;
  }

  public boolean bypassClinitForInlining(ProgramMethod method) {
    return bypassClinitForInlining.contains(method.getReference());
  }

  public void extendRootSet(DependentMinimumKeepInfoCollection dependentMinimumKeepInfo) {
    dependentMinimumKeepInfo
        .getOrCreateUnconditionalMinimumKeepInfoFor(
            references.generatedMessageLiteBuilderMethods.constructorMethod)
        .asMethodJoiner()
        .disallowInlining();
  }

  public void preprocessCallGraphBeforeCycleElimination(Map<DexMethod, Node> nodes) {
    Node node = nodes.get(references.generatedMessageLiteBuilderMethods.constructorMethod);
    if (node != null) {
      List<Node> calleesToBeRemoved = new ArrayList<>();
      for (Node callee : node.getCalleesWithDeterministicOrder()) {
        if (references.isDynamicMethodBridge(callee.getMethod())) {
          calleesToBeRemoved.add(callee);
        }
      }
      for (Node callee : calleesToBeRemoved) {
        callee.removeCaller(node);
      }
    }
  }

  public void inlineCallsToDynamicMethod(
      ProgramMethod method,
      IRCode code,
      OptimizationFeedback feedback,
      MethodProcessor methodProcessor,
      MethodProcessingContext methodProcessingContext,
      Inliner inliner) {
    strengthenCheckCastInstructions(code);

    ProtoInliningReasonStrategy inliningReasonStrategy =
        new ProtoInliningReasonStrategy(appView, new FixedInliningReasonStrategy(Reason.NEVER));
    inliner.performInlining(
        method, code, feedback, methodProcessor, Timing.empty(), inliningReasonStrategy);

    // Run the enum optimization to optimize all Enum.ordinal() invocations. This is required to
    // get rid of the enum switch in dynamicMethod().
    new EnumValueOptimizer(appView)
        .run(code, methodProcessor, methodProcessingContext, Timing.empty());
  }

  /**
   * This method tries to strengthen the type of check-cast instructions that cast a value to
   * GeneratedMessageLite.
   *
   * <p>New proto messages are created by calling dynamicMethod(MethodToInvoke.NEW_MUTABLE_INSTANCE)
   * and casting the result to GeneratedMessageLite.
   *
   * <p>If we encounter the following pattern, then we cannot inline the second call to
   * dynamicMethod, because we don't have a precise receiver type.
   *
   * <pre>
   *   GeneratedMessageLite msg =
   *       (GeneratedMessageLite)
   *           Message.DEFAULT_INSTANCE.dynamicMethod(MethodToInvoke.NEW_MUTABLE_INSTANCE);
   *   GeneratedMessageLite msg2 =
   *       (GeneratedMessageLite) msg.dynamicMethod(MethodToInvoke.NEW_MUTABLE_INSTANCE);
   * </pre>
   *
   * <p>This method therefore optimizes the code above into:
   *
   * <pre>
   *   Message msg =
   *       (Message) Message.DEFAULT_INSTANCE.dynamicMethod(MethodToInvoke.NEW_MUTABLE_INSTANCE);
   *   Message msg2 = (Message) msg.dynamicMethod(MethodToInvoke.NEW_MUTABLE_INSTANCE);
   * </pre>
   *
   * <p>This is assuming that calling dynamicMethod() on a proto message with
   * MethodToInvoke.NEW_MUTABLE_INSTANCE will create an instance of the enclosing class.
   */
  @SuppressWarnings("ReferenceEquality")
  private void strengthenCheckCastInstructions(IRCode code) {
    AffectedValues affectedValues = new AffectedValues();
    InstructionListIterator instructionIterator = code.instructionListIterator();
    CheckCast checkCast;
    while ((checkCast = instructionIterator.nextUntil(Instruction::isCheckCast)) != null) {
      if (checkCast.getType() != references.generatedMessageLiteType) {
        continue;
      }
      Value root = checkCast.object().getAliasedValue();
      if (root.isPhi() || !root.definition.isInvokeVirtual()) {
        continue;
      }
      InvokeVirtual invoke = root.definition.asInvokeVirtual();
      DexMethod invokedMethod = invoke.getInvokedMethod();
      if (!references.isDynamicMethod(invokedMethod)
          && !references.isDynamicMethodBridge(invokedMethod)) {
        continue;
      }
      assert invokedMethod.proto.parameters.values[0] == references.methodToInvokeType;
      Value methodToInvokeValue = invoke.arguments().get(1);
      if (!references.methodToInvokeMembers.isNewMutableInstanceEnum(methodToInvokeValue)) {
        continue;
      }
      ClassTypeElement receiverType =
          invoke.getReceiver().getDynamicUpperBoundType(appView).asClassType();
      if (receiverType != null) {
        AppInfoWithClassHierarchy appInfo = appView.appInfo();
        DexType rawReceiverType = receiverType.getClassType();
        if (appInfo.isStrictSubtypeOf(rawReceiverType, references.generatedMessageLiteType)) {
          Value dest = code.createValue(receiverType.asMaybeNull(), checkCast.getLocalInfo());
          SafeCheckCast replacement = new SafeCheckCast(dest, checkCast.object(), rawReceiverType);
          instructionIterator.replaceCurrentInstruction(replacement, affectedValues);
        }
      }
    }
    affectedValues.narrowingWithAssumeRemoval(appView, code);
  }

  private class RootSetExtension {

    private final AppView<? extends AppInfoWithClassHierarchy> appView;
    private final ProtoReferences references;

    private final PredicateSet<DexType> alwaysClassInline;
    private final Set<DexMethod> alwaysInline;
    private final DependentMinimumKeepInfoCollection dependentMinimumKeepInfo;

    RootSetExtension(
        AppView<? extends AppInfoWithClassHierarchy> appView,
        PredicateSet<DexType> alwaysClassInline,
        Set<DexMethod> alwaysInline,
        DependentMinimumKeepInfoCollection dependentMinimumKeepInfo) {
      this.appView = appView;
      this.references = appView.protoShrinker().references;
      this.alwaysClassInline = alwaysClassInline;
      this.alwaysInline = alwaysInline;
      this.dependentMinimumKeepInfo = dependentMinimumKeepInfo;
    }

    void extend(SubtypingInfo subtypingInfo) {
      alwaysClassInlineGeneratedMessageLiteBuilders();

      // MessageLite and GeneratedMessageLite heuristics.
      alwaysInlineCreateBuilderFromGeneratedMessageLite();
      alwaysInlineNewMutableInstanceFromGeneratedMessageLite();
      neverMergeMessageLite();

      // * extends GeneratedMessageLite heuristics.
      bypassClinitForInliningNewBuilderMethods(subtypingInfo);

      // GeneratedMessageLite$Builder heuristics.
      alwaysInlineBuildPartialFromGeneratedMessageLiteExtendableBuilder();
      neverMergeGeneratedMessageLiteBuilder();
    }

    private void alwaysClassInlineGeneratedMessageLiteBuilders() {
      alwaysClassInline.addPredicate(
          type ->
              appView
                  .appInfo()
                  .isStrictSubtypeOf(type, references.generatedMessageLiteBuilderType));
    }

    private void bypassClinitForInliningNewBuilderMethods(SubtypingInfo subtypingInfo) {
      for (DexType type : subtypingInfo.subtypes(references.generatedMessageLiteType)) {
        DexProgramClass clazz = appView.definitionFor(type).asProgramClass();
        if (clazz != null) {
          DexEncodedMethod newBuilderMethod =
              clazz.lookupDirectMethod(
                  method -> method.getName().isIdenticalTo(references.newBuilderMethodName));
          if (newBuilderMethod != null) {
            bypassClinitForInlining.add(newBuilderMethod.getReference());
          }
        }
      }
    }

    private void alwaysInlineBuildPartialFromGeneratedMessageLiteExtendableBuilder() {
      alwaysInline.add(references.generatedMessageLiteExtendableBuilderMethods.buildPartialMethod);
    }

    private void alwaysInlineCreateBuilderFromGeneratedMessageLite() {
      alwaysInline.add(references.generatedMessageLiteMethods.createBuilderMethod);
    }

    private void alwaysInlineNewMutableInstanceFromGeneratedMessageLite() {
      alwaysInline.add(references.generatedMessageLiteMethods.newMutableInstanceMethod);
    }

    private void neverMergeGeneratedMessageLiteBuilder() {
      // For consistency, never merge the GeneratedMessageLite builders. These will only have a
      // unique subtype if the application has a single proto message, which mostly happens during
      // testing.
      neverMergeClass(references.generatedMessageLiteBuilderType);
      neverMergeClass(references.generatedMessageLiteExtendableBuilderType);
    }

    private void neverMergeMessageLite() {
      // MessageLite is used in several signatures that we use for recognizing methods, so don't
      // allow it to me merged.
      neverMergeClass(references.messageLiteType);
    }

    private void neverMergeClass(DexType type) {
      dependentMinimumKeepInfo
          .getOrCreateUnconditionalMinimumKeepInfoFor(type)
          .asClassJoiner()
          .disallowHorizontalClassMerging()
          .disallowVerticalClassMerging();
    }
  }
}
