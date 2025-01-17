// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.enums;

import static com.android.tools.r8.ir.analysis.type.Nullability.definitelyNotNull;
import static com.android.tools.r8.ir.conversion.ExtraUnusedParameter.computeExtraUnusedParameters;
import static com.android.tools.r8.ir.optimize.enums.EnumUnboxerImpl.ordinalToUnboxedInt;

import com.android.tools.r8.cf.CfVersion;
import com.android.tools.r8.contexts.CompilationContext.ProcessorContext;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCodeWithLens;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedField.Builder;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodSignature;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.MethodResolutionResult;
import com.android.tools.r8.graph.ProgramField;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.PrunedItems;
import com.android.tools.r8.graph.fixup.ConcurrentMethodFixup;
import com.android.tools.r8.graph.fixup.ConcurrentMethodFixup.ProgramClassFixer;
import com.android.tools.r8.graph.fixup.MethodNamingUtility;
import com.android.tools.r8.graph.lens.MethodLookupResult;
import com.android.tools.r8.graph.proto.RewrittenPrototypeDescription;
import com.android.tools.r8.ir.analysis.type.ClassTypeElement;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.analysis.value.SingleConstValue;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.ConstClass;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.MaterializingInstructionsInfo;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.NewUnboxedEnumInstance;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.conversion.ExtraParameter;
import com.android.tools.r8.ir.conversion.ExtraUnusedParameter;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.conversion.MethodConversionOptions;
import com.android.tools.r8.ir.conversion.MethodProcessorEventConsumer;
import com.android.tools.r8.ir.conversion.OneTimeMethodProcessor;
import com.android.tools.r8.ir.optimize.CustomLensCodeRewriter;
import com.android.tools.r8.ir.optimize.enums.EnumDataMap.EnumData;
import com.android.tools.r8.ir.optimize.enums.classification.CheckNotNullEnumUnboxerMethodClassification;
import com.android.tools.r8.ir.optimize.enums.code.CheckNotZeroCode;
import com.android.tools.r8.ir.optimize.info.DefaultMethodOptimizationInfo;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedback;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedbackIgnore;
import com.android.tools.r8.ir.optimize.info.field.InstanceFieldInitializationInfo;
import com.android.tools.r8.ir.synthetic.EnumUnboxingCfCodeProvider.EnumUnboxingMethodDispatchCfCodeProvider;
import com.android.tools.r8.ir.synthetic.ThrowCfCodeProvider;
import com.android.tools.r8.profile.rewriting.ProfileCollectionAdditions;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.ImmutableArrayUtils;
import com.android.tools.r8.utils.OptionalBool;
import com.android.tools.r8.utils.SetUtils;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.android.tools.r8.utils.collections.DexMethodSignatureSet;
import com.android.tools.r8.utils.collections.ProgramMethodMap;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.Int2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

class EnumUnboxingTreeFixer implements ProgramClassFixer {

  private final EnumUnboxingLens.Builder lensBuilder;
  private final AppView<AppInfoWithLiveness> appView;
  private final ProgramMethodMap<Set<DexProgramClass>> checkNotNullMethods;
  private final DexItemFactory factory;
  // Provides information for each unboxed enum regarding the instance field values of each enum
  // instance, the original instance class, and the ordinal value.
  private final EnumDataMap enumDataMap;
  // Maps the superEnum to the enumSubtypes. This is already present in the enumDataMap as DexTypes,
  // we duplicate that here as DexProgramClasses.
  private final Map<DexProgramClass, Set<DexProgramClass>> unboxedEnumHierarchy;
  private final EnumUnboxingUtilityClasses utilityClasses;
  private final ProgramMethodMap<CfCodeWithLens> dispatchMethods =
      ProgramMethodMap.createConcurrent();
  private final ProgramMethodSet methodsToProcess = ProgramMethodSet.createConcurrent();
  private final PrunedItems.Builder prunedItemsBuilder;
  private final ProfileCollectionAdditions profileCollectionAdditions;

  EnumUnboxingTreeFixer(
      AppView<AppInfoWithLiveness> appView,
      ProgramMethodMap<Set<DexProgramClass>> checkNotNullMethods,
      EnumDataMap enumDataMap,
      Map<DexProgramClass, Set<DexProgramClass>> unboxedEnums,
      EnumUnboxingUtilityClasses utilityClasses) {
    this.appView = appView;
    this.checkNotNullMethods = checkNotNullMethods;
    this.enumDataMap = enumDataMap;
    this.factory = appView.dexItemFactory();
    this.unboxedEnumHierarchy = unboxedEnums;
    this.lensBuilder =
        EnumUnboxingLens.enumUnboxingLensBuilder(appView, enumDataMap)
            .mapUnboxedEnums(getUnboxedEnums());
    this.utilityClasses = utilityClasses;
    this.prunedItemsBuilder = PrunedItems.concurrentBuilder();
    this.profileCollectionAdditions = ProfileCollectionAdditions.create(appView);
  }

  private Set<DexProgramClass> computeUnboxedEnumClasses() {
    Set<DexProgramClass> unboxedEnumClasses = Sets.newIdentityHashSet();
    unboxedEnumHierarchy.forEach(
        (superEnum, subEnums) -> {
          unboxedEnumClasses.add(superEnum);
          unboxedEnumClasses.addAll(subEnums);
        });
    return unboxedEnumClasses;
  }

  private Set<DexType> getUnboxedEnums() {
    return enumDataMap.computeAllUnboxedEnums();
  }

  Result fixupTypeReferences(IRConverter converter, ExecutorService executorService, Timing timing)
      throws ExecutionException {
    // We do this before so that we can still perform lookup of definitions.
    fixupSuperEnumClassInitializers(converter, executorService);

    // Fix all methods and fields using enums to unbox.
    new ConcurrentMethodFixup(appView, this)
        .fixupClassesConcurrentlyByConnectedProgramComponents(Timing.empty(), executorService);

    // Install the new graph lens before processing any checkNotZero() methods.
    Set<DexMethod> dispatchMethodReferences = Sets.newIdentityHashSet();
    dispatchMethods.forEach((method, code) -> dispatchMethodReferences.add(method.getReference()));
    EnumUnboxingLens lens = lensBuilder.build(appView, dispatchMethodReferences);
    appView.rewriteWithLens(lens, executorService, timing);

    // Rewrite outliner with lens.
    converter.outliner.rewriteWithLens();

    // Create mapping from checkNotNull() to checkNotZero() methods.
    // The customLensCodeRewriter has to be non null for the duplication but is effectively unused.
    lens.setCustomLensCodeRewriter(CustomLensCodeRewriter.EMPTY);
    BiMap<DexMethod, DexMethod> checkNotNullToCheckNotZeroMapping =
        duplicateCheckNotNullMethods(converter, executorService);

    dispatchMethods.forEach((method, code) -> code.setCodeLens(lens));
    profileCollectionAdditions
        .setArtProfileCollection(appView.getArtProfileCollection())
        .commit(appView);

    return new Result(
        checkNotNullToCheckNotZeroMapping, methodsToProcess, lens, prunedItemsBuilder.build());
  }

  private void cleanUpOldClass(DexProgramClass clazz) {
    clazz.clearInstanceFields();
    clazz.clearStaticFields();
    clazz.getMethodCollection().clearDirectMethods();
    clazz.getMethodCollection().clearVirtualMethods();
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public boolean shouldReserveAsIfPinned(ProgramMethod method) {
    DexProto oldProto = method.getProto();
    DexProto newProto = fixupProto(oldProto);
    // We don't track nor reprocess dependencies of unchanged methods so we have to maintain them
    // with the same signature.
    return oldProto == newProto;
  }

  @Override
  public void fixupProgramClass(DexProgramClass clazz, MethodNamingUtility utility) {
    if (enumDataMap.isSuperUnboxedEnum(clazz.getType())) {
      // Clear the initializers and move the other methods to the new location.
      LocalEnumUnboxingUtilityClass localUtilityClass = utilityClasses.getLocalUtilityClass(clazz);
      Collection<DexEncodedField> localUtilityFields =
          createLocalUtilityFields(clazz, localUtilityClass);
      Collection<DexEncodedMethod> localUtilityMethods =
          createLocalUtilityMethods(clazz, unboxedEnumHierarchy.get(clazz), localUtilityClass);
      // Cleanup old classes.
      cleanUpOldClass(clazz);
      for (DexProgramClass subEnum : unboxedEnumHierarchy.get(clazz)) {
        cleanUpOldClass(subEnum);
      }
      // Update members on the local utility class.
      localUtilityClass.getDefinition().setDirectMethods(localUtilityMethods);
      localUtilityClass.getDefinition().setStaticFields(localUtilityFields);
    } else if (!enumDataMap.isUnboxedEnum(clazz.getType())) {
      clazz.getMethodCollection().replaceMethods(m -> fixupEncodedMethod(m, utility));
      clazz.getFieldCollection().replaceFields(this::fixupEncodedField);
    }
  }

  private BiMap<DexMethod, DexMethod> duplicateCheckNotNullMethods(
      IRConverter converter, ExecutorService executorService) throws ExecutionException {
    BiMap<DexMethod, DexMethod> checkNotNullToCheckNotZeroMapping = HashBiMap.create();
    ProcessorContext processorContext = appView.createProcessorContext();
    MethodProcessorEventConsumer eventConsumer = MethodProcessorEventConsumer.empty();
    OneTimeMethodProcessor.Builder methodProcessorBuilder =
        OneTimeMethodProcessor.builder(eventConsumer, processorContext);

    Set<DexProgramClass> unboxedEnumClasses = computeUnboxedEnumClasses();

    // Only duplicate checkNotNull() methods that are required for enum unboxing.
    checkNotNullMethods.removeIf(
        (checkNotNullMethod, dependentEnums) ->
            !SetUtils.containsAnyOf(unboxedEnumClasses, dependentEnums));

    // For each checkNotNull() method, synthesize a free flowing static checkNotZero() method that
    // takes an int instead of an Object with the same implementation.
    checkNotNullMethods.forEach(
        (checkNotNullMethod, dependentEnums) -> {
          CheckNotNullEnumUnboxerMethodClassification checkNotNullClassification =
              checkNotNullMethod
                  .getOptimizationInfo()
                  .getEnumUnboxerMethodClassification()
                  .asCheckNotNullClassification();
          DexProto newProto =
              factory.createProto(
                  factory.voidType,
                  ImmutableArrayUtils.set(
                      checkNotNullMethod.getParameters().getBacking(),
                      checkNotNullClassification.getArgumentIndex(),
                      factory.intType));
          ProgramMethod checkNotZeroMethod =
              appView
                  .getSyntheticItems()
                  .createMethod(
                      kinds -> kinds.ENUM_UNBOXING_CHECK_NOT_ZERO_METHOD,
                      // Use the context of the checkNotNull() method to ensure the method is placed
                      // in the same feature split.
                      processorContext
                          .createMethodProcessingContext(checkNotNullMethod)
                          .createUniqueContext(),
                      appView,
                      builder ->
                          builder
                              .setAccessFlags(MethodAccessFlags.createPublicStaticSynthetic())
                              .setClassFileVersion(
                                  checkNotNullMethod
                                      .getDefinition()
                                      .getClassFileVersionOrElse(null))
                              .setApiLevelForDefinition(appView.computedMinApiLevel())
                              .setApiLevelForCode(appView.computedMinApiLevel())
                              .setCode(method -> new CheckNotZeroCode(checkNotNullMethod))
                              .setOptimizationInfo(
                                  DefaultMethodOptimizationInfo.getInstance()
                                      .toMutableOptimizationInfo()
                                      .setEnumUnboxerMethodClassification(
                                          checkNotNullClassification))
                              .setProto(newProto));
          checkNotNullToCheckNotZeroMapping.put(
              checkNotNullMethod.getReference(), checkNotZeroMethod.getReference());
          lensBuilder.recordCheckNotZeroMethod(checkNotNullMethod, checkNotZeroMethod);
          methodProcessorBuilder.add(checkNotZeroMethod);
        });

    // Convert each of the synthesized methods. These methods are converted eagerly, since their
    // code objects are of type 'CheckNotZeroCode', which implements most methods using throw new
    // Unreachable().
    OneTimeMethodProcessor methodProcessor = methodProcessorBuilder.build();
    methodProcessor.forEachWaveWithExtension(
        (method, methodProcessingContext) ->
            converter.processDesugaredMethod(
                method,
                OptimizationFeedback.getSimple(),
                methodProcessor,
                methodProcessingContext,
                MethodConversionOptions.forLirPhase(appView)),
        appView.options().getThreadingModule(),
        executorService);

    return checkNotNullToCheckNotZeroMapping;
  }

  private void fixupSuperEnumClassInitializers(
      IRConverter converter, ExecutorService executorService) throws ExecutionException {
    DexEncodedField ordinalField =
        appView.appInfo().resolveField(factory.enumMembers.ordinalField).getResolvedField();
    ThreadUtils.processItems(
        unboxedEnumHierarchy.keySet(),
        unboxedEnum -> fixupSuperEnumClassInitializer(converter, unboxedEnum, ordinalField),
        appView.options().getThreadingModule(),
        executorService);
  }

  @SuppressWarnings("ReferenceEquality")
  private void fixupSuperEnumClassInitializer(
      IRConverter converter, DexProgramClass unboxedEnum, DexEncodedField ordinalField) {
    if (!unboxedEnum.hasClassInitializer()) {
      assert unboxedEnum.staticFields().isEmpty();
      return;
    }

    ProgramMethod classInitializer = unboxedEnum.getProgramClassInitializer();
    EnumData enumData = enumDataMap.get(unboxedEnum);
    LocalEnumUnboxingUtilityClass localUtilityClass =
        utilityClasses.getLocalUtilityClass(unboxedEnum);

    // Rewrite enum instantiations + remove static-puts to pruned fields.
    IRCode code = classInitializer.buildIR(appView);
    ListIterator<BasicBlock> blockIterator = code.listIterator();

    // A mapping from instructions-to-be-removed from the IR to their lens-rewritten
    // instruction (if any). If an instruction-to-be-removed has a lens-rewritten instruction, the
    // lens-rewritten instruction must also be detached from the IR.
    Map<Instruction, Optional<Instruction>> instructionsToRemove = new IdentityHashMap<>();
    while (blockIterator.hasNext()) {
      BasicBlock block = blockIterator.next();
      InstructionListIterator instructionIterator = block.listIterator(code);
      while (instructionIterator.hasNext()) {
        Instruction instruction = instructionIterator.next();
        if (instructionsToRemove.containsKey(instruction)) {
          Optional<Instruction> rewrittenInstruction = instructionsToRemove.remove(instruction);
          if (rewrittenInstruction.isPresent()) {
            instructionIterator.replaceCurrentInstruction(rewrittenInstruction.get());
            instructionIterator.previous();
          }
          instructionIterator.removeOrReplaceByDebugLocalRead();
          continue;
        }

        if (instruction.isConstClass()) {
          // Rewrite MyEnum.class.desiredAssertionStatus() to
          // LocalEnumUtility.class.desiredAssertionStatus() instead of
          // int.class.desiredAssertionStatus().
          ConstClass constClass = instruction.asConstClass();
          if (!enumDataMap.isAssignableTo(constClass.getType(), unboxedEnum.getType())) {
            continue;
          }

          List<InvokeVirtual> desiredAssertionStatusUsers = new ArrayList<>();
          for (Instruction user : constClass.outValue().aliasedUsers()) {
            if (user.isInvokeVirtual()) {
              InvokeVirtual invoke = user.asInvokeVirtual();
              if (invoke.getInvokedMethod() == factory.classMethods.desiredAssertionStatus) {
                desiredAssertionStatusUsers.add(invoke);
              }
            }
          }

          if (!desiredAssertionStatusUsers.isEmpty()) {
            ConstClass newConstClass =
                ConstClass.builder()
                    .setType(localUtilityClass.getType())
                    .setFreshOutValue(
                        code, TypeElement.classClassType(appView, definitelyNotNull()))
                    .setPosition(constClass.getPosition())
                    .build();
            instructionIterator.add(newConstClass);
            constClass
                .outValue()
                .replaceSelectiveInstructionUsers(
                    newConstClass.outValue(), desiredAssertionStatusUsers::contains);
          }
        } else if (instruction.isNewInstance()) {
          NewInstance newInstance = instruction.asNewInstance();
          DexType rewrittenType = appView.graphLens().lookupType(newInstance.getType());
          if (enumDataMap.isAssignableTo(rewrittenType, unboxedEnum.getType())) {
            InvokeDirect constructorInvoke = newInstance.getUniqueConstructorInvoke(factory);
            assert constructorInvoke != null;

            DexMethod invokedMethod = constructorInvoke.getInvokedMethod();

            // Rewrite the constructor invoke in case there are any removed arguments. This is
            // required since we find the argument index of the ordinal value below, and use this to
            // find the ordinal of the current enum instance.
            MethodLookupResult lookupResult =
                appView.graphLens().lookupInvokeDirect(invokedMethod, classInitializer);
            if (lookupResult.getReference() != invokedMethod
                || !lookupResult.getPrototypeChanges().isEmpty()) {
              List<Value> rewrittenArguments =
                  new ArrayList<>(constructorInvoke.arguments().size());
              for (int i = 0; i < constructorInvoke.arguments().size(); i++) {
                Value argument = constructorInvoke.getArgument(i);
                if (!lookupResult
                    .getPrototypeChanges()
                    .getArgumentInfoCollection()
                    .isArgumentRemoved(i)) {
                  rewrittenArguments.add(argument);
                }
              }
              for (ExtraParameter extraParameter :
                  lookupResult.getPrototypeChanges().getExtraParameters()) {
                SingleConstValue singleConstValue = extraParameter.getValue(appView);
                assert singleConstValue.isNull() || singleConstValue.isSingleNumberValue();
                Instruction materializingInstruction;
                if (singleConstValue.isNull()) {
                  assert extraParameter.getType(appView.dexItemFactory()).isNullValueType();
                  materializingInstruction =
                      singleConstValue
                          .asSingleNullValue()
                          .createMaterializingInstruction(
                              appView,
                              code,
                              MaterializingInstructionsInfo.create(
                                  TypeElement.getNull(), null, Position.none()));
                } else {
                  assert extraParameter.getType(appView.dexItemFactory()).isIntType();
                  assert singleConstValue.isSingleNumberValue();
                  materializingInstruction =
                      singleConstValue
                          .asSingleNumberValue()
                          .createMaterializingInstruction(
                              appView,
                              code,
                              MaterializingInstructionsInfo.create(
                                  TypeElement.getInt(), null, Position.none()));
                }
                instructionIterator.previous();
                instructionIterator.add(materializingInstruction);
                rewrittenArguments.add(materializingInstruction.outValue());
                Instruction next = instructionIterator.next();
                assert next == newInstance;
              }
              InvokeDirect originalConstructorInvoke = constructorInvoke;
              constructorInvoke =
                  InvokeDirect.builder()
                      .setArguments(rewrittenArguments)
                      .setMethod(lookupResult.getReference())
                      .build();

              // Record that the original constructor invoke has been rewritten into the new
              // constructor invoke, and that these instructions need to be removed from the IR.
              // Note that although the rewritten constructor invoke has not been inserted into the
              // IR, the creation of it has added it as a user of each of the operands. To undo this
              // we replace the original constructor invoke by the rewritten constructor invoke and
              // then remove the rewritten constructor invoke from the IR.
              instructionsToRemove.put(originalConstructorInvoke, Optional.of(constructorInvoke));
            } else {
              // Record that the constructor invoke needs to be removed.
              instructionsToRemove.put(constructorInvoke, Optional.empty());
            }

            DexProgramClass holder =
                newInstance.getType() == unboxedEnum.getType()
                    ? unboxedEnum
                    : appView.programDefinitionFor(newInstance.getType(), classInitializer);
            DexClassAndMethod constructor;
            if (appView.options().canInitNewInstanceUsingSuperclassConstructor()) {
              MethodResolutionResult resolutionResult =
                  appView
                      .appInfo()
                      .resolveMethod(
                          lookupResult.getReference(), constructorInvoke.getInterfaceBit());
              constructor = resolutionResult.getResolutionPair();
            } else {
              constructor = holder.lookupProgramMethod(lookupResult.getReference());
            }
            assert constructor != null;

            InstanceFieldInitializationInfo ordinalInitializationInfo =
                constructor
                    .getDefinition()
                    .getOptimizationInfo()
                    .getInstanceInitializerInfo(constructorInvoke)
                    .fieldInitializationInfos()
                    .get(ordinalField);

            int ordinal;
            if (ordinalInitializationInfo.isArgumentInitializationInfo()) {
              Value ordinalValue =
                  constructorInvoke
                      .getArgument(
                          ordinalInitializationInfo
                              .asArgumentInitializationInfo()
                              .getArgumentIndex())
                      .getAliasedValue();
              assert ordinalValue.isDefinedByInstructionSatisfying(Instruction::isConstNumber);
              ordinal = ordinalValue.getDefinition().asConstNumber().getIntValue();
            } else {
              assert ordinalInitializationInfo.isSingleValue();
              assert ordinalInitializationInfo.asSingleValue().isSingleNumberValue();
              ordinal =
                  ordinalInitializationInfo.asSingleValue().asSingleNumberValue().getIntValue();
            }

            // Replace by an instruction that produces a value of class type UnboxedEnum (for the
            // code to type check), which can easily be rewritten to a const-number instruction in
            // the enum unboxing rewriter.
            instructionIterator.replaceCurrentInstruction(
                new NewUnboxedEnumInstance(
                    rewrittenType,
                    ordinal,
                    code.createValue(
                        ClassTypeElement.create(rewrittenType, definitelyNotNull(), appView))));
          }
        } else if (instruction.isStaticPut()) {
          StaticPut staticPut = instruction.asStaticPut();
          DexField rewrittenField = appView.graphLens().lookupField(staticPut.getField());
          if (!enumDataMap.isAssignableTo(rewrittenField.getHolderType(), unboxedEnum.getType())) {
            continue;
          }

          ProgramField programField =
              appView.appInfo().resolveField(rewrittenField).getSingleProgramField();
          if (programField != null && isPrunedAfterEnumUnboxing(programField, enumData)) {
            instructionIterator.removeOrReplaceByDebugLocalRead();
          }
        }
      }
    }

    if (!instructionsToRemove.isEmpty()) {
      InstructionListIterator instructionIterator = code.instructionListIterator();
      while (instructionIterator.hasNext()) {
        Instruction instruction = instructionIterator.next();
        if (instructionsToRemove.containsKey(instruction)) {
          Optional<Instruction> rewrittenInstruction = instructionsToRemove.get(instruction);
          if (rewrittenInstruction.isPresent()) {
            instructionIterator.replaceCurrentInstruction(rewrittenInstruction.get());
            instructionIterator.previous();
          }
          instructionIterator.removeOrReplaceByDebugLocalRead();
        }
      }
    }

    converter.removeDeadCodeAndFinalizeIR(
        code, OptimizationFeedbackIgnore.getInstance(), Timing.empty());
  }

  private Collection<DexEncodedField> createLocalUtilityFields(
      DexProgramClass unboxedEnum, LocalEnumUnboxingUtilityClass localUtilityClass) {
    EnumData enumData = enumDataMap.get(unboxedEnum);
    Map<DexField, DexEncodedField> localUtilityFields =
        new LinkedHashMap<>(unboxedEnum.staticFields().size());
    assert localUtilityClass.getDefinition().staticFields().isEmpty();

    unboxedEnum.forEachProgramField(
        field -> {
          if (isPrunedAfterEnumUnboxing(field, enumData)) {
            prunedItemsBuilder.addRemovedField(field.getReference());
            return;
          }

          DexEncodedField newLocalUtilityField =
              createLocalUtilityField(
                  field,
                  localUtilityClass,
                  newFieldSignature -> !localUtilityFields.containsKey(newFieldSignature));
          assert !localUtilityFields.containsKey(newLocalUtilityField.getReference());
          localUtilityFields.put(newLocalUtilityField.getReference(), newLocalUtilityField);
        });
    return localUtilityFields.values();
  }

  private DexEncodedField createLocalUtilityField(
      ProgramField field,
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Predicate<DexField> availableFieldSignatures) {
    // Create a new, fresh field signature on the local utility class.
    DexField newFieldSignature =
        factory.createFreshFieldNameWithoutHolder(
            localUtilityClass.getType(),
            fixupType(field.getType()),
            field.getName().toString(),
            availableFieldSignatures);

    // Record the move.
    lensBuilder.move(field.getReference(), newFieldSignature);

    // Clear annotations and publicize.
    return field
        .getDefinition()
        .toTypeSubstitutedField(
            appView,
            newFieldSignature,
            builder ->
                builder
                    .clearAnnotations()
                    .modifyAccessFlags(
                        accessFlags -> {
                          assert accessFlags.isStatic();
                          accessFlags.promoteToPublic();
                        }));
  }

  @SuppressWarnings("ReferenceEquality")
  private void processMethod(
      ProgramMethod method,
      DexMethodSignatureSet nonPrivateVirtualMethods,
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods) {
    if (method.getDefinition().isClassInitializer()
        && enumDataMap.representativeType(method.getHolderType()) != method.getHolderType()) {
      assert method.getDefinition().getCode().isEmptyVoidMethod();
      prunedItemsBuilder.addRemovedMethod(method.getReference());
      method.getDefinition().setObsolete();
    } else if (method.getDefinition().isInstanceInitializer()) {
      prunedItemsBuilder.addRemovedMethod(method.getReference());
      method.getDefinition().setObsolete();
    } else if (method.getDefinition().isNonPrivateVirtualMethod()) {
      nonPrivateVirtualMethods.add(method.getReference());
    } else {
      directMoveAndMap(localUtilityClass, localUtilityMethods, method);
    }
  }

  private Collection<DexEncodedMethod> createLocalUtilityMethods(
      DexProgramClass unboxedEnum,
      Set<DexProgramClass> subEnums,
      LocalEnumUnboxingUtilityClass localUtilityClass) {
    Map<DexMethod, DexEncodedMethod> localUtilityMethods =
        new LinkedHashMap<>(
            localUtilityClass.getDefinition().getMethodCollection().size()
                + unboxedEnum.getMethodCollection().size());
    localUtilityClass
        .getDefinition()
        .forEachMethod(method -> localUtilityMethods.put(method.getReference(), method));

    // First generate all methods but the ones requiring emulated dispatch.
    DexMethodSignatureSet nonPrivateVirtualMethods = DexMethodSignatureSet.create();
    unboxedEnum.forEachProgramMethod(
        method ->
            processMethod(
                method,
                nonPrivateVirtualMethods,
                localUtilityClass,
                localUtilityMethods));
    // Second for each subEnum generate the remaining methods if not already generated.
    for (DexProgramClass subEnum : subEnums) {
      subEnum.forEachProgramMethod(
          method ->
              processMethod(
                  method,
                  nonPrivateVirtualMethods,
                  localUtilityClass,
                  localUtilityMethods));
    }

    // Then analyze each method that may require emulated dispatch.
    for (DexMethodSignature nonPrivateVirtualMethod : nonPrivateVirtualMethods) {
      processVirtualMethod(
          nonPrivateVirtualMethod, unboxedEnum, subEnums, localUtilityClass, localUtilityMethods);
    }

    return localUtilityMethods.values();
  }

  @SuppressWarnings("ReferenceEquality")
  private void processVirtualMethod(
      DexMethodSignature nonPrivateVirtualMethod,
      DexProgramClass unboxedEnum,
      Set<DexProgramClass> subEnums,
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods) {
    // Emulated dispatch is required if there is a "super method" in the superEnum or above,
    // and at least one override.
    DexMethod reference = nonPrivateVirtualMethod.withHolder(unboxedEnum.getType(), factory);
    ProgramMethodSet subimplementations = ProgramMethodSet.create();
    boolean allImplements = true;
    for (DexProgramClass subEnum : subEnums) {
      ProgramMethod subMethod = subEnum.lookupProgramMethod(reference);
      if (subMethod != null) {
        subimplementations.add(subMethod);
      } else {
        allImplements = false;
      }
    }
    DexClassAndMethod superMethod = unboxedEnum.lookupProgramMethod(reference);
    if (superMethod == null) {
      assert !subimplementations.isEmpty();
      superMethod = appView.appInfo().lookupSuperTarget(reference, unboxedEnum, appView);
      assert superMethod == null || superMethod.getReference() == factory.enumMembers.toString;
    }
    if (superMethod == null) {
      // No effective virtual dispatch is required, just move each subimplementation.
      for (ProgramMethod override : subimplementations) {
        assert !override.getAccessFlags().isAbstract();
        directMoveAndMap(localUtilityClass, localUtilityMethods, override);
      }
      return;
    }
    if (superMethod.getAccessFlags().isAbstract()) {
      if (subimplementations.isEmpty()) {
        // Abstract method with no implementors: rewrite to abstract method error.
        directMoveAndMap(
            localUtilityClass, localUtilityMethods, superMethod.asProgramMethod(), true);
      } else if (!allImplements) {
        // The abstract method is missing implementors, so we need to remap all missing
        // implementation to an abstract method error.
        emulatedDispatchMoveAndMap(
            localUtilityClass, localUtilityMethods, superMethod, subimplementations, true);
      } else if (subimplementations.size() == 1) {
        // Single implementor, no emulated dispatch is required, just forward everything to the
        // unique implementation.
        assert allImplements;
        ProgramMethod override = subimplementations.iterator().next();
        DexMethod uniqueUtility =
            directMoveAndMap(localUtilityClass, localUtilityMethods, override);
        lensBuilder.mapToDispatch(superMethod.getReference(), uniqueUtility);
      } else {
        // Multiple implementors, the abstract method is entirely implemented, no need to
        // introduce the call to the abstract method error.
        emulatedDispatchMoveAndMap(
            localUtilityClass, localUtilityMethods, superMethod, subimplementations, false);
      }
      return;
    }
    assert !superMethod.getAccessFlags().isAbstract();
    // No override, no effective virtual dispatch, just forward to the unique implementation.
    if (subimplementations.isEmpty()) {
      assert superMethod.isProgramMethod();
      directMoveAndMap(localUtilityClass, localUtilityMethods, superMethod.asProgramMethod());
      return;
    }
    // Emulated dispatch with a default case on the super enum.
    emulatedDispatchMoveAndMap(
        localUtilityClass, localUtilityMethods, superMethod, subimplementations, false);
  }

  private void emulatedDispatchMoveAndMap(
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods,
      DexClassAndMethod superMethod,
      ProgramMethodSet unorderedSubimplementations,
      boolean needsAbstractMethodErrorCase) {
    assert !unorderedSubimplementations.isEmpty();
    DexMethod superUtilityMethod;
    List<ProgramMethod> sortedSubimplementations = new ArrayList<>(unorderedSubimplementations);
    sortedSubimplementations.sort(Comparator.comparing(ProgramMethod::getHolderType));
    superUtilityMethod =
        computeSuperUtilityMethod(
            localUtilityClass,
            localUtilityMethods,
            superMethod,
            sortedSubimplementations,
            needsAbstractMethodErrorCase);
    Map<DexMethod, DexMethod> overrideToUtilityMethods = new IdentityHashMap<>();
    for (ProgramMethod subMethod : sortedSubimplementations) {
      DexMethod subEnumLocalUtilityMethod =
          installLocalUtilityMethod(localUtilityClass, localUtilityMethods, subMethod, false);
      assert subEnumLocalUtilityMethod != null;
      overrideToUtilityMethods.put(subMethod.getReference(), subEnumLocalUtilityMethod);
    }
    if (superMethod.isProgramMethod()) {
      sortedSubimplementations.add(superMethod.asProgramMethod());
    }
    DexMethod dispatch =
        installDispatchMethod(
                localUtilityClass,
                localUtilityMethods,
                sortedSubimplementations,
                superUtilityMethod,
                overrideToUtilityMethods)
            .getReference();
    if (superMethod.isProgramMethod()) {
      recordEmulatedDispatch(superMethod.getReference(), superUtilityMethod, dispatch);
    } else {
      lensBuilder.mapToDispatch(
          superMethod
              .getReference()
              .withHolder(localUtilityClass.getSynthesizingContext().getType(), factory),
          dispatch);
    }
    for (DexMethod override : overrideToUtilityMethods.keySet()) {
      recordEmulatedDispatch(override, overrideToUtilityMethods.get(override), dispatch);
    }
  }

  private DexMethod computeSuperUtilityMethod(
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods,
      DexClassAndMethod superMethod,
      List<ProgramMethod> sortedSubimplementations,
      boolean needsAbstractMethodErrorCase) {
    DexMethod superUtilityMethod;
    if (superMethod.isProgramMethod()) {
      if (needsAbstractMethodErrorCase) {
        assert superMethod.getAccessFlags().isAbstract();
        superUtilityMethod =
            installLocalUtilityMethod(
                localUtilityClass, localUtilityMethods, superMethod.asProgramMethod(), true);
      } else if (!superMethod.getAccessFlags().isAbstract()) {
        superUtilityMethod =
            installLocalUtilityMethod(
                localUtilityClass, localUtilityMethods, superMethod.asProgramMethod(), false);
      } else {
        assert superMethod.getAccessFlags().isAbstract();
        superUtilityMethod = null;
      }
    } else {
      // All methods but toString() are final or non-virtual.
      // We could support other cases by setting correctly the superUtilityMethod here.
      assert superMethod.getReference().match(factory.enumMembers.toString);
      ProgramMethod toString = localUtilityClass.ensureToStringMethod(appView);
      superUtilityMethod = toString.getReference();
      for (ProgramMethod context : sortedSubimplementations) {
        // If the utility method is used only from the dispatch method, we have to process it and
        // add it to the ArtProfile.
        methodsToProcess.add(toString);
        profileCollectionAdditions.addMethodIfContextIsInProfile(toString, context);
      }
    }
    return superUtilityMethod;
  }

  private DexMethod directMoveAndMap(
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods,
      ProgramMethod method) {
    return directMoveAndMap(localUtilityClass, localUtilityMethods, method, false);
  }

  private DexMethod directMoveAndMap(
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods,
      ProgramMethod method,
      boolean abstractMethodError) {
    DexMethod utilityMethod =
        installLocalUtilityMethod(
            localUtilityClass, localUtilityMethods, method, abstractMethodError);
    assert utilityMethod != null;
    lensBuilder.moveAndMap(method.getReference(), utilityMethod, method.getDefinition().isStatic());
    return utilityMethod;
  }

  public void recordEmulatedDispatch(DexMethod from, DexMethod move, DexMethod dispatch) {
    // Move is used for getRenamedSignature and to remap invoke-super.
    // Map is used to remap all the other invokes.
    assert from != null;
    assert dispatch != null;
    if (move != null) {
      lensBuilder.moveVirtual(from, move);
    }
    lensBuilder.mapToDispatch(from, dispatch);
  }

  private DexEncodedMethod installDispatchMethod(
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods,
      List<ProgramMethod> contexts,
      DexMethod superUtilityMethod,
      Map<DexMethod, DexMethod> map) {
    assert !map.isEmpty();
    ProgramMethod representative = contexts.iterator().next();
    DexMethod newLocalUtilityMethodReference =
        factory.createFreshMethodNameWithoutHolder(
            "_dispatch_" + representative.getName().toString(),
            fixupProto(factory.prependHolderToProto(representative.getReference())),
            localUtilityClass.getType(),
            newMethodSignature -> !localUtilityMethods.containsKey(newMethodSignature));
    Int2ReferenceSortedMap<DexMethod> methodMap = new Int2ReferenceLinkedOpenHashMap<>();
    IdentityHashMap<DexType, DexMethod> typeToMethod = new IdentityHashMap<>();
    map.forEach(
        (methodReference, newMethodReference) ->
            typeToMethod.put(methodReference.getHolderType(), newMethodReference));
    DexProgramClass unboxedEnum = localUtilityClass.getSynthesizingContext();
    assert enumDataMap.get(unboxedEnum).valuesTypes != null;
    enumDataMap
        .get(unboxedEnum)
        .valuesTypes
        .forEach(
            (i, type) -> {
              if (typeToMethod.containsKey(type)) {
                methodMap.put(ordinalToUnboxedInt(i), typeToMethod.get(type));
              }
            });
    CfCodeWithLens codeWithLens =
        new EnumUnboxingMethodDispatchCfCodeProvider(
                appView, localUtilityClass.getType(), superUtilityMethod, methodMap)
            .generateCfCode();
    DexEncodedMethod newLocalUtilityMethod =
        DexEncodedMethod.builder()
            .setMethod(newLocalUtilityMethodReference)
            .setAccessFlags(MethodAccessFlags.createPublicStaticSynthetic())
            .setCode(codeWithLens)
            .setClassFileVersion(unboxedEnum.getInitialClassFileVersion())
            .setApiLevelForDefinition(representative.getDefinition().getApiLevelForDefinition())
            .setApiLevelForCode(representative.getDefinition().getApiLevelForCode())
            .build();
    ProgramMethod dispatchMethod =
        newLocalUtilityMethod.asProgramMethod(localUtilityClass.getDefinition());
    dispatchMethods.put(dispatchMethod, codeWithLens);
    methodsToProcess.add(dispatchMethod);
    for (ProgramMethod context : contexts) {
      profileCollectionAdditions.addMethodIfContextIsInProfile(dispatchMethod, context);
    }
    assert !localUtilityMethods.containsKey(newLocalUtilityMethodReference);
    localUtilityMethods.put(newLocalUtilityMethodReference, newLocalUtilityMethod);
    return newLocalUtilityMethod;
  }

  private DexMethod installLocalUtilityMethod(
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Map<DexMethod, DexEncodedMethod> localUtilityMethods,
      ProgramMethod method,
      boolean abstractMethodError) {
    assert abstractMethodError || !method.getAccessFlags().isAbstract();
    Predicate<DexMethod> isFresh =
        newMethodSignature -> !localUtilityMethods.containsKey(newMethodSignature);
    DexEncodedMethod newLocalUtilityMethod =
        abstractMethodError
            ? createAbstractMethodErrorLocalUtilityMethod(method, localUtilityClass, isFresh)
            : createLocalUtilityMethod(method, localUtilityClass, isFresh);
    assert !localUtilityMethods.containsKey(newLocalUtilityMethod.getReference());
    localUtilityMethods.put(newLocalUtilityMethod.getReference(), newLocalUtilityMethod);
    return newLocalUtilityMethod.getReference();
  }

  private DexEncodedMethod createLocalUtilityMethod(
      ProgramMethod method,
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Predicate<DexMethod> availableMethodSignatures) {
    assert !method.getAccessFlags().isAbstract();
    DexMethod newMethod =
        createFreshMethodSignature(
            method, localUtilityClass, availableMethodSignatures, method.getReference());
    return method
        .getDefinition()
        .toTypeSubstitutedMethodAsInlining(
            newMethod,
            factory,
            builder ->
                transformMethodForLocalUtility(builder, method)
                    .setCompilationState(method.getDefinition().getCompilationState()));
  }

  private DexEncodedMethod createAbstractMethodErrorLocalUtilityMethod(
      ProgramMethod method,
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Predicate<DexMethod> availableMethodSignatures) {
    assert method.getAccessFlags().isAbstract();
    // Replace the code by a throwing stub and then rewrite the method as "inlining".
    Position position = method.getDefinition().getAndClearPendingInlineFrameAsPosition();
    method.setCode(
        new ThrowCfCodeProvider(
                appView, localUtilityClass.getType(), factory.abstractMethodErrorType, position)
            .generateCfCode(),
        appView);
    DexEncodedMethod dexEncodedMethod =
        method
            .getDefinition()
            .toTypeSubstitutedMethodAsInlining(
                createFreshMethodSignature(
                    method, localUtilityClass, availableMethodSignatures, method.getReference()),
                factory,
                builder ->
                    transformMethodForLocalUtility(builder, method)
                        .modifyAccessFlags(MethodAccessFlags::unsetAbstract)
                        .setClassFileVersion(CfVersion.V1_8)
                        .setApiLevelForDefinition(appView.computedMinApiLevel())
                        .setApiLevelForCode(appView.computedMinApiLevel()));

    methodsToProcess.add(new ProgramMethod(localUtilityClass.getDefinition(), dexEncodedMethod));
    return dexEncodedMethod;
  }

  private DexEncodedMethod.Builder transformMethodForLocalUtility(
      DexEncodedMethod.Builder builder, ProgramMethod method) {
    builder
        .clearAllAnnotations()
        .modifyAccessFlags(
            accessFlags -> {
              if (method.getDefinition().isClassInitializer()) {
                assert accessFlags.isStatic();
              } else {
                accessFlags.promoteToPublic();
                accessFlags.promoteToStatic();
              }
            })
        .unsetIsLibraryMethodOverride();
    return builder;
  }

  private DexMethod createFreshMethodSignature(
      ProgramMethod method,
      LocalEnumUnboxingUtilityClass localUtilityClass,
      Predicate<DexMethod> availableMethodSignatures,
      DexMethod methodReference) {
    return method.getDefinition().isClassInitializer()
        ? factory.createClassInitializer(localUtilityClass.getType())
        : factory.createFreshMethodNameWithoutHolder(
            "_" + method.getName().toString(),
            fixupProto(
                method.getAccessFlags().isStatic()
                    ? method.getProto()
                    : factory.prependHolderToProto(methodReference)),
            localUtilityClass.getType(),
            availableMethodSignatures);
  }

  private boolean isPrunedAfterEnumUnboxing(ProgramField field, EnumData enumData) {
    return !field.getAccessFlags().isStatic()
        || ((enumData.hasUnboxedValueFor(field) || enumData.matchesValuesField(field))
            && !field.getDefinition().getOptimizationInfo().isDead());
  }

  @SuppressWarnings("ReferenceEquality")
  private DexEncodedMethod fixupEncodedMethod(
      DexEncodedMethod method, MethodNamingUtility utility) {
    DexProto oldProto = method.getProto();
    DexProto newProto = fixupProto(oldProto);
    if (oldProto == newProto) {
      assert method.getReference()
          == utility.nextUniqueMethod(
              method, newProto, utilityClasses.getSharedUtilityClass().getType());
      return method;
    }

    DexMethod newMethod =
        utility.nextUniqueMethod(
            method, newProto, utilityClasses.getSharedUtilityClass().getType());
    assert newMethod != method.getReference();
    assert !method.isClassInitializer();
    assert !method.isLibraryMethodOverride().isTrue()
        : "Enum unboxing is changing the signature of a library override in a non unboxed class.";

    List<ExtraUnusedParameter> extraUnusedParameters =
        computeExtraUnusedParameters(method.getReference(), newMethod);
    boolean isStatic = method.isStatic();
    RewrittenPrototypeDescription prototypeChanges =
        lensBuilder.moveAndMap(
            method.getReference(), newMethod, isStatic, isStatic, extraUnusedParameters);
    return method.toTypeSubstitutedMethodAsInlining(
        newMethod,
        factory,
        builder ->
            builder
                .fixupOptimizationInfo(
                    appView, prototypeChanges.createMethodOptimizationInfoFixer())
                .setCompilationState(method.getCompilationState())
                .setIsLibraryMethodOverrideIf(
                    method.isNonPrivateVirtualMethod(), OptionalBool.FALSE));
  }

  @SuppressWarnings({"BadImport", "ReferenceEquality"})
  private DexEncodedField fixupEncodedField(DexEncodedField encodedField) {
    DexField field = encodedField.getReference();
    DexType newType = fixupType(field.type);
    if (newType == field.type) {
      return encodedField;
    }
    DexField newField = field.withType(newType, factory);
    lensBuilder.move(field, newField);
    DexEncodedField newEncodedField =
        encodedField.toTypeSubstitutedField(appView, newField, Builder::clearDynamicType);
    if (encodedField.isStatic() && encodedField.hasExplicitStaticValue()) {
      assert encodedField.getStaticValue() == DexValue.DexValueNull.NULL;
      newEncodedField.setStaticValue(DexValue.DexValueInt.DEFAULT);
      // TODO(b/150593449): Support conversion from DexValueEnum to DexValueInt.
    }
    return newEncodedField;
  }

  private DexProto fixupProto(DexProto proto) {
    DexType returnType = fixupType(proto.returnType);
    DexType[] arguments = fixupTypes(proto.parameters.values);
    return factory.createProto(returnType, arguments);
  }

  @SuppressWarnings("ReferenceEquality")
  private DexType fixupType(DexType type) {
    if (type.isArrayType()) {
      DexType base = type.toBaseType(factory);
      DexType fixed = fixupType(base);
      if (base == fixed) {
        return type;
      }
      return type.replaceBaseType(fixed, factory);
    }
    return type.isClassType() && enumDataMap.isUnboxedEnum(type) ? factory.intType : type;
  }

  private DexType[] fixupTypes(DexType[] types) {
    DexType[] result = new DexType[types.length];
    for (int i = 0; i < result.length; i++) {
      result[i] = fixupType(types[i]);
    }
    return result;
  }

  public static class Result {

    private final BiMap<DexMethod, DexMethod> checkNotNullToCheckNotZeroMapping;
    private final ProgramMethodSet methodsToProcess;
    private final EnumUnboxingLens lens;
    private final PrunedItems prunedItems;

    Result(
        BiMap<DexMethod, DexMethod> checkNotNullToCheckNotZeroMapping,
        ProgramMethodSet methodsToProcess,
        EnumUnboxingLens lens,
        PrunedItems prunedItems) {
      this.checkNotNullToCheckNotZeroMapping = checkNotNullToCheckNotZeroMapping;
      this.methodsToProcess = methodsToProcess;
      this.lens = lens;
      this.prunedItems = prunedItems;
    }

    BiMap<DexMethod, DexMethod> getCheckNotNullToCheckNotZeroMapping() {
      return checkNotNullToCheckNotZeroMapping;
    }

    public ProgramMethodSet getMethodsToProcess() {
      return methodsToProcess;
    }

    public EnumUnboxingLens getLens() {
      return lens;
    }

    PrunedItems getPrunedItems() {
      return prunedItems;
    }
  }
}
