// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.code.CfLogicalBinop;
import com.android.tools.r8.dex.code.DexInstruction;
import com.android.tools.r8.dex.code.DexUshrInt;
import com.android.tools.r8.dex.code.DexUshrInt2Addr;
import com.android.tools.r8.dex.code.DexUshrIntLit8;
import com.android.tools.r8.dex.code.DexUshrLong;
import com.android.tools.r8.dex.code.DexUshrLong2Addr;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.analysis.value.arithmetic.AbstractCalculator;

public class Ushr extends LogicalBinop {

  public Ushr(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  public int opcode() {
    return Opcodes.USHR;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  boolean fitsInDexInstruction(Value value) {
    // The ushr instruction only has the /lit8 variant.
    return fitsInLit8Instruction(value);
  }

  @Override
  public boolean isUshr() {
    return true;
  }

  @Override
  public Ushr asUshr() {
    return this;
  }

  @Override
  public boolean isCommutative() {
    return false;
  }

  @Override
  public DexInstruction CreateInt(int dest, int left, int right) {
    return new DexUshrInt(dest, left, right);
  }

  @Override
  public DexInstruction CreateLong(int dest, int left, int right) {
    return new DexUshrLong(dest, left, right);
  }

  @Override
  public DexInstruction CreateInt2Addr(int left, int right) {
    return new DexUshrInt2Addr(left, right);
  }

  @Override
  public DexInstruction CreateLong2Addr(int left, int right) {
    return new DexUshrLong2Addr(left, right);
  }

  @Override
  public DexInstruction CreateIntLit8(int dest, int left, int constant) {
    return new DexUshrIntLit8(dest, left, constant);
  }

  @Override
  public DexInstruction CreateIntLit16(int dest, int left, int constant) {
    throw new Unreachable("Unsupported instruction ShrIntLit16");
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isUshr() && other.asUshr().type == type;
  }

  @Override
  int foldIntegers(int left, int right) {
    return left >>> right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left >>> right;
  }

  @Override
  AbstractValue foldIntegers(AbstractValue left, AbstractValue right, AppView<?> appView) {
    return AbstractCalculator.ushrIntegers(appView, left, right);
  }

  @Override
  CfLogicalBinop.Opcode getCfOpcode() {
    return CfLogicalBinop.Opcode.Ushr;
  }
}
