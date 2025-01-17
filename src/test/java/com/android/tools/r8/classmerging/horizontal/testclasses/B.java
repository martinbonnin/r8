// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal.testclasses;

import com.android.tools.r8.KeepConstantArguments;
import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoMethodStaticizing;

@NeverClassInline
public class B {

  @KeepConstantArguments
  protected B(String a) {
    System.out.println(a);
  }

  @NeverInline
  @NoMethodStaticizing
  public void bar() {
    System.out.println("bar");
  }
}
