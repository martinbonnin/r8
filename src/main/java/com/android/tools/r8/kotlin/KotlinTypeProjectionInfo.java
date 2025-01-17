// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.shaking.EnqueuerMetadataTraceable;
import com.android.tools.r8.utils.Reporter;
import java.util.function.Consumer;
import kotlin.metadata.KmTypeProjection;
import kotlin.metadata.KmVariance;

// Provides access to Kotlin information about the type projection of a type (arguments).
public class KotlinTypeProjectionInfo implements EnqueuerMetadataTraceable {

  final KmVariance variance;
  final KotlinTypeInfo typeInfo;

  private KotlinTypeProjectionInfo(KmVariance variance, KotlinTypeInfo typeInfo) {
    this.variance = variance;
    this.typeInfo = typeInfo;
  }

  static KotlinTypeProjectionInfo create(
      KmTypeProjection kmTypeProjection, DexItemFactory factory, Reporter reporter) {
    return new KotlinTypeProjectionInfo(
        kmTypeProjection.getVariance(),
        KotlinTypeInfo.create(kmTypeProjection.getType(), factory, reporter));
  }

  private boolean isStarProjection() {
    return variance == null && typeInfo == null;
  }

  boolean rewrite(Consumer<KmTypeProjection> consumer, AppView<?> appView) {
    if (isStarProjection()) {
      consumer.accept(KmTypeProjection.STAR);
      return false;
    } else {
      return typeInfo.rewrite(
          kmType -> consumer.accept(new KmTypeProjection(variance, kmType)), appView);
    }
  }

  @Override
  public void trace(DexDefinitionSupplier definitionSupplier) {
    if (typeInfo != null) {
      typeInfo.trace(definitionSupplier);
    }
  }
}
