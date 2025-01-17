// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.code.CfLogicalBinop;
import com.android.tools.r8.dex.code.DexInstruction;
import com.android.tools.r8.dex.code.DexShlInt;
import com.android.tools.r8.dex.code.DexShlInt2Addr;
import com.android.tools.r8.dex.code.DexShlIntLit8;
import com.android.tools.r8.dex.code.DexShlLong;
import com.android.tools.r8.dex.code.DexShlLong2Addr;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.analysis.value.arithmetic.AbstractCalculator;

public class Shl extends LogicalBinop {

  public Shl(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  public int opcode() {
    return Opcodes.SHL;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  boolean fitsInDexInstruction(Value value) {
    // The shl instruction only has the /lit8 variant.
    return fitsInLit8Instruction(value);
  }

  @Override
  public boolean isCommutative() {
    return false;
  }

  @Override
  public boolean isShl() {
    return true;
  }

  @Override
  public Shl asShl() {
    return this;
  }

  @Override
  public Shl asShl(Value leftValue, Value rightValue) {
    return leftValue == leftValue() && rightValue == rightValue() ? this : null;
  }

  @Override
  public DexInstruction CreateInt(int dest, int left, int right) {
    return new DexShlInt(dest, left, right);
  }

  @Override
  public DexInstruction CreateLong(int dest, int left, int right) {
    return new DexShlLong(dest, left, right);
  }

  @Override
  public DexInstruction CreateInt2Addr(int left, int right) {
    return new DexShlInt2Addr(left, right);
  }

  @Override
  public DexInstruction CreateLong2Addr(int left, int right) {
    return new DexShlLong2Addr(left, right);
  }

  @Override
  public DexInstruction CreateIntLit8(int dest, int left, int constant) {
    return new DexShlIntLit8(dest, left, constant);
  }

  @Override
  public DexInstruction CreateIntLit16(int dest, int left, int constant) {
    throw new Unreachable("Unsupported instruction ShlIntLit16");
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isShl() && other.asShl().type == type;
  }

  @Override
  int foldIntegers(int left, int right) {
    return left << right;
  }

  @Override
  AbstractValue foldIntegers(AbstractValue left, AbstractValue right, AppView<?> appView) {
    return AbstractCalculator.shlIntegers(appView, left, right);
  }

  @Override
  long foldLongs(long left, long right) {
    return left << right;
  }

  @Override
  CfLogicalBinop.Opcode getCfOpcode() {
    return CfLogicalBinop.Opcode.Shl;
  }
}
