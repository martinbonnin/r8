// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.DataResourceProvider;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.synthesis.SyntheticDefinitionsProvider;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public abstract class DexApplication implements DexDefinitionSupplier {

  public final ImmutableList<DataResourceProvider> dataResourceProviders;

  private final ClassNameMapper proguardMap;

  public final Timing timing;

  public final InternalOptions options;
  public final DexItemFactory dexItemFactory;
  private final DexApplicationReadFlags flags;

  /** Constructor should only be invoked by the DexApplication.Builder. */
  DexApplication(
      ClassNameMapper proguardMap,
      DexApplicationReadFlags flags,
      ImmutableList<DataResourceProvider> dataResourceProviders,
      InternalOptions options,
      Timing timing) {
    this.proguardMap = proguardMap;
    this.flags = flags;
    this.dataResourceProviders = dataResourceProviders;
    this.options = options;
    this.dexItemFactory = options.itemFactory;
    this.timing = timing;
  }

  public abstract Builder<?> builder();

  @Override
  public DexItemFactory dexItemFactory() {
    return dexItemFactory;
  }

  public DexDefinitionSupplier getDefinitionsSupplier(
      SyntheticDefinitionsProvider syntheticDefinitionsProvider) {
    DexApplication self = this;
    return new DexDefinitionSupplier() {
      @Override
      public ClassResolutionResult contextIndependentDefinitionForWithResolutionResult(
          DexType type) {
        return syntheticDefinitionsProvider.definitionFor(
            type, self::contextIndependentDefinitionForWithResolutionResult);
      }

      @Override
      public DexClass definitionFor(DexType type) {
        return syntheticDefinitionsProvider
            .definitionFor(type, self::contextIndependentDefinitionForWithResolutionResult)
            .toSingleClassWithProgramOverLibrary();
      }

      @Override
      public DexItemFactory dexItemFactory() {
        return self.dexItemFactory;
      }
    };
  }

  // Reorder classes randomly. Note that the order of classes in program or library
  // class collections should not matter for compilation of valid code and when running
  // with assertions enabled we reorder the classes randomly to catch possible issues.
  // Also note that the order may add to non-determinism in reporting errors for invalid
  // code, but this non-determinism exists even with the same order of classes since we
  // may process classes concurrently and fail-fast on the first error.
  private static class ReorderBox<T> {

    private Collection<T> classes;

    ReorderBox(Collection<T> classes) {
      this.classes = classes;
    }

    boolean reorderClasses() {
      if (!InternalOptions.DETERMINISTIC_DEBUGGING) {
        List<T> shuffled = new ArrayList<>(classes);
        Collections.shuffle(shuffled);
        classes = ImmutableList.copyOf(shuffled);
      }
      return true;
    }

    Collection<T> getClasses() {
      return classes;
    }
  }

  abstract Collection<DexProgramClass> programClasses();

  public abstract void forEachProgramType(Consumer<DexType> consumer);

  public abstract void forEachLibraryType(Consumer<DexType> consumer);

  public Collection<DexProgramClass> classes() {
    ReorderBox<DexProgramClass> box = new ReorderBox<>(programClasses());
    assert box.reorderClasses();
    return box.getClasses();
  }

  public Collection<DexProgramClass> classesWithDeterministicOrder() {
    Comparator<ClassDefinition> comparator = Comparator.comparing(ClassDefinition::getType);
    if (options.testing.reverseClassSortingForDeterminism) {
      comparator = comparator.reversed();
    }
    return classesWithDeterministicOrder(new ArrayList<>(programClasses()), comparator);
  }

  public static <T extends ClassDefinition> List<T> classesWithDeterministicOrder(
      Collection<T> classes) {
    return classesWithDeterministicOrder(new ArrayList<>(classes));
  }

  public static <T extends ClassDefinition> List<T> classesWithDeterministicOrder(List<T> classes) {
    // To keep the order deterministic, we sort the classes by their type, which is a unique key.
    classes.sort(Comparator.comparing(ClassDefinition::getType));
    return classes;
  }

  private static Collection<DexProgramClass> classesWithDeterministicOrder(
      List<DexProgramClass> classes, Comparator<ClassDefinition> comparator) {
    classes.sort(comparator);
    return classes;
  }

  public DexApplicationReadFlags getFlags() {
    return flags;
  }

  @Override
  public abstract DexClass definitionFor(DexType type);

  public abstract DexProgramClass programDefinitionFor(DexType type);

  @Override
  public abstract String toString();

  public ClassNameMapper getProguardMap() {
    return proguardMap;
  }

  public abstract static class Builder<T extends Builder<T>> {

    private final List<DexProgramClass> programClasses = new ArrayList<>();

    final List<DataResourceProvider> dataResourceProviders = new ArrayList<>();

    public final InternalOptions options;
    public final DexItemFactory dexItemFactory;
    ClassNameMapper proguardMap;
    final Timing timing;
    DexApplicationReadFlags flags;

    private final Collection<DexProgramClass> synthesizedClasses;

    public Builder(InternalOptions options, Timing timing) {
      this.options = options;
      this.dexItemFactory = options.itemFactory;
      this.timing = timing;
      this.synthesizedClasses = new ArrayList<>();
    }

    abstract T self();

    public Builder(DexApplication application) {
      flags = application.flags;
      programClasses.addAll(application.programClasses());
      dataResourceProviders.addAll(application.dataResourceProviders);
      proguardMap = application.getProguardMap();
      timing = application.timing;
      options = application.options;
      dexItemFactory = application.dexItemFactory;
      synthesizedClasses = new ArrayList<>();
    }

    public boolean isDirect() {
      return false;
    }

    public DirectMappedDexApplication.Builder asDirect() {
      return null;
    }

    public void setFlags(DexApplicationReadFlags flags) {
      this.flags = flags;
    }

    public synchronized T setProguardMap(ClassNameMapper proguardMap) {
      assert this.proguardMap == null;
      this.proguardMap = proguardMap;
      return self();
    }

    public synchronized T removeProgramClasses(Predicate<DexProgramClass> predicate) {
      this.programClasses.removeIf(predicate);
      return self();
    }

    public synchronized T replaceProgramClasses(Collection<DexProgramClass> newProgramClasses) {
      assert newProgramClasses != null;
      this.programClasses.clear();
      this.programClasses.addAll(newProgramClasses);

      DexApplicationReadFlags.Builder builder = DexApplicationReadFlags.builder();
      builder.setHasReadProgramClassFromDex(this.flags.hasReadProgramClassFromDex());
      builder.setHasReadProgramClassFromCf(this.flags.hasReadProgramClassFromCf());
      this.programClasses.forEach(
          clazz -> {
            DexType type = clazz.getType();
            if (flags.getRecordWitnesses().contains(type)) {
              builder.addRecordWitness(type);
            }
            if (flags.getVarHandleWitnesses().contains(type)) {
              builder.addVarHandleWitness(type);
            }
            if (flags.getMethodHandlesLookupWitnesses().contains(type)) {
              builder.addMethodHandlesLookupWitness(type);
            }
          });
      this.flags = builder.build();

      return self();
    }

    public synchronized T addDataResourceProvider(DataResourceProvider provider) {
      dataResourceProviders.add(provider);
      return self();
    }

    public synchronized T addProgramClass(DexProgramClass clazz) {
      programClasses.add(clazz);
      return self();
    }

    public abstract void addProgramClassPotentiallyOverridingNonProgramClass(DexProgramClass clazz);

    public synchronized T addProgramClasses(Collection<DexProgramClass> classes) {
      programClasses.addAll(classes);
      return self();
    }

    public synchronized T addSynthesizedClass(DexProgramClass synthesizedClass) {
      assert synthesizedClass.isProgramClass() : "All synthesized classes must be program classes";
      addProgramClass(synthesizedClass);
      synthesizedClasses.add(synthesizedClass);
      return self();
    }

    public List<DexProgramClass> getProgramClasses() {
      return programClasses;
    }

    public Collection<DexProgramClass> getSynthesizedClasses() {
      return synthesizedClasses;
    }

    public abstract DexApplication build();
  }

  public static LazyLoadedDexApplication.Builder builder(InternalOptions options, Timing timing) {
    return new LazyLoadedDexApplication.Builder(options, timing);
  }

  public DirectMappedDexApplication asDirect() {
    throw new Unreachable("Cannot use a LazyDexApplication where a DirectDexApplication is"
        + " expected.");
  }

  public abstract DirectMappedDexApplication toDirect();

  public abstract boolean isDirect();
}
