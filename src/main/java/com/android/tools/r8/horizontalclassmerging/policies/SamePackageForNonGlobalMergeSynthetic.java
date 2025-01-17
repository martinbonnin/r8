// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging.policies;

import static com.android.tools.r8.utils.FunctionUtils.ignoreArgument;

import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.horizontalclassmerging.HorizontalMergeGroup;
import com.android.tools.r8.horizontalclassmerging.MultiClassPolicy;
import com.android.tools.r8.synthesis.SyntheticItems;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class SamePackageForNonGlobalMergeSynthetic extends MultiClassPolicy {

  private final AppView<AppInfo> appView;

  public SamePackageForNonGlobalMergeSynthetic(AppView<AppInfo> appView) {
    this.appView = appView;
  }

  /** Sort unrestricted classes into restricted classes if they are in the same package. */
  private void tryFindRestrictedPackage(
      HorizontalMergeGroup unrestrictedClasses,
      Map<String, HorizontalMergeGroup> restrictedClasses) {
    unrestrictedClasses.removeIf(
        clazz -> {
          HorizontalMergeGroup restrictedPackage =
              restrictedClasses.get(clazz.type.getPackageDescriptor());
          if (restrictedPackage != null) {
            restrictedPackage.add(clazz);
            return true;
          }
          return false;
        });
  }

  @Override
  public Collection<HorizontalMergeGroup> apply(HorizontalMergeGroup group) {
    Map<String, HorizontalMergeGroup> restrictedClasses = new LinkedHashMap<>();
    HorizontalMergeGroup unrestrictedClasses = new HorizontalMergeGroup();
    SyntheticItems syntheticItems = appView.getSyntheticItems();

    // Sort all restricted classes into packages.
    for (DexProgramClass clazz : group) {
      assert syntheticItems.isSynthetic(clazz.getType());
      if (Iterables.any(
          syntheticItems.getSyntheticKinds(clazz.getType()),
          kind ->
              !kind.isSyntheticMethodKind()
                  || !kind.asSyntheticMethodKind().isAllowGlobalMerging())) {
        restrictedClasses
            .computeIfAbsent(
                clazz.getType().getPackageDescriptor(), ignoreArgument(HorizontalMergeGroup::new))
            .add(clazz);
      } else {
        unrestrictedClasses.add(clazz);
      }
    }

    tryFindRestrictedPackage(unrestrictedClasses, restrictedClasses);
    removeTrivialGroups(restrictedClasses.values());

    Collection<HorizontalMergeGroup> groups = new ArrayList<>(restrictedClasses.size() + 1);
    if (unrestrictedClasses.size() > 1) {
      groups.add(unrestrictedClasses);
    }
    groups.addAll(restrictedClasses.values());
    return groups;
  }

  @Override
  public String getName() {
    return "SamePackageForApiOutline";
  }
}
