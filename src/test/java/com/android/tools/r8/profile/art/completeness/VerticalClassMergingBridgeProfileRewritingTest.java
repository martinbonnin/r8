// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.profile.art.completeness;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.profile.art.model.ExternalArtProfile;
import com.android.tools.r8.profile.art.utils.ArtProfileInspector;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.utils.InternalOptions.InlinerOptions;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class VerticalClassMergingBridgeProfileRewritingTest extends TestBase {

  @Parameter(0)
  public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        .addArtProfileForRewriting(getArtProfile())
        .addOptionsModification(InlinerOptions::disableInlining)
        .addOptionsModification(
            options -> {
              options.callSiteOptimizationOptions().disableOptimization();
              options.getVerticalClassMergerOptions().setEnableBridgeAnalysis(false);
            })
        .addVerticallyMergedClassesInspector(
            inspector -> inspector.assertMergedIntoSubtype(A.class))
        .enableNeverClassInliningAnnotations()
        .setMinApi(parameters)
        .compile()
        .inspectResidualArtProfile(this::inspect)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("Hello, world!");
  }

  private ExternalArtProfile getArtProfile() throws Exception {
    return ExternalArtProfile.builder()
        .addMethodRule(Reference.methodFromMethod(A.class.getDeclaredMethod("m", A.class)))
        .build();
  }

  private void inspect(ArtProfileInspector profileInspector, CodeInspector inspector) {
    ClassSubject bClassSubject = inspector.clazz(B.class);
    assertThat(bClassSubject, isPresent());

    MethodSubject movedMethodSubject =
        bClassSubject.uniqueMethodThatMatches(
            method -> method.isBridge() && method.isSynthetic() && method.isVirtual());
    assertThat(movedMethodSubject, isPresent());

    MethodSubject syntheticBridgeMethodSubject =
        bClassSubject.uniqueMethodThatMatches(
            method -> !method.isBridge() && !method.isSynthetic() && method.isVirtual());
    assertThat(syntheticBridgeMethodSubject, isPresent());

    profileInspector
        .assertContainsClassRule(bClassSubject)
        .assertContainsMethodRules(movedMethodSubject, syntheticBridgeMethodSubject)
        .assertContainsNoOtherRules();
  }

  static class Main {

    public static void main(String[] args) {
      new B().m(null);
    }
  }

  static class A {

    public void m(A a) {
      System.out.println("Hello, world!");
    }
  }

  @NeverClassInline
  static class B extends A {}
}
