// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking.ifrule.verticalclassmerging;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.AssumeMayHaveSideEffects;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoAccessModification;
import com.android.tools.r8.NoHorizontalClassMerging;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.shaking.ifrule.verticalclassmerging.MergedParameterTypeTest.MergedParameterTypeWithCollisionTest.SuperTestClass;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.FoundMethodSubject;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.stream.Collectors;

public class MergedReturnTypeTest extends MergedTypeBaseTest {

  static class TestClass {

    public static void main(String[] args) {
      System.out.print(method().getClass().getName());
    }

    @AssumeMayHaveSideEffects
    public static A method() {
      return new B();
    }
  }

  public MergedReturnTypeTest(TestParameters parameters, boolean enableVerticalClassMerging) {
    super(parameters, enableVerticalClassMerging);
  }

  @Override
  public void configure(R8FullTestBuilder builder) {
    super.configure(builder);
    builder.enableSideEffectAnnotations();
  }

  @Override
  public Class<?> getTestClass() {
    return TestClass.class;
  }

  @Override
  public String getConditionForProguardIfRule() {
    return "-if class **$TestClass { **$A method(); }";
  }

  @Override
  public String getExpectedStdout() {
    return B.class.getName();
  }

  public static class MergedReturnTypeWithCollisionTest extends MergedTypeBaseTest {

    @NoAccessModification
    @NoHorizontalClassMerging
    static class SuperTestClass {

      @AssumeMayHaveSideEffects
      @NeverInline
      public static A method() {
        return new B();
      }
    }

    static class TestClass extends SuperTestClass {

      public static void main(String[] args) {
        B obj = new B();
        if (obj == null) {
          System.out.print(TestClass.method().getClass().getName());
        }
        System.out.print(SuperTestClass.method().getClass().getName());
      }

      public static A method() {
        return new B();
      }
    }

    public MergedReturnTypeWithCollisionTest(
        TestParameters parameters, boolean enableVerticalClassMerging) {
      super(parameters, enableVerticalClassMerging, ImmutableList.of(SuperTestClass.class));
    }

    @Override
    public void configure(R8FullTestBuilder builder) {
      super.configure(builder);
      builder
          .addVerticallyMergedClassesInspector(
              inspector ->
                  inspector
                      .applyIf(enableVerticalClassMerging, i -> i.assertMergedIntoSubtype(A.class))
                      .assertNoOtherClassesMerged())
          .enableInliningAnnotations()
          .enableNoHorizontalClassMergingAnnotations()
          .enableSideEffectAnnotations();
    }

    @Override
    public Class<?> getTestClass() {
      return TestClass.class;
    }

    @Override
    public String getConditionForProguardIfRule() {
      return "-if class **$SuperTestClass { **$A method(); }";
    }

    @Override
    public String getExpectedStdout() {
      return B.class.getName();
    }

    @Override
    public void inspect(CodeInspector inspector) {
      super.inspect(inspector);

      ClassSubject testClassSubject = inspector.clazz(TestClass.class);
      assertThat(testClassSubject, isPresent());

      if (enableVerticalClassMerging) {
        // Verify that TestClass.method has been removed.
        List<FoundMethodSubject> methods =
            testClassSubject.allMethods().stream()
                .filter(subject -> subject.getFinalName().contains("method"))
                .collect(Collectors.toList());
        assertEquals(1, methods.size());

        // Due to the -if rule, the SuperTestClass is only merged into TestClass after the final
        // round of tree shaking, at which point TestClass.method has already been removed.
        // Therefore, we expect no collision to have happened.
        assertEquals("method", methods.get(0).getFinalName());
      }
    }
  }
}
