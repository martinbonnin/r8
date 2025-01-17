// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging;

import static com.android.tools.r8.ir.conversion.ExtraUnusedParameter.computeExtraUnusedParameters;

import com.android.tools.r8.cf.CfVersion;
import com.android.tools.r8.classmerging.ClassMergerSharedData;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeUtils;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.horizontalclassmerging.code.ConstructorEntryPointSynthesizedCode;
import com.android.tools.r8.ir.conversion.ExtraConstantIntParameter;
import com.android.tools.r8.ir.conversion.ExtraUnusedParameter;
import com.android.tools.r8.profile.rewriting.ProfileCollectionAdditions;
import com.android.tools.r8.utils.ArrayUtils;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.ListUtils;
import com.android.tools.r8.utils.SetUtils;
import com.android.tools.r8.utils.structural.Ordered;
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class InstanceInitializerMerger {

  private final AppView<? extends AppInfoWithClassHierarchy> appView;
  private final Reference2IntMap<DexType> classIdentifiers;
  private final DexItemFactory dexItemFactory;
  private final HorizontalMergeGroup group;
  private final List<ProgramMethod> instanceInitializers;
  private final InstanceInitializerDescription instanceInitializerDescription;
  private final HorizontalClassMergerGraphLens.Builder lensBuilder;

  InstanceInitializerMerger(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      Reference2IntMap<DexType> classIdentifiers,
      HorizontalMergeGroup group,
      List<ProgramMethod> instanceInitializers,
      HorizontalClassMergerGraphLens.Builder lensBuilder) {
    this(appView, classIdentifiers, group, instanceInitializers, lensBuilder, null);
  }

  InstanceInitializerMerger(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      Reference2IntMap<DexType> classIdentifiers,
      HorizontalMergeGroup group,
      List<ProgramMethod> instanceInitializers,
      HorizontalClassMergerGraphLens.Builder lensBuilder,
      InstanceInitializerDescription instanceInitializerDescription) {
    this.appView = appView;
    this.classIdentifiers = classIdentifiers;
    this.dexItemFactory = appView.dexItemFactory();
    this.group = group;
    this.instanceInitializers = instanceInitializers;
    this.instanceInitializerDescription = instanceInitializerDescription;
    this.lensBuilder = lensBuilder;

    // Constructors should not be empty and all constructors should have the same prototype unless
    // equivalent.
    assert !instanceInitializers.isEmpty();
    assert instanceInitializers.stream().map(ProgramMethod::getProto).distinct().count() == 1
        || instanceInitializerDescription != null;
  }

  public int getArity() {
    return instanceInitializers.iterator().next().getReference().getArity();
  }

  public List<ProgramMethod> getInstanceInitializers() {
    return instanceInitializers;
  }

  private CfVersion getNewClassFileVersion() {
    CfVersion classFileVersion = null;
    for (ProgramMethod instanceInitializer : instanceInitializers) {
      if (instanceInitializer.getDefinition().hasClassFileVersion()) {
        classFileVersion =
            Ordered.maxIgnoreNull(
                classFileVersion, instanceInitializer.getDefinition().getClassFileVersion());
      }
    }
    return classFileVersion;
  }

  private DexMethod getNewMethodReference(ProgramMethod representative, boolean needsClassId) {
    DexType[] oldParameters = representative.getParameters().values;
    DexType[] newParameters =
        new DexType[representative.getParameters().size() + BooleanUtils.intValue(needsClassId)];
    System.arraycopy(oldParameters, 0, newParameters, 0, oldParameters.length);
    for (int parameterIndex = 0; parameterIndex < oldParameters.length; parameterIndex++) {
      Set<DexType> parameterTypes = getParameterTypes(instanceInitializers, parameterIndex);
      if (parameterTypes.size() > 1) {
        DexType leastUpperBound = DexTypeUtils.computeLeastUpperBound(appView, parameterTypes);
        assert DexTypeUtils.isApiSafe(appView, leastUpperBound);
        newParameters[parameterIndex] = leastUpperBound;
      }
    }
    if (needsClassId) {
      assert ArrayUtils.last(newParameters) == null;
      newParameters[newParameters.length - 1] = dexItemFactory.intType;
    }
    return dexItemFactory.createInstanceInitializer(group.getTarget().getType(), newParameters);
  }

  private static Set<DexType> getParameterTypes(
      List<ProgramMethod> instanceInitializers, int parameterIndex) {
    return SetUtils.newIdentityHashSet(
        builder ->
            instanceInitializers.forEach(
                instanceInitializer ->
                    builder.accept(instanceInitializer.getParameter(parameterIndex))));
  }

  /**
   * Returns a special original method signature for the synthesized constructor that did not exist
   * prior to horizontal class merging. Otherwise we might accidentally think that the synthesized
   * constructor corresponds to the previous <init>() method on the target class, which could have
   * unintended side-effects such as leading to unused argument removal being applied to the
   * synthesized constructor all-though it by construction doesn't have any unused arguments.
   */
  private DexMethod getSyntheticMethodReference(
      ClassMethodsBuilder classMethodsBuilder, DexMethod newMethodReference) {
    return dexItemFactory.createFreshMethodNameWithoutHolder(
        Constants.SYNTHETIC_INSTANCE_INITIALIZER_PREFIX,
        newMethodReference.getProto(),
        newMethodReference.getHolderType(),
        classMethodsBuilder::isFresh);
  }

  private Int2ReferenceSortedMap<DexMethod> createClassIdToInstanceInitializerMap() {
    assert !hasInstanceInitializerDescription();
    Int2ReferenceSortedMap<DexMethod> typeConstructorClassMap = new Int2ReferenceAVLTreeMap<>();
    for (ProgramMethod instanceInitializer : instanceInitializers) {
      typeConstructorClassMap.put(
          classIdentifiers.getInt(instanceInitializer.getHolderType()),
          instanceInitializer.getReference());
    }
    return typeConstructorClassMap;
  }

  public int size() {
    return instanceInitializers.size();
  }

  public static class Builder {

    private final AppView<? extends AppInfoWithClassHierarchy> appView;
    private final Reference2IntMap<DexType> classIdentifiers;
    private int estimatedDexCodeSize;
    private final List<List<ProgramMethod>> instanceInitializerGroups = new ArrayList<>();
    private final HorizontalClassMergerGraphLens.Builder lensBuilder;

    public Builder(
        AppView<? extends AppInfoWithClassHierarchy> appView,
        Reference2IntMap<DexType> classIdentifiers,
        HorizontalClassMergerGraphLens.Builder lensBuilder) {
      this.appView = appView;
      this.classIdentifiers = classIdentifiers;
      this.lensBuilder = lensBuilder;
      createNewGroup();
    }

    private List<ProgramMethod> createNewGroup() {
      estimatedDexCodeSize = 0;
      List<ProgramMethod> newGroup = new ArrayList<>();
      instanceInitializerGroups.add(newGroup);
      return newGroup;
    }

    public Builder add(ProgramMethod instanceInitializer) {
      int estimatedMaxSizeInBytes =
          instanceInitializer.getDefinition().getCode().estimatedDexCodeSizeUpperBoundInBytes();
      // If the constructor gets too large, then the constructor should be merged into a new group.
      if (estimatedDexCodeSize + estimatedMaxSizeInBytes
              > appView.options().minimumVerificationSizeLimitInBytes() / 2
          && estimatedDexCodeSize > 0) {
        createNewGroup();
      }

      ListUtils.last(instanceInitializerGroups).add(instanceInitializer);
      estimatedDexCodeSize += estimatedMaxSizeInBytes;
      return this;
    }

    public Builder addEquivalent(ProgramMethod instanceInitializer) {
      // If adding the given constructor to the current merge group leads to any API unsafe
      // parameter types, then the constructor should be merged into a new group.
      List<ProgramMethod> eligibleGroup = null;
      for (List<ProgramMethod> candidateGroup : instanceInitializerGroups) {
        if (isMergeApiSafe(candidateGroup, instanceInitializer)) {
          eligibleGroup = candidateGroup;
          break;
        }
      }
      if (eligibleGroup == null) {
        eligibleGroup = createNewGroup();
      }
      eligibleGroup.add(instanceInitializer);
      return this;
    }

    private boolean isMergeApiSafe(List<ProgramMethod> group, ProgramMethod instanceInitializer) {
      if (group.isEmpty()) {
        return true;
      }
      for (int parameterIndex = 0;
          parameterIndex < instanceInitializer.getParameters().size();
          parameterIndex++) {
        Set<DexType> parameterTypes = getParameterTypes(group, parameterIndex);
        // Adding the given instance initializer to the group can only lead to an API unsafe
        // parameter type if the instance initializer contributes a new parameter type to the group.
        if (parameterTypes.add(instanceInitializer.getParameter(parameterIndex))
            && !DexTypeUtils.isLeastUpperBoundApiSafe(appView, parameterTypes)) {
          return false;
        }
      }
      return true;
    }

    public List<InstanceInitializerMerger> build(HorizontalMergeGroup group) {
      assert instanceInitializerGroups.stream().noneMatch(List::isEmpty);
      return ListUtils.map(
          instanceInitializerGroups,
          instanceInitializers ->
              new InstanceInitializerMerger(
                  appView, classIdentifiers, group, instanceInitializers, lensBuilder));
    }

    public List<InstanceInitializerMerger> buildEquivalent(
        HorizontalMergeGroup group, InstanceInitializerDescription instanceInitializerDescription) {
      assert instanceInitializerGroups.stream().noneMatch(List::isEmpty);
      return ListUtils.map(
          instanceInitializerGroups,
          instanceInitializers ->
              new InstanceInitializerMerger(
                  appView,
                  classIdentifiers,
                  group,
                  instanceInitializers,
                  lensBuilder,
                  instanceInitializerDescription));
    }
  }

  private boolean hasInstanceInitializerDescription() {
    return instanceInitializerDescription != null;
  }

  private DexMethod moveInstanceInitializer(
      ClassMergerSharedData classMergerSharedData,
      ClassMethodsBuilder classMethodsBuilder,
      ProgramMethod instanceInitializer,
      DexMethod reservedMethod) {
    DexMethod newReference =
        dexItemFactory.createInstanceInitializerWithFreshProto(
            instanceInitializer.getReference().withHolder(group.getTarget(), dexItemFactory),
            classMergerSharedData.getExtraUnusedArgumentTypes(),
            candidate ->
                classMethodsBuilder.isFresh(candidate)
                    && candidate.isNotIdenticalTo(reservedMethod));
    if (newReference.isIdenticalTo(instanceInitializer.getReference())) {
      classMethodsBuilder.addDirectMethod(instanceInitializer.getDefinition());
      return newReference;
    }
    DexEncodedMethod newMethod =
        instanceInitializer
            .getDefinition()
            .toTypeSubstitutedMethodAsInlining(newReference, dexItemFactory);
    classMethodsBuilder.addDirectMethod(newMethod);
    return newReference;
  }

  private MethodAccessFlags getNewAccessFlags() {
    return MethodAccessFlags.fromSharedAccessFlags(
        Constants.ACC_PUBLIC | Constants.ACC_SYNTHETIC, true);
  }

  private Code getNewCode(boolean needsClassId, int numberOfUnusedArguments) {
    if (hasInstanceInitializerDescription()) {
      return instanceInitializerDescription.createCode(
          group, needsClassId, numberOfUnusedArguments);
    }
    assert useSyntheticMethod();
    return new ConstructorEntryPointSynthesizedCode(
        createClassIdToInstanceInitializerMap(),
        group.hasClassIdField() ? group.getClassIdField() : null,
        numberOfUnusedArguments);
  }

  private boolean isSingleton() {
    return instanceInitializers.size() == 1;
  }

  /** Synthesize a new method which selects the constructor based on a parameter type. */
  @SuppressWarnings("ReferenceEquality")
  void merge(
      ClassMergerSharedData classMergerSharedData,
      ProfileCollectionAdditions profileCollectionAdditions,
      ClassMethodsBuilder classMethodsBuilder) {
    ProgramMethod representative = ListUtils.first(instanceInitializers);

    // Create merged instance initializer reference.
    boolean needsClassId =
        instanceInitializers.size() > 1
            && (!hasInstanceInitializerDescription() || group.hasClassIdField());

    DexMethod newMethodReferenceTemplate = getNewMethodReference(representative, needsClassId);

    DexMethod newMethodReference =
        dexItemFactory.createInstanceInitializerWithFreshProto(
            newMethodReferenceTemplate,
            classMergerSharedData.getExtraUnusedArgumentTypes(),
            classMethodsBuilder::isFresh);

    // Compute the extra unused null parameters.
    List<ExtraUnusedParameter> extraUnusedParameters =
        computeExtraUnusedParameters(newMethodReferenceTemplate, newMethodReference);

    // Move instance initializers to target class.
    if (hasInstanceInitializerDescription()) {
      lensBuilder.moveMethods(instanceInitializers, newMethodReference);
    } else if (!useSyntheticMethod()) {
      lensBuilder.moveMethod(representative.getReference(), newMethodReference, true);
    } else {
      for (ProgramMethod instanceInitializer : instanceInitializers) {
        DexMethod movedInstanceInitializer =
            moveInstanceInitializer(
                classMergerSharedData,
                classMethodsBuilder,
                instanceInitializer,
                newMethodReference);
        lensBuilder.mapMethod(movedInstanceInitializer, movedInstanceInitializer);
        lensBuilder.recordNewMethodSignature(
            instanceInitializer.getReference(), movedInstanceInitializer);

        // Amend the art profile collection.
        profileCollectionAdditions.applyIfContextIsInProfile(
            instanceInitializer.getReference(),
            additionsBuilder -> additionsBuilder.addRule(representative));
      }
    }

    // Add a mapping from a synthetic name to the synthetic constructor.
    DexMethod syntheticMethodReference =
        getSyntheticMethodReference(classMethodsBuilder, newMethodReference);
    if (useSyntheticMethod()) {
      lensBuilder.recordNewMethodSignature(syntheticMethodReference, newMethodReference, true);
    }

    // Map each of the instance initializers to the new instance initializer in the graph lens.
    for (ProgramMethod instanceInitializer : instanceInitializers) {
      ExtraConstantIntParameter extraParameter =
          needsClassId
              ? new ExtraConstantIntParameter(
                  classIdentifiers.getInt(instanceInitializer.getHolderType()))
              : null;
      lensBuilder.mapMergedConstructor(
          instanceInitializer.getReference(), newMethodReference, extraParameter);
    }

    DexEncodedMethod representativeMethod = representative.getDefinition();

    DexEncodedMethod newInstanceInitializer;
    if (!hasInstanceInitializerDescription() && !useSyntheticMethod()) {
      newInstanceInitializer =
          representativeMethod.toTypeSubstitutedMethodAsInlining(
              newMethodReference, dexItemFactory);
    } else {
      newInstanceInitializer =
          DexEncodedMethod.syntheticBuilder()
              .setMethod(newMethodReference)
              .setAccessFlags(getNewAccessFlags())
              .setCode(getNewCode(needsClassId, extraUnusedParameters.size()))
              .setClassFileVersion(getNewClassFileVersion())
              .setApiLevelForDefinition(representativeMethod.getApiLevelForDefinition())
              .setApiLevelForCode(representativeMethod.getApiLevelForCode())
              .build();
    }
    classMethodsBuilder.addDirectMethod(newInstanceInitializer);

    assert newInstanceInitializer.getCode().isDefaultInstanceInitializerCode()
        || newInstanceInitializer.getCode().isLirCode()
        || newInstanceInitializer.getCode().isIncompleteHorizontalClassMergerCode();
  }

  void setObsolete() {
    if (hasInstanceInitializerDescription() || !useSyntheticMethod()) {
      instanceInitializers.forEach(
          instanceInitializer -> instanceInitializer.getDefinition().setObsolete());
    }
  }

  private boolean useSyntheticMethod() {
    return !isSingleton() || group.hasClassIdField();
  }
}
