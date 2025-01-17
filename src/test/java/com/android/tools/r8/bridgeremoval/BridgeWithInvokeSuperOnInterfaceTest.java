// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.bridgeremoval;

import static com.android.tools.r8.utils.codeinspector.Matchers.isAbsent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.transformers.ClassFileTransformer.MethodPredicate;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.FoundMethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class BridgeWithInvokeSuperOnInterfaceTest extends TestBase {

  private static final String[] EXPECTED = new String[] {"I::foo"};

  @Parameter() public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  @Test
  public void testRuntime() throws Exception {
    testForRuntime(parameters)
        .addProgramClasses(Main.class, I.class)
        .addProgramClassFileData(getJWithBridgeAccessFlag())
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(EXPECTED);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addProgramClasses(Main.class, I.class)
        .addProgramClassFileData(getJWithBridgeAccessFlag())
        .setMinApi(parameters)
        .addKeepMainRule(Main.class)
        .enableNoVerticalClassMergingAnnotations()
        .enableInliningAnnotations()
        .addDontObfuscate()
        .compile()
        .inspect(
            inspector -> {
              // Check that we are removing the bridge if we support default methods.
              if (parameters.canUseDefaultAndStaticInterfaceMethods()) {
                ClassSubject J = inspector.clazz(J.class);
                assertThat(J, isAbsent());
                assertTrue(
                    inspector.allClasses().stream()
                        .allMatch(
                            classSubject ->
                                classSubject.allMethods(FoundMethodSubject::isBridge).isEmpty()));
              }
            })
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(EXPECTED);
  }

  private byte[] getJWithBridgeAccessFlag() throws Exception {
    return transformer(J.class)
        .setAccessFlags(MethodPredicate.onName("foo"), MethodAccessFlags::setBridge)
        .transform();
  }

  @NoVerticalClassMerging
  public interface I {

    @NeverInline
    default void foo() {
      System.out.println("I::foo");
    }
  }

  @NoVerticalClassMerging
  public interface J extends I {

    @Override
    default void foo() {
      I.super.foo();
    }
  }

  public static class Main implements J {

    public static void main(String[] args) {
      new Main().callSuper();
    }

    @NeverInline
    public void callSuper() {
      J.super.foo();
    }
  }
}
