// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.unusedarguments;

import static org.junit.Assert.assertEquals;

import com.android.tools.r8.KeepConstantArguments;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class UnusedArgumentsIntTest extends UnusedArgumentsTestBase {
  private static final Set<String> methodsThatWontBeOptimized = ImmutableSet.of("main", "iinc");

  public UnusedArgumentsIntTest(TestParameters parameters, boolean minification) {
    super(parameters, minification);
  }

  static class TestClass {

    @KeepConstantArguments
    @NeverInline
    public static int a(int a) {
      return a;
    }

    @KeepConstantArguments
    @NeverInline
    public static int a(int a, int b) {
      return a;
    }

    @KeepConstantArguments
    @NeverInline
    public static int iinc(int a, int b) {
      b++;
      return a;
    }

    @KeepConstantArguments
    @NeverInline
    public static int a(int a, int b, int c) {
      return a;
    }

    public static void main(String[] args) {
      System.out.print(a(1));
      System.out.print(a(2, 3));
      System.out.print(a(4, 5, 6));
      System.out.print(iinc(8, 3));
    }
  }

  @Override
  public void configure(R8FullTestBuilder builder) {
    super.configure(builder);
    builder.enableConstantArgumentAnnotations();
  }

  @Override
  public Class<?> getTestClass() {
    return TestClass.class;
  }

  @Override
  public String getExpectedResult() {
    return "1248";
  }

  @Override
  public void inspectTestClass(ClassSubject clazz) {
    assertEquals(5, clazz.allMethods().size());
    clazz.forAllMethods(
        method -> {
          Assert.assertTrue(
              methodsThatWontBeOptimized.contains(method.getOriginalMethodName())
                  || (method.getFinalSignature().parameters.length == 1
                      && method.getFinalSignature().parameters[0].equals("int")));
        });
  }
}
