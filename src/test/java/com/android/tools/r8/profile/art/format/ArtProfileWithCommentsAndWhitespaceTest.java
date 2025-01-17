// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.profile.art.format;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestDiagnosticMessages;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8.profile.art.ArtProfileBuilder;
import com.android.tools.r8.profile.art.ArtProfileProvider;
import com.android.tools.r8.profile.art.utils.ArtProfileInspector;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.MethodReferenceUtils;
import com.android.tools.r8.utils.UTF8TextInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ArtProfileWithCommentsAndWhitespaceTest extends TestBase {

  private static final MethodReference MAIN_METHOD_REFERENCE =
      MethodReferenceUtils.mainMethod(Main.class);

  private static Path profileWithComments;

  @Parameter(0)
  public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withDefaultRuntimes().withMinimumApiLevel().build();
  }

  @BeforeClass
  public static void setup() throws Exception {
    profileWithComments = getStaticTemp().newFile().toPath();
    FileUtils.writeTextFile(
        profileWithComments,
        "# Comment 1",
        " # Comment 2",
        " ",
        "\t",
        "HSP" + MethodReferenceUtils.toSmaliString(MAIN_METHOD_REFERENCE) + " # Comment 3",
        "# Comment 4");
  }

  @Test
  public void testD8() throws Exception {
    parameters.assumeDexRuntime();
    testForD8()
        .addProgramClasses(Main.class)
        .addArtProfileForRewriting(getArtProfileProvider())
        .release()
        .setMinApi(parameters)
        .compileWithExpectedDiagnostics(TestDiagnosticMessages::assertNoMessages)
        .inspectResidualArtProfile(this::inspectResidualArtProfile);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addProgramClasses(Main.class)
        .addKeepMainRule(Main.class)
        .addArtProfileForRewriting(getArtProfileProvider())
        .setMinApi(parameters)
        .compileWithExpectedDiagnostics(TestDiagnosticMessages::assertNoMessages)
        .inspectResidualArtProfile(this::inspectResidualArtProfile);
  }

  private ArtProfileProvider getArtProfileProvider() {
    return new ArtProfileProvider() {

      @Override
      public void getArtProfile(ArtProfileBuilder profileBuilder) {
        try {
          profileBuilder.addHumanReadableArtProfile(
              new UTF8TextInputStream(profileWithComments), parserBuilder -> {});
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      @Override
      public Origin getOrigin() {
        return new PathOrigin(profileWithComments);
      }
    };
  }

  private void inspectResidualArtProfile(ArtProfileInspector profileInspector) {
    profileInspector
        .assertContainsClassRule(MAIN_METHOD_REFERENCE.getHolderClass())
        .assertContainsMethodRule(MAIN_METHOD_REFERENCE)
        .assertContainsNoOtherRules();
  }

  static class Main {

    public static void main(String[] args) {
      System.out.println("Hello, world!");
    }
  }
}
