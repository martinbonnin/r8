// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `kotlin-dsl`
  `java-library`
  id("dependencies-plugin")
}

val root = getRoot()

java {
  sourceSets.test.configure {
    java {
      srcDir(root.resolveAll("src", "test", "java"))
      // Generated art tests
      srcDir(root.resolveAll("build", "generated", "test", "java"))
    }
  }
  // We are using a new JDK to compile to an older language version, which is not directly
  // compatible with java toolchains.
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
  toolchain {
    languageVersion = JavaLanguageVersion.of(JvmCompatibility.release)
  }
}


val testbaseJavaCompileTask = projectTask("testbase", "compileJava")
val testbaseDepsJarTask = projectTask("testbase", "depsJar")

// If we depend on keepanno by referencing the project source outputs we get an error regarding
// incompatible java class file version. By depending on the jar we circumvent that.
val keepAnnoJarTask = projectTask("keepanno", "jar")
val keepAnnoCompileTask = projectTask("keepanno", "compileJava")
val mainCompileTask = projectTask("main", "compileJava")
val mainDepsJarTask = projectTask("main", "depsJar")
val resourceShrinkerJavaCompileTask = projectTask("resourceshrinker", "compileJava")
val resourceShrinkerKotlinCompileTask = projectTask("resourceshrinker", "compileKotlin")
val resourceShrinkerDepsJarTask = projectTask("resourceshrinker", "depsJar")

dependencies {
  implementation(keepAnnoJarTask.outputs.files)
  implementation(mainCompileTask.outputs.files)
  implementation(projectTask("main", "processResources").outputs.files)
  implementation(resourceShrinkerJavaCompileTask.outputs.files)
  implementation(resourceShrinkerKotlinCompileTask.outputs.files)
  implementation(resourceShrinkerDepsJarTask.outputs.files)
  implementation(testbaseDepsJarTask.outputs.files)
  implementation(testbaseJavaCompileTask.outputs.files)
}

val sourceSetDependenciesTasks = arrayOf(
  projectTask("tests_java_9", getExampleJarsTaskName("examplesJava9")),
  projectTask("tests_java_10", getExampleJarsTaskName("examplesJava10")),
  projectTask("tests_java_11", getExampleJarsTaskName("examplesJava11")),
  projectTask("tests_java_17", getExampleJarsTaskName("examplesJava17")),
  projectTask("tests_java_21", getExampleJarsTaskName("examplesJava21")),
)

fun testDependencies() : FileCollection {
  return sourceSets
    .test
    .get()
    .compileClasspath
    .filter {
        "$it".contains("third_party")
          && !"$it".contains("errorprone")
          && !"$it".contains("third_party/gradle")
    }
}

tasks {

  getByName<Delete>("clean") {
    // TODO(b/327315907): Don't generating into the root build dir.
    delete.add(getRoot().resolveAll("build", "generated", "test", "java", "com", "android", "tools", "r8", "art"))
  }

  val createArtTests by registering(Exec::class) {
    dependsOn(gradle.includedBuild("shared").task(":downloadDeps"))
    // TODO(b/327315907): Don't generating into the root build dir.
    val outputDir = getRoot().resolveAll("build", "generated", "test", "java", "com", "android", "tools", "r8", "art")
    val createArtTestsScript = getRoot().resolveAll("tools", "create_art_tests.py")
    inputs.file(createArtTestsScript)
    inputs.dir(getRoot().resolveAll("tests", "2017-10-04"))
    outputs.dir(outputDir)
    workingDir(getRoot())
    commandLine("python3", createArtTestsScript)
  }
  "compileTestJava" {
    dependsOn(testbaseJavaCompileTask)
    dependsOn(gradle.includedBuild("shared").task(":downloadDeps"))
  }
  withType<JavaCompile> {
    dependsOn(testbaseJavaCompileTask)
    dependsOn(createArtTests)
    dependsOn(gradle.includedBuild("keepanno").task(":jar"))
    dependsOn(gradle.includedBuild("resourceshrinker").task(":jar"))
    dependsOn(gradle.includedBuild("main").task(":compileJava"))
    dependsOn(gradle.includedBuild("main").task(":processResources"))
    dependsOn(gradle.includedBuild("shared").task(":downloadDeps"))
  }

  withType<JavaExec> {
    if (name.endsWith("main()")) {
      // IntelliJ pass the main execution through a stream which is
      // not compatible with gradle configuration cache.
      notCompatibleWithConfigurationCache("JavaExec created by IntelliJ")
    }
  }

  withType<KotlinCompile> {
    enabled = false
  }

  val sourceSetDependencyTask by registering {
    dependsOn(*sourceSetDependenciesTasks)
  }

  withType<Test> {
    TestingState.setUpTestingState(this)
    dependsOn(mainDepsJarTask)
    dependsOn(gradle.includedBuild("shared").task(":downloadDeps"))
    if (!project.hasProperty("no_internal")) {
      dependsOn(gradle.includedBuild("shared").task(":downloadDepsInternal"))
    }
    dependsOn(sourceSetDependencyTask)
    systemProperty("TEST_DATA_LOCATION",
                   layout.buildDirectory.dir("classes/java/test").get().toString())
    systemProperty("TESTBASE_DATA_LOCATION",
                   testbaseJavaCompileTask.outputs.files.getAsPath().split(File.pathSeparator)[0])

    systemProperty(
      "BUILD_PROP_KEEPANNO_RUNTIME_PATH",
      keepAnnoCompileTask.outputs.files.getAsPath().split(File.pathSeparator)[0])
    // This path is set when compiling examples jar task in DependenciesPlugin.
    systemProperty("EXAMPLES_JAVA_11_JAVAC_BUILD_DIR",
                    getRoot().resolveAll("build", "test", "examplesJava11", "classes"))
    systemProperty(
      "BUILD_PROP_R8_RUNTIME_PATH",
      mainCompileTask.outputs.files.getAsPath().split(File.pathSeparator)[0] +
        File.pathSeparator + mainDepsJarTask.outputs.files.singleFile +
        File.pathSeparator + getRoot().resolveAll("src", "main", "resources") +
        File.pathSeparator + keepAnnoCompileTask.outputs.files.getAsPath().split(File.pathSeparator)[0] +
        File.pathSeparator + resourceShrinkerJavaCompileTask.outputs.files.getAsPath().split(File.pathSeparator)[0] +
        File.pathSeparator + resourceShrinkerKotlinCompileTask.outputs.files.getAsPath().split(File.pathSeparator)[1])
    systemProperty("R8_DEPS", mainDepsJarTask.outputs.files.singleFile)
    systemProperty("com.android.tools.r8.artprofilerewritingcompletenesscheck", "true")
  }

  val testJar by registering(Jar::class) {
    from(sourceSets.test.get().output)
    // TODO(b/296486206): Seems like IntelliJ has a problem depending on test source sets. Renaming
    //  this from the default name (tests_java_8.jar) will allow IntelliJ to find the resources in
    //  the jar and not show red underlines. However, navigation to base classes will not work.
    archiveFileName.set("not_named_tests_java_8.jar")
  }
}
