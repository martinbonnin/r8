// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal.interfaces;

import static com.android.tools.r8.utils.codeinspector.Matchers.isAbsent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isImplementing;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoHorizontalClassMerging;
import com.android.tools.r8.NoMethodStaticizing;
import com.android.tools.r8.NoUnusedInterfaceRemoval;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class CollisionWithDefaultMethodOutsideMergeGroupAfterSubclassMergingTest extends TestBase {

  @Parameter(0)
  public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  @Test
  public void test() throws Exception {
    parameters.assumeDexRuntime();
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        // I and J are not eligible for merging, since the merging of class B into class A
        // contributes the default method K.m() to A, and the merging of J into I would contribute
        // the default method J.m() to A.
        .addHorizontallyMergedClassesInspector(
            inspector -> {
              inspector
                  .assertIsCompleteMergeGroup(A.class, B.class)
                  .assertMergedInto(B.class, A.class);
              if (parameters.canUseDefaultAndStaticInterfaceMethods()) {
                inspector.assertClassesNotMerged(I.class, J.class, K.class);
              }
              inspector.assertNoOtherClassesMerged();
            })
        .enableInliningAnnotations()
        .enableNeverClassInliningAnnotations()
        .enableNoHorizontalClassMergingAnnotations()
        .enableNoMethodStaticizingAnnotations()
        .enableNoUnusedInterfaceRemovalAnnotations()
        .enableNoVerticalClassMergingAnnotations()
        .setMinApi(parameters)
        .compile()
        .inspect(
            inspector -> {
              ClassSubject aClassSubject = inspector.clazz(A.class);
              assertThat(aClassSubject, isPresent());
              assertThat(aClassSubject, isImplementing(inspector.clazz(I.class)));
              assertThat(aClassSubject, isImplementing(inspector.clazz(K.class)));

              ClassSubject cClassSubject = inspector.clazz(C.class);
              if (parameters.canUseDefaultAndStaticInterfaceMethods()) {
                assertThat(cClassSubject, isPresent());
                assertThat(cClassSubject, isImplementing(inspector.clazz(J.class)));
              } else {
                assertThat(cClassSubject, isAbsent());
              }
            })
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("A", "B", "K", "J");
  }

  static class Main {

    public static void main(String[] args) {
      new A().keepA();
      new B().m();
      new C().m();
    }
  }

  @NoUnusedInterfaceRemoval
  @NoVerticalClassMerging
  interface I {}

  @NoUnusedInterfaceRemoval
  @NoVerticalClassMerging
  interface J {
    @NeverInline
    @NoMethodStaticizing
    default void m() {
      System.out.println("J");
    }
  }

  @NoHorizontalClassMerging
  @NoUnusedInterfaceRemoval
  @NoVerticalClassMerging
  interface K {
    @NeverInline
    @NoMethodStaticizing
    default void m() {
      System.out.println("K");
    }
  }

  @NeverClassInline
  static class A implements I {

    @NeverInline
    @NoMethodStaticizing
    void keepA() {
      System.out.println("A");
    }
  }

  // Will be merged into A, which as a side effect contributes the default method K.m() to A.
  // This should prevent merging of J into I, since that would contribute the default method J.m()
  // to A.
  @NeverClassInline
  static class B implements K {

    @NeverInline
    B() {
      System.out.println("B");
    }
  }

  @NeverClassInline
  @NoHorizontalClassMerging
  static class C implements J {}
}
