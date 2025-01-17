// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.vertical;


import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.BooleanUtils;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Regression test for b/133501933. */
@RunWith(Parameterized.class)
public class IncorrectRewritingOfInvokeSuperTest extends TestBase {

  @Parameter(0)
  public TestParameters parameters;

  @Parameter(1)
  public boolean verifyLensLookup;

  @Parameters(name = "{0}, verify: {1}")
  public static List<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimesAndApiLevels().build(), BooleanUtils.values());
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(IncorrectRewritingOfInvokeSuperTest.class)
        .addKeepMainRule(TestClass.class)
        .addOptionsModification(
            options -> {
              options.enableUnusedInterfaceRemoval = false;
              options.testing.enableVerticalClassMergerLensAssertion = verifyLensLookup;
            })
        .enableInliningAnnotations()
        .enableNoVerticalClassMergingAnnotations()
        .addDontObfuscate()
        .addVerticallyMergedClassesInspector(
            inspector -> inspector.assertMergedIntoSubtype(A.class, ArgType.class))
        .setMinApi(parameters)
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutputLines("Caught NPE");
  }

  static class TestClass {

    public static void main(String[] args) {
      B b = new B() {};
      b.m(new SubArgType());
      try {
        b.m(null);
      } catch (RuntimeException e) {
        System.out.println("Caught NPE");
      }
    }
  }

  interface I<U> {

    void m(U arg);
  }

  abstract static class A implements I<ArgType> {

    @NeverInline
    @Override
    public void m(ArgType x) {
      if (x == null) {
        throw new RuntimeException();
      }
    }
  }

  @NoVerticalClassMerging
  static class B extends A {

    @NeverInline
    @Override
    public final void m(ArgType x) {
      super.m(x);
    }
  }

  static class ArgType {}

  static class SubArgType extends ArgType {}
}
