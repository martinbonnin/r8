// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.keepanno;

import static com.android.tools.r8.utils.codeinspector.Matchers.isAbsent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.keepanno.annotations.KeepBinding;
import com.android.tools.r8.keepanno.annotations.KeepCondition;
import com.android.tools.r8.keepanno.annotations.KeepEdge;
import com.android.tools.r8.keepanno.annotations.KeepTarget;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

@RunWith(Parameterized.class)
public class KeepBindingTest extends KeepAnnoTestBase {

  static final String EXPECTED = StringUtils.lines("A::foo");

  @Parameter public KeepAnnoParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static List<KeepAnnoParameters> data() {
    return createParameters(
        getTestParameters().withDefaultRuntimes().withApiLevel(AndroidApiLevel.B).build());
  }

  @Test
  public void test() throws Exception {
    testForKeepAnno(parameters)
        .addProgramClasses(getInputClasses())
        .addKeepClassRules(A.class, B.class)
        .addKeepMainRule(TestClass.class)
        .setExcludedOuterClass(getClass())
        .run(TestClass.class)
        .assertSuccessWithOutput(EXPECTED)
        .applyIf(parameters.isShrinker(), r -> r.inspect(i -> checkOutput(i, true)));
  }

  @Test
  public void testNoKeepOnClass() throws Exception {
    testForKeepAnno(parameters)
        .addProgramClasses(getInputClasses())
        .addKeepMainRule(TestClass.class)
        .setExcludedOuterClass(getClass())
        .run(TestClass.class)
        .assertSuccessWithOutput(EXPECTED)
        .applyIf(parameters.isShrinker(), r -> r.inspect(i -> checkOutput(i, false)));
  }

  public List<Class<?>> getInputClasses() {
    return ImmutableList.of(TestClass.class, A.class, B.class, C.class);
  }

  private void checkOutput(CodeInspector inspector, boolean expectB) {
    assertThat(inspector.clazz(A.class), isPresent());
    assertThat(inspector.clazz(A.class).uniqueMethodWithOriginalName("foo"), isPresent());
    if (expectB) {
      assertThat(inspector.clazz(B.class), isPresent());
      assertThat(
          inspector.clazz(B.class).uniqueMethodWithOriginalName("foo"),
          parameters.isPG() ? isPresent() : isAbsent());
    } else {
      assertThat(inspector.clazz(B.class), isAbsent());
    }
    assertThat(inspector.clazz(C.class), isAbsent());
  }

  static class A {
    public void foo() {
      System.out.println("A::foo");
    }

    public void bar() throws Exception {
      getClass().getDeclaredMethod("foo").invoke(this);
    }
  }

  static class B {
    public void foo() {
      System.out.println("B::foo");
    }

    public void bar() throws Exception {
      getClass().getDeclaredMethod("foo").invoke(this);
    }
  }

  static class C {
    public void foo() {
      System.out.println("C::foo");
    }

    public void bar() throws Exception {
      getClass().getDeclaredMethod("foo").invoke(this);
    }
  }

  /**
   * This conditional rule expresses that if any class in the program has a live "bar" method then
   * that same classes "foo" method is to be kept. The binding(s) establishes the relation between
   * the holder of the two methods.
   */
  @KeepEdge(
      bindings = {
        @KeepBinding(bindingName = "Holder"),
        @KeepBinding(bindingName = "BarMethod", classFromBinding = "Holder", methodName = "bar"),
        @KeepBinding(bindingName = "FooMethod", classFromBinding = "Holder", methodName = "foo")
      },
      preconditions = {@KeepCondition(memberFromBinding = "BarMethod")},
      consequences = {@KeepTarget(memberFromBinding = "FooMethod")})
  static class TestClass {

    public static void main(String[] args) throws Exception {
      new A().bar();
    }
  }
}
