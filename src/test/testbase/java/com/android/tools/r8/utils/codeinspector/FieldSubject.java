// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils.codeinspector;

import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.references.FieldReference;

public abstract class FieldSubject extends MemberSubject {

  public abstract boolean hasExplicitStaticValue();

  public abstract DexEncodedField getField();

  public DexField getDexField() {
    return getField().getReference();
  }

  public abstract TypeSubject getType();

  public abstract DexValue getStaticValue();

  @Override
  public abstract boolean isRenamed();

  public String getOriginalFieldName() {
    return getOriginalName();
  }

  public abstract String getOriginalSignatureAttribute();

  @Override
  public abstract String getFinalSignatureAttribute();

  @Override
  public FieldSubject asFieldSubject() {
    return this;
  }

  public FoundFieldSubject asFoundFieldSubject() {
    return null;
  }

  @Override
  public boolean isFieldSubject() {
    return true;
  }

  public abstract String getJvmFieldSignatureAsString();

  public abstract FieldReference getOriginalReference();

  public abstract FieldReference getFinalReference();
}
