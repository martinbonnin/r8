// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.cf.code;

import com.android.tools.r8.dex.code.CfOrDexStaticFieldRead;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.conversion.CfSourceCode;
import com.android.tools.r8.ir.conversion.CfState;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.optimize.interfaces.analysis.CfAnalysisConfig;
import com.android.tools.r8.optimize.interfaces.analysis.CfFrameState;
import java.util.ListIterator;
import org.objectweb.asm.Opcodes;

public class CfStaticFieldRead extends CfFieldInstruction implements CfOrDexStaticFieldRead {

  public CfStaticFieldRead(DexField field) {
    super(field);
  }

  public CfStaticFieldRead(DexField field, DexField declaringField) {
    super(field, declaringField);
  }

  @Override
  public CfFieldInstruction createWithField(DexField otherField) {
    return new CfStaticFieldRead(otherField);
  }

  @Override
  public int getOpcode() {
    return Opcodes.GETSTATIC;
  }

  @Override
  public boolean isFieldGet() {
    return true;
  }

  @Override
  public boolean isStaticFieldGet() {
    return true;
  }

  @Override
  void internalRegisterUse(
      UseRegistry<?> registry, DexClassAndMethod context, ListIterator<CfInstruction> iterator) {
    registry.registerStaticFieldReadInstruction(this);
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    builder.addStaticGet(state.push(getField().getType()).register, getField());
  }

  @Override
  public CfFrameState evaluate(CfFrameState frame, AppView<?> appView, CfAnalysisConfig config) {
    // ..., →
    // ..., value
    return frame.push(config, getField().getType());
  }
}
