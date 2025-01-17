// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.optimize.argumentpropagation.codescanner;

public enum InFlowKind {
  ABSTRACT_COMPUTATION,
  ABSTRACT_FUNCTION_CAST,
  ABSTRACT_FUNCTION_IDENTITY,
  ABSTRACT_FUNCTION_IF_THEN_ELSE,
  ABSTRACT_FUNCTION_INSTANCE_FIELD_READ,
  FIELD,
  METHOD_PARAMETER
}
