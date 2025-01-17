// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.reflection;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ForNameInterfaceTest extends TestBase {

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public ForNameInterfaceTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  public interface I {}

  public @interface J {}

  public static class Main {

    public static void main(String[] args) throws ClassNotFoundException {
      Class<?> aClass =
          Class.forName("com.android.tools.r8.ir.optimize.reflection.ForNameInterfaceTest$I");
      System.out.println(aClass.getName());
      aClass = Class.forName("com.android.tools.r8.ir.optimize.reflection.ForNameInterfaceTest$J");
      System.out.println(aClass.getName());
    }
  }

  @Test
  public void testForNameOnInterface()
      throws ExecutionException, CompilationFailedException, IOException {
    testForR8(parameters.getBackend())
        .addInnerClasses(ForNameInterfaceTest.class)
        .addKeepMainRule(Main.class)
        .addDontObfuscate()
        .setMinApi(parameters)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(
            "com.android.tools.r8.ir.optimize.reflection.ForNameInterfaceTest$I",
            "com.android.tools.r8.ir.optimize.reflection.ForNameInterfaceTest$J");
  }
}
