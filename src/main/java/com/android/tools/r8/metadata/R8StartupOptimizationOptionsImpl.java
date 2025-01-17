// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.metadata;

import com.android.tools.r8.dex.VirtualFile;
import com.android.tools.r8.keepanno.annotations.AnnotationPattern;
import com.android.tools.r8.keepanno.annotations.FieldAccessFlags;
import com.android.tools.r8.keepanno.annotations.KeepConstraint;
import com.android.tools.r8.keepanno.annotations.KeepItemKind;
import com.android.tools.r8.keepanno.annotations.UsedByReflection;
import com.android.tools.r8.utils.InternalOptions;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import java.util.List;

@UsedByReflection(
    description = "Keep and preserve @SerializedName for correct (de)serialization",
    constraints = {KeepConstraint.LOOKUP},
    constrainAnnotations = @AnnotationPattern(constant = SerializedName.class),
    kind = KeepItemKind.CLASS_AND_FIELDS,
    fieldAccess = {FieldAccessFlags.PRIVATE},
    fieldAnnotatedByClassConstant = SerializedName.class)
public class R8StartupOptimizationOptionsImpl implements R8StartupOptimizationOptions {

  @Expose
  @SerializedName("numberOfStartupDexFiles")
  private final int numberOfStartupDexFiles;

  public R8StartupOptimizationOptionsImpl(List<VirtualFile> virtualFiles) {
    this.numberOfStartupDexFiles =
        (int) virtualFiles.stream().filter(VirtualFile::isStartup).count();
  }

  public static R8StartupOptimizationOptionsImpl create(
      InternalOptions options, List<VirtualFile> virtualFiles) {
    if (options.getStartupOptions().getStartupProfileProviders().isEmpty()) {
      return null;
    }
    return new R8StartupOptimizationOptionsImpl(virtualFiles);
  }

  @Override
  public int getNumberOfStartupDexFiles() {
    return numberOfStartupDexFiles;
  }
}
