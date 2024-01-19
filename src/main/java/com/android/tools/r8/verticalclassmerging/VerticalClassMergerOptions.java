// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.verticalclassmerging;

import com.android.tools.r8.classmerging.ClassMergerMode;
import com.android.tools.r8.utils.InternalOptions;

public class VerticalClassMergerOptions {

  private final InternalOptions options;

  private boolean enabled = true;

  public VerticalClassMergerOptions(InternalOptions options) {
    this.options = options;
  }

  public void disable() {
    setEnabled(false);
  }

  public boolean isEnabled(ClassMergerMode mode) {
    if (!enabled || !options.isOptimizing() || !options.isShrinking()) {
      return false;
    }
    // TODO(b/320431939): Enable final round of vertical class merging for desugared library.
    if (mode.isFinal() && !options.synthesizedClassPrefix.isEmpty()) {
      return false;
    }
    return true;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
