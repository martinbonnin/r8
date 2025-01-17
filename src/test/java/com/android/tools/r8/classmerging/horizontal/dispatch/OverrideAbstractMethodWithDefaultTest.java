// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal.dispatch;

import static com.android.tools.r8.utils.codeinspector.Matchers.isAbsent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresentIf;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.classmerging.horizontal.HorizontalClassMergingTestBase;
import org.junit.Test;

public class OverrideAbstractMethodWithDefaultTest extends HorizontalClassMergingTestBase {

  public OverrideAbstractMethodWithDefaultTest(TestParameters parameters) {
    super(parameters);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        .addHorizontallyMergedClassesInspector(
            inspector ->
                inspector
                    .applyIf(
                        parameters.canUseDefaultAndStaticInterfaceMethods(),
                        i -> i.assertIsCompleteMergeGroup(I.class, J.class),
                        i -> i.assertIsCompleteMergeGroup(B1.class, B2.class))
                    .assertNoOtherClassesMerged())
        .enableInliningAnnotations()
        .enableNeverClassInliningAnnotations()
        .enableNoVerticalClassMergingAnnotations()
        .setMinApi(parameters)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("J", "B2")
        .inspect(
            codeInspector -> {
              assertThat(codeInspector.clazz(I.class), isPresent());
              assertThat(codeInspector.clazz(J.class), isAbsent());
              assertThat(
                  codeInspector.clazz(A.class),
                  isPresentIf(parameters.canUseDefaultAndStaticInterfaceMethods()));
              assertThat(codeInspector.clazz(B1.class), isPresent());
              assertThat(
                  codeInspector.clazz(B2.class),
                  isPresentIf(parameters.canUseDefaultAndStaticInterfaceMethods()));
              assertThat(codeInspector.clazz(C1.class), isPresent());
              assertThat(codeInspector.clazz(C2.class), isPresent());
            });
  }

  @NoVerticalClassMerging
  interface I {
    void m();
  }

  @NoVerticalClassMerging
  interface J extends I {
    default void m() {
      System.out.println("J");
    }
  }

  abstract static class A implements I {}

  @NoVerticalClassMerging
  abstract static class B1 extends A {}

  @NoVerticalClassMerging
  abstract static class B2 extends A {
    @Override
    @NeverInline
    public void m() {
      System.out.println("B2");
    }
  }

  @NeverClassInline
  static class C1 extends B1 implements J {}

  @NeverClassInline
  static class C2 extends B2 {}

  static class Main {
    @NeverInline
    public static void doI(I i) {
      i.m();
    }

    public static void main(String[] args) {
      doI(new C1());
      doI(new C2());
    }
  }
}
