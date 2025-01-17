// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils.codeinspector;

import com.android.tools.r8.errors.Unimplemented;

public abstract class Subject {

  public final boolean isAbsent() {
    return !isPresent();
  }

  public abstract boolean isPresent();

  public abstract boolean isRenamed();

  public abstract boolean isSynthetic();

  public boolean isCompilerSynthesized() {
    throw new Unimplemented(
        "Predicate not yet supported on Subject: " + getClass().getSimpleName());
  }
}
