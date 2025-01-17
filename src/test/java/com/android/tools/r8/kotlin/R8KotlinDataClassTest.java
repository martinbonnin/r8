// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import com.android.tools.r8.KotlinTestParameters;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.kotlin.TestKotlinClass.Visibility;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class R8KotlinDataClassTest extends AbstractR8KotlinTestBase {

  private static final TestKotlinDataClass TEST_DATA_CLASS =
      new TestKotlinDataClass("dataclass.Person")
      .addProperty("name", "java.lang.String", Visibility.PUBLIC)
      .addProperty("age", "int", Visibility.PUBLIC);

  private static final MethodSignature COMPONENT2_METHOD =
      TEST_DATA_CLASS.getComponentNFunctionForProperty("age");
  private static final MethodSignature COPY_DEFAULT_METHOD =
      TEST_DATA_CLASS.getCopyDefaultSignature();

  private static final Consumer<InternalOptions> disableClassInliner =
      o -> o.enableClassInlining = false;

  @Parameterized.Parameters(name = "{0}, {1}, allowAccessModification: {2}")
  public static Collection<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimesAndApiLevels().build(),
        getKotlinTestParameters().withAllCompilersLambdaGenerationsAndTargetVersions().build(),
        BooleanUtils.values());
  }

  public R8KotlinDataClassTest(
      TestParameters parameters,
      KotlinTestParameters kotlinParameters,
      boolean allowAccessModification) {
    super(parameters, kotlinParameters, allowAccessModification);
  }

  @Test
  public void testDataclassGettersOnly() throws Exception {
    String mainClassName = "dataclass.MainGettersOnlyKt";
    MethodSignature testMethodSignature =
        new MethodSignature("testDataClassGetters", "void", Collections.emptyList());
    runTest(
            "dataclass",
            mainClassName,
            testBuilder ->
                testBuilder
                    .addKeepRules(keepClassMethod(mainClassName, testMethodSignature))
                    .addOptionsModification(disableClassInliner))
        .inspect(inspector -> checkClassIsRemoved(inspector, TEST_DATA_CLASS.getClassName()));
  }

  @Test
  public void testDataclassComponentOnly() throws Exception {
    String mainClassName = "dataclass.MainComponentOnlyKt";
    MethodSignature testMethodSignature =
        new MethodSignature("testAllDataClassComponentFunctions", "void", Collections.emptyList());
    runTest(
            "dataclass",
            mainClassName,
            testBuilder ->
                testBuilder
                    .addKeepRules(keepClassMethod(mainClassName, testMethodSignature))
                    .addOptionsModification(disableClassInliner))
        .inspect(inspector -> checkClassIsRemoved(inspector, TEST_DATA_CLASS.getClassName()));
  }

  @Test
  public void testDataclassComponentPartial() throws Exception {
    String mainClassName = "dataclass.MainComponentPartialKt";
    MethodSignature testMethodSignature =
        new MethodSignature("testSomeDataClassComponentFunctions", "void", Collections.emptyList());
    runTest(
            "dataclass",
            mainClassName,
            testBuilder ->
                testBuilder
                    .addKeepRules(keepClassMethod(mainClassName, testMethodSignature))
                    .addOptionsModification(disableClassInliner))
        .inspect(
            inspector -> {
              checkClassIsRemoved(inspector, TEST_DATA_CLASS.getClassName());

              ClassSubject classSubject = checkClassIsKept(inspector, mainClassName);
              MethodSubject testMethod = checkMethodIsKept(classSubject, testMethodSignature);
              checkMethodIsNeverInvoked(testMethod, COMPONENT2_METHOD);
            });
  }

  @Test
  public void testDataclassCopyIsRemovedIfNotUsed() throws Exception {
    String mainClassName = "dataclass.MainComponentOnlyKt";
    MethodSignature testMethodSignature =
        new MethodSignature("testDataClassCopy", "void", Collections.emptyList());
    runTest(
            "dataclass",
            mainClassName,
            testBuilder ->
                testBuilder
                    .addKeepRules(keepClassMethod(mainClassName, testMethodSignature))
                    .addOptionsModification(disableClassInliner))
        .inspect(inspector -> checkClassIsRemoved(inspector, TEST_DATA_CLASS.getClassName()));
  }

  @Test
  public void testDataclassCopyDefaultIsRemovedIfNotUsed() throws Exception {
    String mainClassName = "dataclass.MainCopyKt";
    MethodSignature testMethodSignature =
        new MethodSignature("testDataClassCopyWithDefault", "void", Collections.emptyList());
    runTest(
            "dataclass",
            mainClassName,
            testBuilder ->
                testBuilder
                    .addKeepRules(keepClassMethod(mainClassName, testMethodSignature))
                    .addOptionsModification(disableClassInliner))
        .inspect(inspector -> checkClassIsRemoved(inspector, TEST_DATA_CLASS.getClassName()));
  }
}
