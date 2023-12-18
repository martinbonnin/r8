// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.verticalclassmerging.policies;

import com.android.tools.r8.verticalclassmerging.VerticalMergeGroup;

public class NoAnnotationClassesPolicy extends VerticalClassMergerPolicy {

  @Override
  public boolean canMerge(VerticalMergeGroup group) {
    return !group.getSource().isAnnotation();
  }

  @Override
  public String getName() {
    return "NoAnnotationClassesPolicy";
  }
}
