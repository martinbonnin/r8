// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoMethodStaticizing;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestParameters;
import org.junit.Test;

public class InheritOverrideInterfaceTest extends HorizontalClassMergingTestBase {
  public InheritOverrideInterfaceTest(TestParameters parameters) {
    super(parameters);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        .addHorizontallyMergedClassesInspector(
            inspector ->
                inspector.assertIsCompleteMergeGroup(A.class, B.class).assertNoOtherClassesMerged())
        .enableInliningAnnotations()
        .enableNeverClassInliningAnnotations()
        .enableNoMethodStaticizingAnnotations()
        .enableNoVerticalClassMergingAnnotations()
        .setMinApi(parameters)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("A", "B", "A");
  }

  @NoVerticalClassMerging
  interface I {
    @NeverInline
    default void m() {
      System.out.println("I");
    }
  }

  @NeverClassInline
  public static class A implements I {
    @NeverInline
    @NoMethodStaticizing
    @Override
    public void m() {
      System.out.println("A");
    }
  }

  @NeverClassInline
  public static class B implements I {
    @NeverInline
    @NoMethodStaticizing
    @Override
    public void m() {
      System.out.println("B");
    }
  }

  @NoVerticalClassMerging
  interface J extends I {
    default void m() {
      System.out.println("J");
    }
  }

  @NeverClassInline
  public static class C extends A implements J {}

  public static class Main {
    @NeverInline
    public static void doI(I i) {
      i.m();
    }

    public static void main(String[] args) {
      doI(new A());
      doI(new B());
      doI(new C());
    }
  }
}
