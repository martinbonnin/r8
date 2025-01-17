// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.compilerapi.sampleapi;

import com.android.tools.r8.ArchiveClassFileProvider;
import com.android.tools.r8.ArchiveProgramResourceProvider;
import com.android.tools.r8.AssertionsConfiguration;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DesugarGraphConsumer;
import com.android.tools.r8.DexFilePerClassFileConsumer;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.ProgramResource;
import com.android.tools.r8.ProgramResource.Kind;
import com.android.tools.r8.ProgramResourceProvider;
import com.android.tools.r8.ResourceException;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.compilerapi.CompilerApiTest;
import com.android.tools.r8.compilerapi.CompilerApiTestRunner;
import com.android.tools.r8.origin.ArchiveEntryOrigin;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8.utils.StringDiagnostic;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * This API test is a copy over from the old API sample test set up.
 *
 * <p>NOTE: Don't use this test as the basis for new API tests. Instead, use one of the simpler and
 * more feature directed tests found in the sibling packages.
 */
public class D8ApiUsageSampleTest extends CompilerApiTestRunner {

  public D8ApiUsageSampleTest(TestParameters parameters) {
    super(parameters);
  }

  @Override
  public Class<? extends CompilerApiTest> binaryTestClass() {
    return ApiTest.class;
  }

  @Test
  public void test() throws IOException {
    ApiTest test = new ApiTest(ApiTest.PARAMETERS);
    test.run(temp);
  }

  public static class ApiTest extends CompilerApiTest {

    public ApiTest(Object parameters) {
      super(parameters);
    }

    private static final Origin origin =
        new Origin(Origin.root()) {
          @Override
          public String part() {
            return "D8ApiUsageSample";
          }
        };

    private static final DiagnosticsHandler handler = new DiagnosticsHandler() {};

    @Test
    public void test() throws IOException {
      run(temp);
    }

    public void run(TemporaryFolder temp) throws IOException {
      runFromArgs(
          new String[] {
            "--output",
            temp.newFolder().getAbsolutePath(),
            "--min-api",
            "19",
            "--lib",
            getAndroidJar().toString(),
            "--classpath",
            getAndroidJar().toString(),
            getPathForClass(getMockClass()).toString()
          });
    }

    public void runFromArgs(String[] args) {
      // Parse arguments with the commandline parser to make use of its API.
      D8Command.Builder cmd = D8Command.parse(args, origin);
      CompilationMode mode = cmd.getMode();
      Path temp = cmd.getOutputPath();
      int minApiLevel = cmd.getMinApiLevel();
      // The Builder API does not provide access to the concrete paths
      // (everything is put into providers) so manually parse them here.
      List<Path> libraries = new ArrayList<>(1);
      List<Path> classpath = new ArrayList<>(args.length);
      List<Path> inputs = new ArrayList<>(args.length);
      for (int i = 0; i < args.length; i++) {
        if (args[i].equals("--lib")) {
          libraries.add(Paths.get(args[++i]));
        } else if (args[i].equals("--classpath")) {
          classpath.add(Paths.get(args[++i]));
        } else if (isArchive(args[i]) || isClassFile(args[i])) {
          inputs.add(Paths.get(args[i]));
        }
      }
      if (!Files.exists(temp) || !Files.isDirectory(temp)) {
        throw new RuntimeException("Must supply a temp/output directory");
      }
      if (inputs.isEmpty()) {
        throw new RuntimeException("Must supply program inputs");
      }
      if (libraries.isEmpty()) {
        throw new RuntimeException("Must supply library inputs");
      }
      useProgramFileList(CompilationMode.DEBUG, minApiLevel, libraries, classpath, inputs);
      useProgramFileList(CompilationMode.RELEASE, minApiLevel, libraries, classpath, inputs);
      useProgramData(minApiLevel, libraries, classpath, inputs);
      useProgramResourceProvider(minApiLevel, libraries, classpath, inputs);
      useLibraryAndClasspathProvider(minApiLevel, libraries, classpath, inputs);
      useAssertionConfig(minApiLevel, libraries, classpath, inputs);
      useVArgVariants(minApiLevel, libraries, classpath, inputs);
      incrementalCompileAndMerge(minApiLevel, libraries, classpath, inputs);
    }

    // Check API support for compiling Java class-files from the file system.
    private static void useProgramFileList(
        CompilationMode mode,
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      try {
        D8.run(
            D8Command.builder(handler)
                .setMode(mode)
                .setMinApiLevel(minApiLevel)
                .setProgramConsumer(new EnsureOutputConsumer())
                .addLibraryFiles(libraries)
                .addClasspathFiles(classpath)
                .addProgramFiles(inputs)
                .build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      }
    }

    // Check API support for compiling Java class-files from byte content.
    private static void useProgramData(
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      try {
        D8Command.Builder builder =
            D8Command.builder(handler)
                .setMinApiLevel(minApiLevel)
                .setProgramConsumer(new EnsureOutputConsumer())
                .addLibraryFiles(libraries)
                .addClasspathFiles(classpath);
        for (ClassFileContent classfile : readClassFiles(inputs)) {
          builder.addClassProgramData(classfile.data, classfile.origin);
        }
        for (Path input : inputs) {
          if (isDexFile(input)) {
            builder.addDexProgramData(Files.readAllBytes(input), new PathOrigin(input));
          }
        }
        D8.run(builder.build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      } catch (IOException e) {
        throw new RuntimeException("Unexpected IO exception", e);
      }
    }

    // Check API support for compiling Java class-files from a program provider abstraction.
    private static void useProgramResourceProvider(
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      try {
        D8Command.Builder builder =
            D8Command.builder(handler)
                .setMinApiLevel(minApiLevel)
                .setProgramConsumer(new EnsureOutputConsumer())
                .addLibraryFiles(libraries)
                .addClasspathFiles(classpath);
        for (Path input : inputs) {
          if (isArchive(input)) {
            builder.addProgramResourceProvider(
                ArchiveProgramResourceProvider.fromArchive(
                    input, ArchiveProgramResourceProvider::includeClassFileEntries));
          } else if (isClassFile(input)) {
            builder.addProgramResourceProvider(
                new ProgramResourceProvider() {
                  @Override
                  public Collection<ProgramResource> getProgramResources()
                      throws ResourceException {
                    return Collections.singleton(ProgramResource.fromFile(Kind.CF, input));
                  }
                });
          } else if (isDexFile(input)) {
            builder.addProgramResourceProvider(
                new ProgramResourceProvider() {
                  @Override
                  public Collection<ProgramResource> getProgramResources()
                      throws ResourceException {
                    return Collections.singleton(ProgramResource.fromFile(Kind.DEX, input));
                  }
                });
          }
        }
        D8.run(builder.build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      }
    }

    private static void useLibraryAndClasspathProvider(
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      try {
        D8Command.Builder builder =
            D8Command.builder(handler)
                .setMinApiLevel(minApiLevel)
                .setProgramConsumer(new EnsureOutputConsumer())
                .addProgramFiles(inputs);
        for (Path library : libraries) {
          builder.addLibraryResourceProvider(new ArchiveClassFileProvider(library));
        }
        for (Path path : classpath) {
          builder.addClasspathResourceProvider(new ArchiveClassFileProvider(path));
        }
        D8.run(builder.build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      } catch (IOException e) {
        throw new RuntimeException("Unexpected IO exception", e);
      }
    }

    private static void useAssertionConfig(
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      String pkg = "com.android.tools.r8.compilerapi.sampleapi";
      try {
        D8.run(
            D8Command.builder(handler)
                .setMinApiLevel(minApiLevel)
                .setProgramConsumer(new EnsureOutputConsumer())
                .addLibraryFiles(libraries)
                .addClasspathFiles(classpath)
                .addProgramFiles(inputs)
                .addAssertionsConfiguration(b -> b.setScopeAll().setCompileTimeEnable().build())
                .addAssertionsConfiguration(b -> b.setScopeAll().setCompileTimeDisable().build())
                .addAssertionsConfiguration(
                    b -> b.setScopePackage(pkg).setCompileTimeEnable().build())
                .addAssertionsConfiguration(b -> b.setScopePackage(pkg).setPassthrough().build())
                .addAssertionsConfiguration(
                    b -> b.setScopePackage(pkg).setCompileTimeDisable().build())
                .addAssertionsConfiguration(
                    b ->
                        b.setScopeClass(pkg + ".D8ApiUsageSampleTest")
                            .setCompileTimeEnable()
                            .build())
                .addAssertionsConfiguration(
                    b -> b.setScopeClass(pkg + ".D8ApiUsageSampleTest").setPassthrough().build())
                .addAssertionsConfiguration(
                    b ->
                        b.setScopeClass(pkg + ".D8ApiUsageSampleTest")
                            .setCompileTimeDisable()
                            .build())
                .addAssertionsConfiguration(
                    AssertionsConfiguration.Builder::compileTimeEnableAllAssertions)
                .addAssertionsConfiguration(
                    AssertionsConfiguration.Builder::passthroughAllAssertions)
                .addAssertionsConfiguration(
                    AssertionsConfiguration.Builder::compileTimeDisableAllAssertions)
                .build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      }
    }

    // Check API support for all the varg variants.
    private static void useVArgVariants(
        int minApiLevel, List<Path> libraries, List<Path> classpath, List<Path> inputs) {
      try {
        D8Command.Builder builder =
            D8Command.builder(handler)
                .setMinApiLevel(minApiLevel)
                .setProgramConsumer(new EnsureOutputConsumer())
                .addLibraryFiles(libraries.get(0))
                .addLibraryFiles(libraries.stream().skip(1).toArray(Path[]::new))
                .addProgramFiles(inputs.get(0))
                .addProgramFiles(inputs.stream().skip(1).toArray(Path[]::new));
        if (!classpath.isEmpty()) {
          builder
              .addClasspathFiles(classpath.get(0))
              .addClasspathFiles(classpath.stream().skip(1).toArray(Path[]::new));
        }
        D8.run(builder.build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      }
    }

    private static void incrementalCompileAndMerge(
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      // Compile and merge via index intermediates.
      mergeIntermediates(
          minApiLevel, compileToIndexedIntermediates(minApiLevel, libraries, classpath, inputs));
      // Compile and merge via per-classfile intermediates.
      mergeIntermediates(
          minApiLevel,
          compileToPerClassFileIntermediates(minApiLevel, libraries, classpath, inputs));
    }

    private static Collection<byte[]> compileToIndexedIntermediates(
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      IndexIntermediatesConsumer consumer = new IndexIntermediatesConsumer();
      try {
        D8.run(
            D8Command.builder(handler)
                .setMinApiLevel(minApiLevel)
                .setIntermediate(true)
                .setProgramConsumer(consumer)
                .addClasspathFiles(classpath)
                .addLibraryFiles(libraries)
                .addProgramFiles(inputs)
                .setDisableDesugaring(false)
                .setDesugarGraphConsumer(new MyDesugarGraphConsumer())
                .build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      }
      return consumer.bytes;
    }

    private static Collection<byte[]> compileToPerClassFileIntermediates(
        int minApiLevel,
        Collection<Path> libraries,
        Collection<Path> classpath,
        Collection<Path> inputs) {
      PerClassIntermediatesConsumer consumer = new PerClassIntermediatesConsumer();
      try {
        D8.run(
            D8Command.builder(handler)
                .setMinApiLevel(minApiLevel)
                .setProgramConsumer(consumer)
                .addLibraryFiles(libraries)
                .addClasspathFiles(classpath)
                .addProgramFiles(inputs)
                .build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected compilation exceptions", e);
      }
      return consumer.bytes;
    }

    private static void mergeIntermediates(int minApiLevel, Collection<byte[]> intermediates) {
      D8Command.Builder builder =
          D8Command.builder(handler)
              .setMinApiLevel(minApiLevel)
              .setProgramConsumer(new EnsureOutputConsumer())
              .setDisableDesugaring(true);
      for (byte[] intermediate : intermediates) {
        builder.addDexProgramData(intermediate, Origin.unknown());
      }
      try {
        D8.run(builder.build());
      } catch (CompilationFailedException e) {
        throw new RuntimeException("Unexpected merging error", e);
      }
    }

    // Helpers for tests.
    // Some of this reimplements stuff in R8 utils, but that is not public API and we should not
    // rely on it.

    private static List<ClassFileContent> readClassFiles(Collection<Path> files)
        throws IOException {
      List<ClassFileContent> classfiles = new ArrayList<>();
      for (Path file : files) {
        if (isArchive(file)) {
          Origin zipOrigin = new PathOrigin(file);
          ZipInputStream zip =
              new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8);
          ZipEntry entry;
          while (null != (entry = zip.getNextEntry())) {
            String name = entry.getName();
            if (isClassFile(name)) {
              Origin origin = new ArchiveEntryOrigin(name, zipOrigin);
              classfiles.add(new ClassFileContent(origin, readBytes(zip)));
            }
          }
        } else if (isClassFile(file)) {
          classfiles.add(new ClassFileContent(new PathOrigin(file), Files.readAllBytes(file)));
        }
      }
      return classfiles;
    }

    private static byte[] readBytes(InputStream stream) throws IOException {
      try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[0xffff];
        for (int length; (length = stream.read(buffer)) != -1; ) {
          bytes.write(buffer, 0, length);
        }
        return bytes.toByteArray();
      }
    }

    private static String toLowerCase(String str) {
      return str.toLowerCase(Locale.ROOT);
    }

    private static boolean isClassFile(Path file) {
      return isClassFile(file.toString());
    }

    private static boolean isClassFile(String file) {
      file = toLowerCase(file);
      return file.endsWith(".class");
    }

    private static boolean isDexFile(Path file) {
      return isDexFile(file.toString());
    }

    private static boolean isDexFile(String file) {
      file = toLowerCase(file);
      return file.endsWith(".dex");
    }

    private static boolean isArchive(Path file) {
      return isArchive(file.toString());
    }

    private static boolean isArchive(String file) {
      file = toLowerCase(file);
      return file.endsWith(".zip") || file.endsWith(".jar");
    }

    private static class ClassFileContent {
      final Origin origin;
      final byte[] data;

      public ClassFileContent(Origin origin, byte[] data) {
        this.origin = origin;
        this.data = data;
      }
    }

    private static class IndexIntermediatesConsumer implements DexIndexedConsumer {

      List<byte[]> bytes = new ArrayList<>();

      @Override
      public synchronized void accept(
          int fileIndex, byte[] data, Set<String> descriptors, DiagnosticsHandler handler) {
        bytes.add(data);
      }

      @Override
      public void finished(DiagnosticsHandler handler) {}
    }

    private static class PerClassIntermediatesConsumer implements DexFilePerClassFileConsumer {

      List<byte[]> bytes = new ArrayList<>();

      @Override
      public synchronized void accept(
          String primaryClassDescriptor,
          byte[] data,
          Set<String> descriptors,
          DiagnosticsHandler handler) {
        bytes.add(data);
      }

      @Override
      public void finished(DiagnosticsHandler handler) {}
    }

    private static class EnsureOutputConsumer implements DexIndexedConsumer {
      boolean hasOutput = false;

      @Override
      public synchronized void accept(
          int fileIndex, byte[] data, Set<String> descriptors, DiagnosticsHandler handler) {
        hasOutput = true;
      }

      @Override
      public void finished(DiagnosticsHandler handler) {
        if (!hasOutput) {
          handler.error(new StringDiagnostic("Expected to produce output but had none"));
        }
      }
    }

    private static class MyDesugarGraphConsumer implements DesugarGraphConsumer {

      @Override
      public void accept(Origin dependent, Origin dependency) {}

      public void finished() {}
    }
  }
}
