// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.errors.DexOverflowException;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationDirectory;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexAnnotationSetRefList;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexDebugInfo;
import com.android.tools.r8.graph.DexEncodedArray;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.naming.MinifiedNameMapPrinter;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.OutputMode;
import com.android.tools.r8.utils.ThreadUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class ApplicationWriter {

  public final DexApplication application;
  public final AppInfo appInfo;
  public final byte[] deadCode;
  public final NamingLens namingLens;
  public final byte[] proguardSeedsData;
  public final InternalOptions options;
  public DexString markerString;

  private static class SortAnnotations extends MixedSectionCollection {

    @Override
    public boolean add(DexAnnotationSet dexAnnotationSet) {
      // Annotation sets are sorted by annotation types.
      dexAnnotationSet.sort();
      return true;
    }

    @Override
    public boolean add(DexAnnotation annotation) {
      // The elements of encoded annotation must be sorted by name.
      annotation.annotation.sort();
      return true;
    }

    @Override
    public boolean add(DexEncodedArray dexEncodedArray) {
      // Dex values must potentially be sorted, eg, for DexValueAnnotation.
      for (DexValue value : dexEncodedArray.values) {
        value.sort();
      }
      return true;
    }

    @Override
    public boolean add(DexProgramClass dexClassData) {
      return true;
    }

    @Override
    public boolean add(DexCode dexCode) {
      return true;
    }

    @Override
    public boolean add(DexDebugInfo dexDebugInfo) {
      return true;
    }

    @Override
    public boolean add(DexTypeList dexTypeList) {
      return true;
    }

    @Override
    public boolean add(DexAnnotationSetRefList annotationSetRefList) {
      return true;
    }

    @Override
    public boolean setAnnotationsDirectoryForClass(DexProgramClass clazz,
        DexAnnotationDirectory annotationDirectory) {
      return true;
    }
  }

  public ApplicationWriter(
      DexApplication application,
      AppInfo appInfo,
      InternalOptions options,
      Marker marker,
      byte[] deadCode,
      NamingLens namingLens,
      byte[] proguardSeedsData) {
    assert application != null;
    this.application = application;
    this.appInfo = appInfo;
    assert options != null;
    this.options = options;
    this.markerString = (marker == null)
        ? null
        : application.dexItemFactory.createString(marker.toString());
    this.deadCode = deadCode;
    this.namingLens = namingLens;
    this.proguardSeedsData = proguardSeedsData;
  }

  public AndroidApp write(ExecutorService executorService)
      throws IOException, ExecutionException, DexOverflowException {
    application.timing.begin("DexApplication.write");
    try {
      application.dexItemFactory.sort(namingLens);
      assert this.markerString == null || application.dexItemFactory.extractMarker() != null;

      SortAnnotations sortAnnotations = new SortAnnotations();
      application.classes().forEach((clazz) -> clazz.addDependencies(sortAnnotations));

      // Distribute classes into dex files.
      VirtualFile.Distributor distributor = null;
      if (options.outputMode == OutputMode.FilePerInputClass) {
        distributor = new VirtualFile.FilePerInputClassDistributor(this);
      } else if (!options.canUseMultidex()
          && options.mainDexKeepRules.isEmpty()
          && application.mainDexList.isEmpty()) {
        distributor = new VirtualFile.MonoDexDistributor(this);
      } else {
        distributor = new VirtualFile.FillFilesDistributor(this, options.minimalMainDex);
      }
      Map<Integer, VirtualFile> newFiles = distributor.run();

      // Collect the indexed items sets for all files and perform JumboString processing.
      // This is required to ensure that shared code blocks have a single and consistent code
      // item that is valid for all dex files.
      // Use a linked hash map as the order matters when addDexProgramData is called below.
      Map<VirtualFile, Future<ObjectToOffsetMapping>> offsetMappingFutures = new LinkedHashMap<>();
      for (int i = 0; i < newFiles.size(); i++) {
        VirtualFile newFile = newFiles.get(i);
        assert newFile.getId() == i;
        assert !newFile.isEmpty();
        if (!newFile.isEmpty()) {
          offsetMappingFutures
              .put(newFile, executorService.submit(() -> {
                ObjectToOffsetMapping mapping = newFile.computeMapping(application);
                rewriteCodeWithJumboStrings(mapping, newFile.classes(), application);
                return mapping;
              }));
        }
      }

      // Wait for all spawned futures to terminate to ensure jumbo string writing is complete.
      // TODO(66327890): Implement a test for this.
      ThreadUtils.awaitFutures(offsetMappingFutures.values());

      // Generate the dex file contents.
      LinkedHashMap<VirtualFile, Future<byte[]>> dexDataFutures = new LinkedHashMap<>();
      for (VirtualFile newFile : offsetMappingFutures.keySet()) {
        assert !newFile.isEmpty();
        dexDataFutures.put(newFile,
            executorService.submit(() -> writeDexFile(offsetMappingFutures.get(newFile).get())));
      }

      // Wait for all the spawned futures to terminate.
      AndroidApp.Builder builder = AndroidApp.builder();
      try {
        for (Map.Entry<VirtualFile, Future<byte[]>> entry : dexDataFutures.entrySet()) {
          VirtualFile virtualFile = entry.getKey();
          if (virtualFile.getPrimaryClassDescriptor() != null) {
            builder.addDexProgramData(
                entry.getValue().get(),
                virtualFile.getClassDescriptors(),
                virtualFile.getPrimaryClassDescriptor());
          } else {
            builder.addDexProgramData(entry.getValue().get(), virtualFile.getClassDescriptors());
          }
        }
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting for future.", e);
      }
      if (deadCode != null) {
        builder.setDeadCode(deadCode);
      }
      // Write the proguard map file after writing the dex files, as the map writer traverses
      // the DexProgramClass structures, which are destructively updated during dex file writing.
      byte[] proguardMapResult = writeProguardMapFile();
      if (proguardMapResult != null) {
        builder.setProguardMapData(proguardMapResult);
      }
      if (proguardSeedsData != null) {
        builder.setProguardSeedsData(proguardSeedsData);
      }
      byte[] mainDexList = writeMainDexList();
      if (mainDexList != null) {
        builder.setMainDexListOutputData(mainDexList);
      }
      return builder.build();
    } finally {
      application.timing.end();
    }
  }


  /**
   * Rewrites the code for all methods in the given file so that they use JumboString for at
   * least the strings that require it in mapping.
   * <p>
   * If run multiple times on a class, the lowest index that is required to be a JumboString will
   * be used.
   */
  private static void rewriteCodeWithJumboStrings(ObjectToOffsetMapping mapping,
      List<DexProgramClass> classes, DexApplication application) {
    // If there are no strings with jumbo indices at all this is a no-op.
    if (!mapping.hasJumboStrings()) {
      return;
    }
    // If the globally highest sorting string is not a jumbo string this is also a no-op.
    if (application.highestSortingString != null &&
        application.highestSortingString.slowCompareTo(mapping.getFirstJumboString()) < 0) {
      return;
    }
    // At least one method needs a jumbo string.
    for (DexProgramClass clazz : classes) {
      clazz.forEachMethod(method -> method.rewriteCodeWithJumboStrings(mapping, application));
    }
  }

  private byte[] writeDexFile(ObjectToOffsetMapping mapping)
      throws ApiLevelException {
    FileWriter fileWriter = new FileWriter(mapping, application, appInfo, options, namingLens);
    // Collect the non-fixed sections.
    fileWriter.collect();
    // Generate and write the bytes.
    return fileWriter.generate();
  }

  private byte[] writeProguardMapFile() throws IOException {
    // TODO(herhut): Should writing of the proguard-map file be split like this?
    if (!namingLens.isIdentityLens()) {
      MinifiedNameMapPrinter printer = new MinifiedNameMapPrinter(application, namingLens);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      PrintStream stream = new PrintStream(bytes);
      printer.write(stream);
      stream.flush();
      return bytes.toByteArray();
    } else if (application.getProguardMap() != null) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      Writer writer = new PrintWriter(bytes);
      application.getProguardMap().write(writer, !options.skipDebugLineNumberOpt);
      writer.flush();
      return bytes.toByteArray();
    }
    return null;
  }

  private String mapMainDexListName(DexType type) {
    return DescriptorUtils.descriptorToJavaType(namingLens.lookupDescriptor(type).toString())
        .replace('.', '/') + ".class";
  }

  private byte[] writeMainDexList() {
    if (application.mainDexList.isEmpty()) {
      return null;
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintWriter writer = new PrintWriter(bytes);
    application.mainDexList.forEach(
        type -> writer.println(mapMainDexListName(type))
    );
    writer.flush();
    return bytes.toByteArray();
  }
}
