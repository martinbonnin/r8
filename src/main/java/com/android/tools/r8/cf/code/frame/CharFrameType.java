// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.cf.code.frame;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.lens.GraphLens;
import com.android.tools.r8.naming.NamingLens;

public class CharFrameType extends SinglePrimitiveFrameType {

  static final CharFrameType SINGLETON = new CharFrameType();

  private CharFrameType() {}

  @Override
  public DexType getInitializedType(DexItemFactory dexItemFactory) {
    return dexItemFactory.charType;
  }

  @Override
  public String getTypeName() {
    return "char";
  }

  @Override
  public Object getTypeOpcode(GraphLens graphLens, GraphLens codeLens, NamingLens namingLens) {
    throw new Unreachable("Unexpected value type: " + this);
  }

  @Override
  public boolean hasIntVerificationType() {
    return true;
  }

  @Override
  public boolean isChar() {
    return true;
  }
}
