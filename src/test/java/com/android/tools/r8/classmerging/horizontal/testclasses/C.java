// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal.testclasses;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoAccessModification;

@NeverClassInline
public class C {

  @NoAccessModification String field;

  @NoAccessModification
  protected C(String field) {
    this.field = field;
  }

  @NeverInline
  public void bar() {
    System.out.println(field);
  }
}
