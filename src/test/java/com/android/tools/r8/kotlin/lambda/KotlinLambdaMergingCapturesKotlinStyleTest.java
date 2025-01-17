// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin.lambda;

import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.KotlinTestBase;
import com.android.tools.r8.KotlinTestParameters;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.HorizontallyMergedClassesInspector;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class KotlinLambdaMergingCapturesKotlinStyleTest extends KotlinTestBase {

  private final boolean allowAccessModification;
  private final TestParameters parameters;

  @Parameters(name = "{0}, {1}, allow access modification: {2}")
  public static Collection<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimesAndApiLevels().build(),
        getKotlinTestParameters().withAllCompilersLambdaGenerationsAndTargetVersions().build(),
        BooleanUtils.values());
  }

  public KotlinLambdaMergingCapturesKotlinStyleTest(
      TestParameters parameters,
      KotlinTestParameters kotlinParameters,
      boolean allowAccessModification) {
    super(kotlinParameters);
    this.allowAccessModification = allowAccessModification;
    this.parameters = parameters;
  }

  @Test
  public void testJVM() throws Exception {
    assumeFalse(allowAccessModification);
    assumeTrue(parameters.isCfRuntime());
    assumeTrue(kotlinParameters.isFirst());
    testForJvm(parameters)
        .addProgramFiles(getProgramFiles())
        .run(parameters.getRuntime(), getMainClassName())
        .assertSuccessWithOutput(getExpectedOutput());
  }

  @Test
  public void testR8() throws Exception {
    // Get the Kotlin lambdas in the input.
    KotlinLambdasInInput lambdasInInput =
        KotlinLambdasInInput.create(getProgramFiles(), getTestName());
    assertEquals(0, lambdasInInput.getNumberOfJStyleLambdas());
    assertEquals(
        kotlinParameters.getLambdaGeneration().isInvokeDynamic() ? 0 : 26,
        lambdasInInput.getNumberOfKStyleLambdas());

    testForR8(parameters.getBackend())
        .addProgramFiles(getProgramFiles())
        .addKeepMainRule(getMainClassName())
        .addHorizontallyMergedClassesInspector(
            HorizontallyMergedClassesInspector::assertNoClassesMerged)
        .allowAccessModification(allowAccessModification)
        .allowDiagnosticWarningMessages()
        .setMinApi(parameters)
        .compile()
        .assertAllWarningMessagesMatch(
            containsString("Resource 'META-INF/MANIFEST.MF' already exists."))
        .inspect(inspector -> inspect(inspector, lambdasInInput))
        .run(parameters.getRuntime(), getMainClassName())
        .assertSuccessWithOutput(getExpectedOutput());
  }

  private void inspect(CodeInspector inspector, KotlinLambdasInInput lambdasInInput) {
    List<ClassReference> presentKStyleLambdas = new ArrayList<>();
    for (ClassReference classReference : lambdasInInput.getKStyleLambdas()) {
      if (inspector.clazz(classReference).isPresent()) {
        presentKStyleLambdas.add(classReference);
      }
    }
    assertEquals(0, presentKStyleLambdas.size());
  }

  private String getExpectedOutput() {
    return StringUtils.lines(
        "a: 1 2 3",
        "b: 2 3 1",
        "c: 3 1 2",
        "d: 1 A D(d=x)",
        "e: 2 D(d=y) B",
        "f: 3 D(d=z) D(d=x)",
        "g: 7 D(d=z) 3",
        "h: 8 9 1",
        "i: A B C",
        "j: D(d=x) D(d=y) D(d=z)",
        "k: 7 8 9",
        "l: A D(d=y) 9",
        "n: 7 B D(d=z)",
        "o: D(d=x) 8 C",
        "p: 1 2 C",
        "a: true 10 * 20 30 40 50.0 60.0 D(d=D) S null 70",
        "a: true 10 D(d=D) S * 20 30 40 50.0 60.0 null 70",
        "a: true * 20 40 50.0 60.0 S null 70 10 30 D(d=D)",
        "a: D(d=D) S null 70 true 10 * 20 30 40 50.0 60.0",
        "a: true 10 * 20 30 40 50.0 60.0 D(d=D) S $o3 $o4",
        "a: true 10 * 20 30 40 50.0 60.0 D(d=D) $o2 $o3 70",
        "a: true 10 * 20 30 40 50.0 60.0 $o1 $o2 null 70",
        "a: true 10 * 20 30 40 50.0 60.0 $o1 S null $o4",
        "x: true 10 * 20 30 40 50.0 60.0 D(d=D) S $o3 70",
        "y: true 10 * 20 30 40 $f 60.0 D(d=D) S null 70",
        "z: true 10 * $s 30 40 50.0 60.0 D(d=D) S null 70");
  }

  private Path getJavaJarFile() {
    return getJavaJarFile(getTestName());
  }

  private String getMainClassName() {
    return getTestName() + ".MainKt";
  }

  private List<Path> getProgramFiles() {
    Path kotlinJarFile =
        getCompileMemoizer(getKotlinFilesInResource(getTestName()), getTestName())
            .configure(kotlinCompilerTool -> kotlinCompilerTool.includeRuntime().noReflect())
            .getForConfiguration(kotlinParameters);
    return ImmutableList.of(kotlinJarFile, getJavaJarFile(), kotlinc.getKotlinAnnotationJar());
  }

  private String getTestName() {
    return "lambdas_kstyle_captures";
  }
}
