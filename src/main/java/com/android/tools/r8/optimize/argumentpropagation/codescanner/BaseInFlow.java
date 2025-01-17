// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.optimize.argumentpropagation.codescanner;

public interface BaseInFlow extends InFlow {

  static BaseInFlow asBaseInFlowOrNull(InFlow inFlow) {
    return inFlow != null ? inFlow.asBaseInFlow() : null;
  }

  @Override
  default boolean isBaseInFlow() {
    return true;
  }

  @Override
  default BaseInFlow asBaseInFlow() {
    return this;
  }
}
