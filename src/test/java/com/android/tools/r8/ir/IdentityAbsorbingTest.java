// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir;

import static org.junit.Assert.assertTrue;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class IdentityAbsorbingTest extends TestBase {

  private static final String EXPECTED_RESULT =
      StringUtils.lines(
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "2147483647",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "2147483646",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "-2147483648",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "-2147483647",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "9223372036854775807",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "9223372036854775806",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "-9223372036854775808",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "-9223372036854775807",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0",
          "0",
          "0",
          "0",
          "-1",
          "-1",
          "-1",
          "0",
          "0",
          "0");

  private final TestParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withCfRuntimes().withDexRuntimes().withAllApiLevels().build();
  }

  public IdentityAbsorbingTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testD8() throws Exception {
    testForRuntime(parameters)
        .addProgramClasses(Main.class)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addProgramClasses(Main.class)
        .addKeepMainRule(Main.class)
        .enableInliningAnnotations()
        .setMinApi(parameters)
        .compile()
        .inspect(this::inspect)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  private void inspect(CodeInspector inspector) {
    inspector
        .clazz(Main.class)
        .forAllMethods(
            m ->
                assertTrue(
                    m.streamInstructions()
                        .noneMatch(
                            i ->
                                i.isIntLogicalBinop()
                                    || i.isLongLogicalBinop()
                                    || i.isIntArithmeticBinop()
                                    || i.isLongArithmeticBinop())));
  }

  static class Main {

    public static void main(String[] args) {
      intTests(Integer.MAX_VALUE);
      intTests(Integer.MAX_VALUE - 1);
      intTests(Integer.MIN_VALUE);
      intTests(Integer.MIN_VALUE + 1);
      intTests(System.currentTimeMillis() > 0 ? 0 : 1);
      intTests(System.currentTimeMillis() > 0 ? 1 : 9);
      intTests(System.currentTimeMillis() > 0 ? -1 : 1);

      longTests(Long.MAX_VALUE);
      longTests(Long.MAX_VALUE - 1);
      longTests(Long.MIN_VALUE);
      longTests(Long.MIN_VALUE + 1);
      longTests(System.currentTimeMillis() > 0 ? 0L : 1L);
      longTests(System.currentTimeMillis() > 0 ? 1L : 9L);
      longTests(System.currentTimeMillis() > 0 ? -1L : 1L);
    }

    private static void longTests(long val) {
      identityLongTest(val);
      absorbingLongTest(val);
      identityDoubleLongTest(val);
      absorbingDoubleLongTest(val);
    }

    private static void intTests(int val) {
      identityIntTest(val);
      absorbingIntTest(val);
      identityDoubleIntTest(val);
      absorbingDoubleIntTest(val);
      chainIntTest(val);
    }

    @NeverInline
    private static void identityDoubleIntTest(int val) {
      System.out.println(val + 0 + 0);
      System.out.println(0 + val + 0);
      System.out.println(0 + 0 + val);
      System.out.println(val - 0 - 0);
      System.out.println(val * 1 * 1);
      System.out.println(1 * val * 1);
      System.out.println(1 * 1 * val);
      System.out.println(val / 1 / 1);

      System.out.println(val & -1 & -1);
      System.out.println(-1 & val & -1);
      System.out.println(-1 & -1 & val);
      System.out.println(val | 0 | 0);
      System.out.println(0 | val | 0);
      System.out.println(0 | 0 | val);
      System.out.println(val ^ 0 ^ 0);
      System.out.println(0 ^ val ^ 0);
      System.out.println(0 ^ 0 ^ val);
      System.out.println(val << 0 << 0);
      System.out.println(val >> 0 >> 0);
      System.out.println(val >>> 0 >>> 0);
    }

    @NeverInline
    private static void identityDoubleLongTest(long val) {
      System.out.println(val + 0L + 0L);
      System.out.println(0L + val + 0L);
      System.out.println(0L + 0L + val);
      System.out.println(val - 0L - 0L);
      System.out.println(val * 1L * 1L);
      System.out.println(1L * val * 1L);
      System.out.println(1L * 1L * val);
      System.out.println(val / 1L / 1L);

      System.out.println(val & -1L & -1L);
      System.out.println(-1L & val & -1L);
      System.out.println(-1L & -1L & val);
      System.out.println(val | 0L | 0L);
      System.out.println(0L | val | 0L);
      System.out.println(0L | 0L | val);
      System.out.println(val ^ 0L ^ 0L);
      System.out.println(0L ^ val ^ 0L);
      System.out.println(0L ^ 0L ^ val);
      System.out.println(val << 0L << 0L);
      System.out.println(val >> 0L >> 0L);
      System.out.println(val >>> 0L >>> 0L);
    }

    @NeverInline
    private static void identityIntTest(int val) {
      System.out.println(val + 0);
      System.out.println(0 + val);
      System.out.println(val - 0);
      System.out.println(val * 1);
      System.out.println(1 * val);
      System.out.println(val / 1);

      System.out.println(val & -1);
      System.out.println(-1 & val);
      System.out.println(val | 0);
      System.out.println(0 | val);
      System.out.println(val ^ 0);
      System.out.println(0 ^ val);
      System.out.println(val << 0);
      System.out.println(val >> 0);
      System.out.println(val >>> 0);
    }

    @NeverInline
    private static void identityLongTest(long val) {
      System.out.println(val + 0L);
      System.out.println(0L + val);
      System.out.println(val - 0L);
      System.out.println(val * 1L);
      System.out.println(1L * val);
      System.out.println(val / 1L);

      System.out.println(val & -1L);
      System.out.println(-1L & val);
      System.out.println(val | 0L);
      System.out.println(0L | val);
      System.out.println(val ^ 0L);
      System.out.println(0L ^ val);
      System.out.println(val << 0L);
      System.out.println(val >> 0L);
      System.out.println(val >>> 0L);
    }

    @NeverInline
    private static void absorbingDoubleIntTest(int val) {
      System.out.println(val * 0 * 0);
      System.out.println(0 * val * 0);
      System.out.println(0 * 0 * val);
      // val would need to be proven non zero.
      // System.out.println(0 / val);
      // System.out.println(0 % val);

      System.out.println(0 & 0 & val);
      System.out.println(0 & val & 0);
      System.out.println(val & 0 & 0);
      System.out.println(-1 | -1 | val);
      System.out.println(-1 | val | -1);
      System.out.println(val | -1 | -1);
      System.out.println(0 << 0 << val);
      System.out.println(0 >> 0 >> val);
      System.out.println(0 >>> 0 >>> val);
    }

    @NeverInline
    private static void absorbingDoubleLongTest(long val) {
      System.out.println(val * 0L * 0L);
      System.out.println(0L * val * 0L);
      System.out.println(0L * 0L * val);
      // val would need to be proven non zero.
      // System.out.println(0L / val);
      // System.out.println(0L % val);

      System.out.println(0L & 0L & val);
      System.out.println(0L & val & 0L);
      System.out.println(val & 0L & 0L);
      System.out.println(-1L | -1L | val);
      System.out.println(-1L | val | -1L);
      System.out.println(val | -1L | -1L);
      System.out.println(0L << 0L << val);
      System.out.println(0L >> 0L >> val);
      System.out.println(0L >>> 0L >>> val);
    }

    @NeverInline
    private static void absorbingIntTest(int val) {
      System.out.println(val * 0);
      System.out.println(0 * val);
      // val would need to be proven non zero.
      // System.out.println(0 / val);
      // System.out.println(0 % val);

      System.out.println(0 & val);
      System.out.println(val & 0);
      System.out.println(-1 | val);
      System.out.println(val | -1);
      System.out.println(0 << val);
      System.out.println(0 >> val);
      System.out.println(0 >>> val);
    }

    @NeverInline
    private static void absorbingLongTest(long val) {
      System.out.println(val * 0L);
      System.out.println(0L * val);
      // val would need to be proven non zero.
      // System.out.println(0L / val);
      // System.out.println(0L % val);

      System.out.println(0L & val);
      System.out.println(val & 0L);
      System.out.println(-1L | val);
      System.out.println(val | -1L);
      System.out.println(0L << val);
      System.out.println(0L >> val);
      System.out.println(0L >>> val);
    }

    private static void chainIntTest(int val) {
      int abs = System.currentTimeMillis() > 0 ? val * 0 : 0 * val;
      System.out.println(abs * val);
    }
  }
}
