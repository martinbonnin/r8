// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.keepanno;

import static com.android.tools.r8.utils.codeinspector.Matchers.isAbsent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresentAndNotRenamed;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresentAndRenamed;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.keepanno.annotations.AnnotationPattern;
import com.android.tools.r8.keepanno.annotations.KeepItemKind;
import com.android.tools.r8.keepanno.annotations.KeepTarget;
import com.android.tools.r8.keepanno.annotations.UsedByReflection;
import com.android.tools.r8.keepanno.annotations.UsesReflection;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.google.common.collect.ImmutableList;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

@RunWith(Parameterized.class)
public class AnnotationPatternClassRetentionTest extends KeepAnnoTestBase {

  static final String EXPECTED = StringUtils.lines("C1:");

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
        .setExcludedOuterClass(getClass())
        .run(TestClass.class)
        .assertSuccessWithOutput(EXPECTED)
        .applyIf(parameters.isShrinker(), r -> r.inspect(this::checkOutput));
  }

  public List<Class<?>> getInputClasses() {
    return ImmutableList.of(TestClass.class, Reflector.class, A1.class, A2.class, C1.class);
  }

  private void checkOutput(CodeInspector inspector) {
    // Nothing is keeping A1 and no references exist to it.
    // Having the constraint on annotations with class retention should not prohibit removal.
    assertThat(inspector.clazz(A1.class), isAbsent());
    // The class retention annotation is used and kept. It can be renamed as nothing prohibits that.
    assertThat(inspector.clazz(A2.class), isPresentAndRenamed());
    // The class is retained by the keep-annotation.
    ClassSubject clazz = inspector.clazz(C1.class);
    assertThat(clazz, isPresentAndNotRenamed());
    assertThat(clazz.annotation(A1.class), isAbsent());
    assertThat(clazz.annotation(A2.class), isPresent());
  }

  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @interface A1 {}

  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.CLASS)
  @interface A2 {}

  static class Reflector {

    @UsesReflection({
      @KeepTarget(
          classAnnotatedByClassConstant = A2.class,
          constrainAnnotations = @AnnotationPattern(retention = RetentionPolicy.CLASS)),
    })
    public void foo(Class<?>... classes) throws Exception {
      for (Class<?> clazz : classes) {
        String typeName = clazz.getTypeName();
        System.out.print(typeName.substring(typeName.lastIndexOf('$') + 1) + ":");
        // Ignoring A1 as we explicitly have no keep-annotation to preserve it.
        // The below code will not trigger as A2 is not visible at runtime, but it will ensure the
        // annotation is used.
        if (clazz.isAnnotationPresent(A2.class)) {
          System.out.print(" A2");
        }
        System.out.println();
      }
    }
  }

  @A1
  @A2
  static class C1 {}

  static class TestClass {

    @UsedByReflection(kind = KeepItemKind.CLASS_AND_METHODS)
    public static void main(String[] args) throws Exception {
      new Reflector().foo(C1.class);
    }
  }
}
