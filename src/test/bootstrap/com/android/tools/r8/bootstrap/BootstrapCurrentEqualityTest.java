// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.bootstrap;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.ExternalR8TestCompileResult;
import com.android.tools.r8.JdkClassFileProvider;
import com.android.tools.r8.R8TestBuilder;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.TestRuntime.CfVm;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.retrace.ProguardMapProducer;
import com.android.tools.r8.retrace.ProguardMappingSupplier;
import com.android.tools.r8.retrace.Retrace;
import com.android.tools.r8.retrace.RetraceCommand;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.Pair;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * This test relies on a freshly built build/libs/r8lib_with_deps.jar. If this test fails remove
 * build directory and rebuild r8lib_with_deps by calling test.py or gradle r8libWithdeps.
 */
@RunWith(Parameterized.class)
public class BootstrapCurrentEqualityTest extends TestBase {

  static class HelloWorldProgram {
    public static void main(String[] args) {
      System.out.println("Hello, world!");
    }
  }

  private static final List<Path> KEEP_RULES_FILES =
      ImmutableList.of(
          Paths.get(ToolHelper.getProjectRoot(), "src", "main", "keep.txt"),
          Paths.get(ToolHelper.getProjectRoot(), "src", "main", "discard.txt"));

  private static final Class<?> HELLO_CLASS = HelloWorldProgram.class;
  private static final String HELLO_NAME = typeName(HELLO_CLASS);
  private static final String[] KEEP_HELLO = {
    "-keep class " + HELLO_NAME + " {",
    "  public static void main(...);",
    "}",
    "-allowaccessmodification"
  };
  private static final String HELLO_EXPECTED = StringUtils.lines("Hello, world!");

  private static Pair<Path, Path> r8R8Debug;
  private static Pair<Path, Path> r8R8Release;

  private final TestParameters parameters;

  @ClassRule public static TemporaryFolder testFolder = new TemporaryFolder();

  @BeforeClass
  public static void beforeAll() throws Exception {
    if (data().stream().count() > 0) {
      r8R8Release = compileR8(CompilationMode.RELEASE);
      r8R8Debug = compileR8(CompilationMode.DEBUG);
    }
  }

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters()
        .withCfRuntimesStartingFromIncluding(CfVm.getMinimumSystemVersion())
        .build();
  }

  public BootstrapCurrentEqualityTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  private static Pair<Path, Path> compileR8(CompilationMode mode) throws Exception {
    // Run R8 on r8.jar.
    final Path jar = testFolder.newFolder().toPath().resolve("out.jar");
    final Path map = testFolder.newFolder().toPath().resolve("out.map");
    testForR8(newTempFolder(), Backend.CF)
        .setMode(mode)
        .addProgramFiles(ToolHelper.getR8WithRelocatedDeps())
        .addLibraryProvider(JdkClassFileProvider.fromSystemJdk())
        .addKeepRuleFiles(KEEP_RULES_FILES)
        .addOptionsModification(
            options -> options.getTestingOptions().dontReportFailingCheckDiscarded = mode.isDebug())
        .enableExperimentalKeepAnnotations()
        // TODO(b/176783536, b/270105162): Get a hold of dependencies in new gradle setup.
        .apply(R8TestBuilder::allowUnusedDontWarnPatterns)
        .compile()
        .apply(c -> FileUtils.writeTextFile(map, c.getProguardMap()))
        .writeToZip(jar);
    return new Pair<>(jar, map);
  }

  @Test
  public void testRetrace() throws IOException {
    ProcessResult processResult =
        ToolHelper.runProcess(
            new ProcessBuilder()
                .command(
                    parameters.getRuntime().asCf().getJavaExecutable().toString(),
                    "-DR8_THROW_EXCEPTION_FOR_TESTING_RETRACE=1",
                    "-cp",
                    r8R8Release.getFirst().toString(),
                    "com.android.tools.r8.R8",
                    "--help"));
    assertNotEquals(0, processResult.exitCode);
    assertThat(processResult.stderr, not(containsString("SelfRetraceTest")));

    List<String> expectedStackTrace =
        Lists.newArrayList(
            "Intentional exception for testing retrace.",
            "com.android.tools.r8.utils.SelfRetraceTest.foo3(SelfRetraceTest.java:13)",
            "com.android.tools.r8.utils.SelfRetraceTest.foo2(SelfRetraceTest.java:17)",
            "com.android.tools.r8.utils.SelfRetraceTest.foo1(SelfRetraceTest.java:21)",
            "com.android.tools.r8.utils.SelfRetraceTest.test(SelfRetraceTest.java:26)",
            "com.android.tools.r8.R8.run(R8.java:");

    RetraceCommand retraceCommand =
        RetraceCommand.builder()
            .setStackTrace(StringUtils.splitLines(processResult.stderr))
            .setMappingSupplier(
                ProguardMappingSupplier.builder()
                    .setProguardMapProducer(ProguardMapProducer.fromPath(r8R8Release.getSecond()))
                    .build())
            .setRetracedStackTraceConsumer(
                retraced -> {
                  int expectedIndex = -1;
                  for (String line : retraced) {
                    if (expectedIndex >= expectedStackTrace.size()) {
                      break;
                    } else if (expectedIndex == -1 && line.contains("java.lang.RuntimeException")) {
                      expectedIndex = 0;
                    }
                    if (expectedIndex > -1) {
                      assertThat(line, containsString(expectedStackTrace.get(expectedIndex++)));
                    }
                  }
                  assertEquals(expectedStackTrace.size(), expectedIndex);
                })
            .build();
    Retrace.run(retraceCommand);
  }

  @Test
  public void testR8LibCompatibility() throws Exception {
    // Produce r81 = R8Lib(R8WithDeps) and r82 = R8LibNoDeps + Deps(R8WithDeps) and test that r81 is
    // equal to r82. This test should only run if we are testing r8lib and we expect both R8libs to
    // be built by gradle. If we are not testing with R8Lib, do not run this test.
    assumeTrue(ToolHelper.isTestingR8Lib());
    Path runR81 =
        testForExternalR8(parameters.getBackend(), parameters.getRuntime())
            .useProvidedR8(ToolHelper.R8LIB_JAR)
            .addProgramFiles(ToolHelper.getR8WithRelocatedDeps())
            .addLibraryFiles(parameters.asCfRuntime().getJavaHome())
            .addKeepRuleFiles(KEEP_RULES_FILES)
            .setMode(CompilationMode.RELEASE)
            .compile()
            .outputJar();
    Path runR82 =
        testForExternalR8(parameters.getBackend(), parameters.getRuntime())
            .useProvidedR8(ToolHelper.R8LIB_EXCLUDE_DEPS_JAR)
            .addR8ExternalDepsToClasspath()
            .addProgramFiles(ToolHelper.getR8WithRelocatedDeps())
            .addLibraryFiles(parameters.asCfRuntime().getJavaHome())
            .addKeepRuleFiles(KEEP_RULES_FILES)
            .setMode(CompilationMode.RELEASE)
            .compile()
            .outputJar();
    assert uploadJarsToCloudStorageIfTestFails(TestBase::filesAreEqual, runR81, runR82);
  }

  @Test
  public void testSignatures() throws Exception {
    testParseSignaturesInJar(r8R8Release.getFirst());
  }

  @Test
  public void testJvm() throws Exception {
    Path program = writeClassesToJar(HELLO_CLASS);
    testForJvm(parameters)
        .addProgramFiles(program)
        .run(parameters.getRuntime(), HELLO_NAME)
        .assertSuccessWithOutput(HELLO_EXPECTED);
  }

  @Test
  public void testR8() throws Exception {
    Path program = writeClassesToJar(HELLO_CLASS);
    compareR8(program);
  }

  private void compareR8(Path program) throws Exception {
    ExternalR8TestCompileResult runR8Debug =
        testForExternalR8(newTempFolder(), parameters.getBackend(), parameters.getRuntime())
            .addProgramFiles(program)
            .addKeepRules(BootstrapCurrentEqualityTest.KEEP_HELLO)
            .setMode(CompilationMode.DEBUG)
            .compile();
    testForJvm(parameters)
        .addProgramFiles(runR8Debug.outputJar())
        .run(parameters.getRuntime(), BootstrapCurrentEqualityTest.HELLO_NAME)
        .assertSuccessWithOutput(HELLO_EXPECTED);
    ExternalR8TestCompileResult runR8Release =
        testForExternalR8(newTempFolder(), parameters.getBackend(), parameters.getRuntime())
            .addProgramFiles(program)
            .addKeepRules(BootstrapCurrentEqualityTest.KEEP_HELLO)
            .setMode(CompilationMode.RELEASE)
            .compile();
    testForJvm(parameters)
        .addProgramFiles(runR8Release.outputJar())
        .run(parameters.getRuntime(), BootstrapCurrentEqualityTest.HELLO_NAME)
        .assertSuccessWithOutput(HELLO_EXPECTED);
    RunR8AndCheck(r8R8Debug, program, runR8Debug, CompilationMode.DEBUG);
    RunR8AndCheck(r8R8Debug, program, runR8Release, CompilationMode.RELEASE);
    RunR8AndCheck(r8R8Release, program, runR8Debug, CompilationMode.DEBUG);
    RunR8AndCheck(r8R8Release, program, runR8Release, CompilationMode.RELEASE);
  }

  private void RunR8AndCheck(
      Pair<Path, Path> r8,
      Path program,
      ExternalR8TestCompileResult result,
      CompilationMode mode)
      throws Exception {
    ExternalR8TestCompileResult runR8R8 =
        testForExternalR8(newTempFolder(), parameters.getBackend(), parameters.getRuntime())
            .useProvidedR8(r8.getFirst())
            .addProgramFiles(program)
            .addKeepRules(BootstrapCurrentEqualityTest.KEEP_HELLO)
            .setMode(mode)
            .compile();
    // Check that the process outputs (exit code, stdout, stderr) are the same.
    assertEquals(result.getStdout(), runR8R8.getStdout());
    assertEquals(result.getStderr(), runR8R8.getStderr());
    // Check that the output jars are the same.
    uploadJarsToCloudStorageIfTestFails(
        TestBase::assertProgramsEqual, result.outputJar(), runR8R8.outputJar());
  }

  private static TemporaryFolder newTempFolder() throws IOException {
    TemporaryFolder tempFolder = new TemporaryFolder(testFolder.newFolder());
    tempFolder.create();
    return tempFolder;
  }
}
