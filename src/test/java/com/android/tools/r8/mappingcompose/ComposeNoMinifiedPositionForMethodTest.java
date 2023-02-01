// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.mappingcompose;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.ProguardMapReader.ParseException;
import com.android.tools.r8.utils.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/***
 * This is a regression test for b/267289876.
 */
@RunWith(Parameterized.class)
public class ComposeNoMinifiedPositionForMethodTest extends TestBase {

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withNoneRuntime().build();
  }

  public ComposeNoMinifiedPositionForMethodTest(TestParameters parameters) {
    parameters.assertNoneRuntime();
  }

  private static final String mappingFoo =
      StringUtils.unixLines(
          "# {'id':'com.android.tools.r8.mapping','version':'2.2'}",
          "com.foo -> a:",
          "    void m1():13:13 -> x",
          "    1:1:void bar():42:42 -> y",
          "    void bar():58 -> y");

  @Test
  public void testCompose() throws Exception {
    ParseException parseException =
        assertThrows(ParseException.class, () -> ClassNameMapper.mapperFromString(mappingFoo));
    assertThat(parseException.getMessage(), containsString("No mapping for original range 13:13."));
  }
}
