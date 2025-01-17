// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar.annotations.version2;

import com.android.tools.r8.ir.desugar.annotations.CovariantReturnType;
import com.android.tools.r8.ir.desugar.annotations.D;
import com.android.tools.r8.ir.desugar.annotations.E;

public class F extends E {
  @CovariantReturnType(returnType = E.class, presentAfter = 25)
  @CovariantReturnType(returnType = F.class, presentAfter = 28)
  @Override
  public D method() {
    return new F();
  }
}
