// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging;

import static com.android.tools.r8.graph.DexClassAndMethod.asProgramMethodOrNull;

import com.android.tools.r8.classmerging.ClassMergerMode;
import com.android.tools.r8.classmerging.ClassMergerSharedData;
import com.android.tools.r8.classmerging.Policy;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.PrunedItems;
import com.android.tools.r8.horizontalclassmerging.code.SyntheticInitializerConverter;
import com.android.tools.r8.ir.conversion.LirConverter;
import com.android.tools.r8.ir.conversion.MethodConversionOptions;
import com.android.tools.r8.ir.conversion.MethodConversionOptions.MutableMethodConversionOptions;
import com.android.tools.r8.profile.art.ArtProfileCompletenessChecker;
import com.android.tools.r8.profile.rewriting.ProfileCollectionAdditions;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.FieldAccessInfoCollectionModifier;
import com.android.tools.r8.shaking.KeepInfoCollection;
import com.android.tools.r8.shaking.RuntimeTypeCheckInfo;
import com.android.tools.r8.synthesis.SyntheticItems;
import com.android.tools.r8.utils.InternalOptions.HorizontalClassMergerOptions;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.android.tools.r8.utils.TraversalContinuation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public class HorizontalClassMerger {

  private final AppView<?> appView;
  private final ClassMergerMode mode;
  private final HorizontalClassMergerOptions options;

  private HorizontalClassMerger(AppView<?> appView, ClassMergerMode mode) {
    this.appView = appView;
    this.mode = mode;
    this.options = appView.options().horizontalClassMergerOptions();
  }

  public static HorizontalClassMerger createForInitialClassMerging(
      AppView<AppInfoWithLiveness> appView) {
    return new HorizontalClassMerger(appView, ClassMergerMode.INITIAL);
  }

  public static HorizontalClassMerger createForFinalClassMerging(
      AppView<? extends AppInfoWithClassHierarchy> appView) {
    return new HorizontalClassMerger(appView, ClassMergerMode.FINAL);
  }

  public static HorizontalClassMerger createForD8ClassMerging(AppView<?> appView) {
    assert appView.options().horizontalClassMergerOptions().isRestrictedToSynthetics();
    return new HorizontalClassMerger(appView, ClassMergerMode.FINAL);
  }

  public void runIfNecessary(ExecutorService executorService, Timing timing)
      throws ExecutionException {
    runIfNecessary(executorService, timing, null);
  }

  public void runIfNecessary(
      ExecutorService executorService, Timing timing, RuntimeTypeCheckInfo runtimeTypeCheckInfo)
      throws ExecutionException {
    timing.begin("HorizontalClassMerger (" + mode.toString() + ")");
    if (shouldRun()) {
      IRCodeProvider codeProvider =
          appView.hasClassHierarchy()
              ? IRCodeProvider.create(appView.withClassHierarchy(), this::getConversionOptions)
              : IRCodeProvider.createThrowing();
      run(runtimeTypeCheckInfo, codeProvider, executorService, timing);

      assert ArtProfileCompletenessChecker.verify(appView);

      // Clear type elements cache after IR building.
      appView.dexItemFactory().clearTypeElementsCache();
      appView.notifyOptimizationFinishedForTesting();
    } else {
      appView.setHorizontallyMergedClasses(HorizontallyMergedClasses.empty(), mode);
    }
    appView.appInfo().notifyHorizontalClassMergerFinished(mode);
    assert ArtProfileCompletenessChecker.verify(appView);
    timing.end();
  }

  private boolean shouldRun() {
    return options.isEnabled(mode, appView.getWholeProgramOptimizations())
        && !appView.hasCfByteCodePassThroughMethods();
  }

  private MutableMethodConversionOptions getConversionOptions() {
    return mode.isInitial()
        ? MethodConversionOptions.forPreLirPhase(appView)
        : MethodConversionOptions.forLirPhase(appView);
  }

  private void run(
      RuntimeTypeCheckInfo runtimeTypeCheckInfo,
      IRCodeProvider codeProvider,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    // Run the policies on all program classes to produce a final grouping.
    List<Policy> policies =
        PolicyScheduler.getPolicies(appView, codeProvider, mode, runtimeTypeCheckInfo);
    Collection<HorizontalMergeGroup> groups =
        new HorizontalClassMergerPolicyExecutor()
            .run(getInitialGroups(), policies, executorService, timing);

    // If there are no groups, then end horizontal class merging.
    if (groups.isEmpty()) {
      appView.setHorizontallyMergedClasses(HorizontallyMergedClasses.empty(), mode);
      return;
    }

    HorizontalClassMergerGraphLens.Builder lensBuilder =
        new HorizontalClassMergerGraphLens.Builder();

    // Merge the classes.
    List<ClassMerger> classMergers = initializeClassMergers(codeProvider, lensBuilder, groups);
    ClassMergerSharedData classMergerSharedData = new ClassMergerSharedData(appView);
    ProfileCollectionAdditions profileCollectionAdditions =
        ProfileCollectionAdditions.create(appView);
    SyntheticInitializerConverter.Builder syntheticInitializerConverterBuilder =
        SyntheticInitializerConverter.builder(appView, codeProvider);
    List<VirtuallyMergedMethodsKeepInfo> virtuallyMergedMethodsKeepInfos = new ArrayList<>();
    PrunedItems prunedItems =
        applyClassMergers(
            classMergers,
            classMergerSharedData,
            profileCollectionAdditions,
            syntheticInitializerConverterBuilder,
            virtuallyMergedMethodsKeepInfos::add);

    SyntheticInitializerConverter syntheticInitializerConverter =
        syntheticInitializerConverterBuilder.build();
    assert syntheticInitializerConverter.isEmpty() || appView.enableWholeProgramOptimizations();
    syntheticInitializerConverter.convertClassInitializers(executorService);

    // Generate the graph lens.
    HorizontallyMergedClasses mergedClasses =
        HorizontallyMergedClasses.builder().addMergeGroups(groups).build();
    appView.setHorizontallyMergedClasses(mergedClasses, mode);

    HorizontalClassMergerGraphLens horizontalClassMergerGraphLens =
        createLens(
            classMergerSharedData, mergedClasses, lensBuilder, mode, executorService, timing);
    profileCollectionAdditions =
        profileCollectionAdditions.rewriteMethodReferences(
            horizontalClassMergerGraphLens::getNextMethodToInvoke);

    assert verifyNoCyclesInInterfaceHierarchies(appView, groups);

    FieldAccessInfoCollectionModifier fieldAccessInfoCollectionModifier = null;
    if (mode.isInitial()) {
      fieldAccessInfoCollectionModifier = createFieldAccessInfoCollectionModifier(groups);
    } else {
      assert groups.stream().noneMatch(HorizontalMergeGroup::hasClassIdField);
    }

    // Set the new graph lens before finalizing any synthetic code.
    appView.setGraphLens(horizontalClassMergerGraphLens);
    codeProvider.setGraphLens(horizontalClassMergerGraphLens);

    // Finalize synthetic code.
    transformIncompleteCode(groups, horizontalClassMergerGraphLens, executorService);

    // Must rewrite AppInfoWithLiveness before pruning the merged classes, to ensure that allocation
    // sites, fields accesses, etc. are correctly transferred to the target classes.
    DexApplication newApplication = getNewApplication(mergedClasses);
    if (appView.enableWholeProgramOptimizations()) {
      // Prune keep info.
      KeepInfoCollection keepInfo = appView.getKeepInfo();
      keepInfo.mutate(mutator -> mutator.removeKeepInfoForMergedClasses(prunedItems));
      assert appView.hasClassHierarchy();
      if (mode.isInitial()) {
        appView.rewriteWithLensAndApplication(
            horizontalClassMergerGraphLens, newApplication.toDirect(), executorService, timing);
      } else {
        appView.rewriteWithLens(horizontalClassMergerGraphLens, executorService, timing);
        LirConverter.rewriteLirWithLens(appView.withClassHierarchy(), timing, executorService);
        if (appView.hasLiveness()) {
          appView
              .withLiveness()
              .setAppInfo(appView.appInfoWithLiveness().rebuildWithLiveness(newApplication));
        } else {
          appView
              .withClassHierarchy()
              .setAppInfo(
                  appView.appInfoWithClassHierarchy().rebuildWithClassHierarchy(newApplication));
        }
      }
    } else {
      assert mode.isFinal();
      SyntheticItems syntheticItems = appView.appInfo().getSyntheticItems();
      assert !syntheticItems.hasPendingSyntheticClasses();
      appView
          .withoutClassHierarchy()
          .setAppInfo(
              new AppInfo(
                  syntheticItems.commitRewrittenWithLens(
                      newApplication, horizontalClassMergerGraphLens, timing),
                  appView
                      .appInfo()
                      .getMainDexInfo()
                      .rewrittenWithLens(syntheticItems, horizontalClassMergerGraphLens, timing)));
      appView.rewriteWithD8Lens(horizontalClassMergerGraphLens, timing);
    }

    // Amend art profile collection.
    profileCollectionAdditions
        .setArtProfileCollection(appView.getArtProfileCollection())
        .setStartupProfile(appView.getStartupProfile())
        .commit(appView);

    // Record where the synthesized $r8$classId fields are read and written.
    if (fieldAccessInfoCollectionModifier != null) {
      fieldAccessInfoCollectionModifier.modify(appView.withLiveness());
    }

    appView.pruneItems(
        prunedItems.toBuilder().setPrunedApp(appView.app()).build(), executorService, timing);

    amendKeepInfo(horizontalClassMergerGraphLens, virtuallyMergedMethodsKeepInfos);
  }

  private void amendKeepInfo(
      HorizontalClassMergerGraphLens horizontalClassMergerGraphLens,
      List<VirtuallyMergedMethodsKeepInfo> virtuallyMergedMethodsKeepInfos) {
    if (!appView.enableWholeProgramOptimizations()) {
      assert virtuallyMergedMethodsKeepInfos.isEmpty();
      return;
    }
    appView
        .getKeepInfo()
        .mutate(
            keepInfo -> {
              for (VirtuallyMergedMethodsKeepInfo virtuallyMergedMethodsKeepInfo :
                  virtuallyMergedMethodsKeepInfos) {
                DexMethod representative = virtuallyMergedMethodsKeepInfo.getRepresentative();
                DexMethod mergedMethodReference =
                    horizontalClassMergerGraphLens.getNextMethodToInvoke(representative);
                ProgramMethod mergedMethod =
                    asProgramMethodOrNull(appView.definitionFor(mergedMethodReference));
                if (mergedMethod != null) {
                  keepInfo.joinMethod(
                      mergedMethod,
                      info -> info.merge(virtuallyMergedMethodsKeepInfo.getKeepInfo()));
                  continue;
                }
                assert false;
              }
            });
  }

  private FieldAccessInfoCollectionModifier createFieldAccessInfoCollectionModifier(
      Collection<HorizontalMergeGroup> groups) {
    assert mode.isInitial();
    FieldAccessInfoCollectionModifier.Builder builder =
        new FieldAccessInfoCollectionModifier.Builder();
    for (HorizontalMergeGroup group : groups) {
      if (group.hasClassIdField()) {
        DexProgramClass target = group.getTarget();
        target.forEachProgramInstanceInitializerMatching(
            definition -> definition.getCode().isHorizontalClassMergerCode(),
            method -> builder.recordFieldWrittenInContext(group.getClassIdField(), method));
        target.forEachProgramVirtualMethodMatching(
            definition ->
                definition.hasCode() && definition.getCode().isHorizontalClassMergerCode(),
            method -> builder.recordFieldReadInContext(group.getClassIdField(), method));
      }
    }
    return builder.build();
  }

  private void transformIncompleteCode(
      Collection<HorizontalMergeGroup> groups,
      HorizontalClassMergerGraphLens horizontalClassMergerGraphLens,
      ExecutorService executorService)
      throws ExecutionException {
    if (!appView.hasClassHierarchy()) {
      assert verifyNoIncompleteCode(groups, executorService);
      return;
    }
    ThreadUtils.processItems(
        groups,
        group -> {
          DexProgramClass target = group.getTarget();
          target.forEachProgramMethodMatching(
              definition ->
                  definition.hasCode()
                      && definition.getCode().isIncompleteHorizontalClassMergerCode(),
              method -> {
                // Transform the code object to CfCode. This may return null if the code object does
                // not have support for generating CfCode. In this case, the call to toCfCode() will
                // lens rewrite the references of the code object using the lens.
                //
                // This should be changed to generate non-null LirCode always.
                IncompleteHorizontalClassMergerCode code =
                    (IncompleteHorizontalClassMergerCode) method.getDefinition().getCode();
                Code newCode =
                    mode.isInitial()
                        ? code.toCfCode(
                            appView.withClassHierarchy(), method, horizontalClassMergerGraphLens)
                        : code.toLirCode(
                            appView.withClassHierarchy(), method, horizontalClassMergerGraphLens);
                if (newCode != null) {
                  method.setCode(newCode, appView);
                }
              });
        },
        appView.options().getThreadingModule(),
        executorService);
  }

  private boolean verifyNoIncompleteCode(
      Collection<HorizontalMergeGroup> groups, ExecutorService executorService)
      throws ExecutionException {
    ThreadUtils.processItems(
        groups,
        group -> {
          assert !group
                  .getTarget()
                  .methods(
                      method ->
                          method.hasCode()
                              && method.getCode().isIncompleteHorizontalClassMergerCode())
                  .iterator()
                  .hasNext()
              : "Expected no incomplete code";
        },
        appView.options().getThreadingModule(),
        executorService);
    return true;
  }

  private DexApplication getNewApplication(HorizontallyMergedClasses mergedClasses) {
    // In the second round of class merging, we must forcefully remove the merged classes from the
    // application, since we won't run tree shaking before writing the application.
    if (mode.isInitial()) {
      return appView.app();
    } else {
      return appView
          .app()
          .builder()
          .removeProgramClasses(clazz -> mergedClasses.isMergeSource(clazz.getType()))
          .build();
    }
  }

  // TODO(b/270398965): Replace LinkedList.
  @SuppressWarnings("JdkObsolete")
  private List<HorizontalMergeGroup> getInitialGroups() {
    HorizontalMergeGroup initialClassGroup = new HorizontalMergeGroup();
    HorizontalMergeGroup initialInterfaceGroup = new HorizontalMergeGroup();
    for (DexProgramClass clazz : appView.appInfo().classesWithDeterministicOrder()) {
      if (clazz.isInterface()) {
        initialInterfaceGroup.add(clazz);
      } else {
        initialClassGroup.add(clazz);
      }
    }
    List<HorizontalMergeGroup> initialGroups = new LinkedList<>();
    initialGroups.add(initialClassGroup);
    initialGroups.add(initialInterfaceGroup);
    initialGroups.removeIf(HorizontalMergeGroup::isTrivial);
    return initialGroups;
  }

  /**
   * Prepare horizontal class merging by determining which virtual methods and constructors need to
   * be merged and how the merging should be performed.
   */
  private List<ClassMerger> initializeClassMergers(
      IRCodeProvider codeProvider,
      HorizontalClassMergerGraphLens.Builder lensBuilder,
      Collection<HorizontalMergeGroup> groups) {
    List<ClassMerger> classMergers = new ArrayList<>(groups.size());
    for (HorizontalMergeGroup group : groups) {
      assert group.isNonTrivial();
      assert group.hasInstanceFieldMap();
      assert group.hasTarget();
      classMergers.add(
          new ClassMerger.Builder(appView, codeProvider, group, mode).build(lensBuilder));
    }
    appView.dexItemFactory().clearTypeElementsCache();
    return classMergers;
  }

  /** Merges all class groups using {@link ClassMerger}. */
  private PrunedItems applyClassMergers(
      Collection<ClassMerger> classMergers,
      ClassMergerSharedData classMergerSharedData,
      ProfileCollectionAdditions profileCollectionAdditions,
      SyntheticInitializerConverter.Builder syntheticInitializerConverterBuilder,
      Consumer<VirtuallyMergedMethodsKeepInfo> virtuallyMergedMethodsKeepInfoConsumer) {
    PrunedItems.Builder prunedItemsBuilder = PrunedItems.builder().setPrunedApp(appView.app());
    for (ClassMerger merger : classMergers) {
      merger.mergeGroup(
          classMergerSharedData,
          profileCollectionAdditions,
          prunedItemsBuilder,
          syntheticInitializerConverterBuilder,
          virtuallyMergedMethodsKeepInfoConsumer);
    }
    appView.dexItemFactory().clearTypeElementsCache();
    return prunedItemsBuilder.build();
  }

  /**
   * Fix all references to merged classes using the {@link HorizontalClassMergerTreeFixer}.
   * Construct a graph lens containing all changes performed by horizontal class merging.
   */
  @SuppressWarnings("ReferenceEquality")
  private HorizontalClassMergerGraphLens createLens(
      ClassMergerSharedData classMergerSharedData,
      HorizontallyMergedClasses mergedClasses,
      HorizontalClassMergerGraphLens.Builder lensBuilder,
      ClassMergerMode mode,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    return new HorizontalClassMergerTreeFixer(
            appView, classMergerSharedData, mergedClasses, lensBuilder, mode)
        .run(executorService, timing);
  }

  @SuppressWarnings("ReferenceEquality")
  private static boolean verifyNoCyclesInInterfaceHierarchies(
      AppView<?> appView, Collection<HorizontalMergeGroup> groups) {
    for (HorizontalMergeGroup group : groups) {
      if (group.isClassGroup()) {
        continue;
      }
      assert appView.hasClassHierarchy();
      DexProgramClass interfaceClass = group.getTarget();
      appView
          .withClassHierarchy()
          .appInfo()
          .traverseSuperTypes(
              interfaceClass,
              (superType, subclass, isInterface) -> {
                assert superType != interfaceClass.getType()
                    : "Interface " + interfaceClass.getTypeName() + " inherits from itself";
                return TraversalContinuation.doContinue();
              });
    }
    return true;
  }
}
