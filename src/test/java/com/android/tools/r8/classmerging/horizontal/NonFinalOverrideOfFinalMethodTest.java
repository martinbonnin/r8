// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoMethodStaticizing;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runners.Parameterized;

public class NonFinalOverrideOfFinalMethodTest extends HorizontalClassMergingTestBase {

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public NonFinalOverrideOfFinalMethodTest(TestParameters parameters) {
    super(parameters);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        .enableInliningAnnotations()
        .enableNeverClassInliningAnnotations()
        .enableNoMethodStaticizingAnnotations()
        .enableNoVerticalClassMergingAnnotations()
        .setMinApi(parameters)
        .addHorizontallyMergedClassesInspector(
            inspector -> inspector.assertMergedInto(B.class, A.class).assertNoOtherClassesMerged())
        .compile()
        .inspect(
            inspector -> {
              ClassSubject classSubject = inspector.clazz(A.class);
              assertThat(classSubject, isPresent());

              MethodSubject methodSubject = classSubject.uniqueMethodWithOriginalName("foo");
              assertThat(methodSubject, isPresent());
              assertFalse(methodSubject.isFinal());
            })
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("A", "B", "BSub");
  }

  public static class Main {
    public static void main(String[] args) {
      new A().foo();
      new B().bar();
      new BSub().foo();
    }
  }

  @NeverClassInline
  @NoVerticalClassMerging
  public static class A {

    @NeverInline
    @NoMethodStaticizing
    public final void foo() {
      System.out.println("A");
    }
  }

  @NeverClassInline
  @NoVerticalClassMerging
  public static class B {

    @NeverInline
    @NoMethodStaticizing
    public void bar() {
      System.out.println("B");
    }
  }

  @NeverClassInline
  public static class BSub extends B {

    @NeverInline
    @NoMethodStaticizing
    public void foo() {
      System.out.println("BSub");
    }
  }
}
