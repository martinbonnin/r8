// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal.testclasses;

import com.android.tools.r8.NeverPropagateValue;
import com.android.tools.r8.NoAccessModification;
import com.android.tools.r8.NoVerticalClassMerging;

public class NonReboundFieldAccessOnMergedClassTestClasses {

  @NoAccessModification
  @NoVerticalClassMerging
  static class A {

    @NeverPropagateValue public String greeting;

    A(String greeting) {
      this.greeting = greeting;
    }
  }

  @NoVerticalClassMerging
  public static class B extends A {

    public B(String greeting) {
      super(greeting);
    }
  }
}
