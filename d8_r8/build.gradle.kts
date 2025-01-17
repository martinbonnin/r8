// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

plugins {
  `kotlin-dsl`
  id("dependencies-plugin")
}

tasks {
  "clean" {
    dependsOn(gradle.includedBuild("commonBuildSrc").task(":clean"))
    dependsOn(gradle.includedBuild("keepanno").task(":clean"))
    dependsOn(gradle.includedBuild("resourceshrinker").task(":clean"))
    dependsOn(gradle.includedBuild("main").task(":clean"))
    dependsOn(gradle.includedBuild("library_desugar").task(":clean"))
    dependsOn(gradle.includedBuild("test").task(":clean"))
  }

  val r8 by registering() {
    dependsOn(gradle.includedBuild("main").task(":r8WithRelocatedDeps"))
  }

  val r8lib by registering() {
    dependsOn(gradle.includedBuild("test").task(":assembleR8LibWithRelocatedDeps"))
  }
}
