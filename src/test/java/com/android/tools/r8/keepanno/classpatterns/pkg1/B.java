// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.keepanno.classpatterns.pkg1;

public class B {

  public static String foo() {
    return "pkg1.B";
  }

  public static void foo(String arg) {
    System.out.println("pkg1.B: " + arg);
  }
}
