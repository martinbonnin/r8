// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.type;

import static com.android.tools.r8.ir.analysis.type.TypeLatticeElement.DOUBLE;
import static com.android.tools.r8.ir.analysis.type.TypeLatticeElement.FLOAT;
import static com.android.tools.r8.ir.analysis.type.TypeLatticeElement.INT;
import static com.android.tools.r8.ir.analysis.type.TypeLatticeElement.LONG;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.ir.analysis.AnalysisTestBase;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.google.common.collect.Streams;
import java.util.function.Consumer;
import org.junit.Test;

public class ConstrainedPrimitiveTypeTest extends AnalysisTestBase {

  public ConstrainedPrimitiveTypeTest() throws Exception {
    super(TestClass.class);
  }

  @Test
  public void testIntWithInvokeUser() throws Exception {
    buildAndCheckIR("intWithInvokeUserTest", testInspector(INT, 1));
  }

  @Test
  public void testIntWithIndirectInvokeUser() throws Exception {
    buildAndCheckIR("intWithIndirectInvokeUserTest", testInspector(INT, 2));
  }

  @Test
  public void testFloatWithInvokeUser() throws Exception {
    buildAndCheckIR("floatWithInvokeUserTest", testInspector(FLOAT, 1));
  }

  @Test
  public void testFloatWithIndirectInvokeUser() throws Exception {
    buildAndCheckIR("floatWithIndirectInvokeUserTest", testInspector(FLOAT, 2));
  }

  @Test
  public void testLongWithInvokeUser() throws Exception {
    buildAndCheckIR("longWithInvokeUserTest", testInspector(LONG, 1));
  }

  @Test
  public void testLongWithIndirectInvokeUser() throws Exception {
    buildAndCheckIR("longWithIndirectInvokeUserTest", testInspector(LONG, 2));
  }

  @Test
  public void testDoubleWithInvokeUser() throws Exception {
    buildAndCheckIR("doubleWithInvokeUserTest", testInspector(DOUBLE, 1));
  }

  @Test
  public void testDoubleWithIndirectInvokeUser() throws Exception {
    buildAndCheckIR("doubleWithIndirectInvokeUserTest", testInspector(DOUBLE, 2));
  }

  private static Consumer<IRCode> testInspector(
      TypeLatticeElement expectedType, int expectedNumberOfConstNumberInstructions) {
    return code -> {
      Iterable<Instruction> instructions = code::instructionIterator;
      for (Instruction instruction : instructions) {
        if (instruction.isConstNumber()) {
          ConstNumber constNumberInstruction = instruction.asConstNumber();
          assertEquals(expectedType, constNumberInstruction.outValue().getTypeLattice());
        }
      }

      assertEquals(
          expectedNumberOfConstNumberInstructions,
          Streams.stream(code.instructionIterator()).filter(Instruction::isConstNumber).count());
    };
  }

  static class TestClass {

    public static void intWithInvokeUserTest() {
      int x = 1;
      Integer.toString(x);
    }

    public static void intWithIndirectInvokeUserTest(boolean unknown) {
      int x;
      if (unknown) {
        x = 1;
      } else {
        x = 2;
      }
      Integer.toString(x);
    }

    public static void floatWithInvokeUserTest() {
      float x = 1f;
      Float.toString(x);
    }

    public static void floatWithIndirectInvokeUserTest(boolean unknown) {
      float x;
      if (unknown) {
        x = 1f;
      } else {
        x = 2f;
      }
      Float.toString(x);
    }

    public static void longWithInvokeUserTest() {
      long x = 1L;
      Long.toString(x);
    }

    public static void longWithIndirectInvokeUserTest(boolean unknown) {
      long x;
      if (unknown) {
        x = 1L;
      } else {
        x = 2L;
      }
      Long.toString(x);
    }

    public static void doubleWithInvokeUserTest() {
      double x = 1.0;
      Double.toString(x);
    }

    public static void doubleWithIndirectInvokeUserTest(boolean unknown) {
      double x;
      if (unknown) {
        x = 1f;
      } else {
        x = 2f;
      }
      Double.toString(x);
    }
  }
}
