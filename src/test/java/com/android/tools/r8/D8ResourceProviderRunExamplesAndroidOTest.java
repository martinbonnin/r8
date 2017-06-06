// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8;

import com.android.tools.r8.utils.DirectoryResourceProvider;
import com.android.tools.r8.utils.PreloadedResourceProvider;
import java.io.IOException;
import java.nio.file.Path;

public class D8ResourceProviderRunExamplesAndroidOTest
    extends D8IncrementalRunExamplesAndroidOTest {
  class D8LazyTestRunner extends D8IncrementalTestRunner {

    D8LazyTestRunner(String testName, String packageName, String mainClass) {
      super(testName, packageName, mainClass);
    }

    @Override
    void addClasspathReference(Path testJarFile, D8Command.Builder builder) {
      addClasspathPath(getClassesRoot(testJarFile), builder);
      addClasspathPath(getLegacyClassesRoot(testJarFile), builder);
    }

    private void addClasspathPath(Path location, D8Command.Builder builder) {
      builder.addClasspathResourceProvider(
          DirectoryResourceProvider.fromDirectory(
              Resource.Kind.CLASSPATH, location.resolve("..")));
    }

    @Override
    void addLibraryReference(D8Command.Builder builder, Path location) throws IOException {
      builder.addLibraryResourceProvider(
          PreloadedResourceProvider.fromArchive(Resource.Kind.LIBRARY, location));
    }
  }

  @Override
  D8IncrementalTestRunner test(String testName, String packageName, String mainClass) {
    return new D8LazyTestRunner(testName, packageName, mainClass);
  }
}
