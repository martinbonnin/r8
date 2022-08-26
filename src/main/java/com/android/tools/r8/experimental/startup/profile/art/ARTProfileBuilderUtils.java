// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.experimental.startup.profile.art;

import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.startup.ARTProfileClassRuleInfo;
import com.android.tools.r8.startup.ARTProfileMethodRuleInfo;
import com.android.tools.r8.startup.ARTProfileRulePredicate;
import com.android.tools.r8.startup.StartupProfileBuilder;

public class ARTProfileBuilderUtils {

  private static final String COMPANION_CLASS_SUFFIX = "$-CC";
  private static final String EXTERNAL_SYNTHETIC_SUFFIX = "$$ExternalSynthetic";

  public interface SyntheticToSyntheticContextGeneralization {

    ClassReference getSyntheticContextReference(ClassReference classReference);

    /**
     * When a startup profile is given to D8, the program input should be dex and the startup
     * profile should have been generated by launching the dex that is given on input. Therefore,
     * synthetic items in the ART profile should be present in the program input to D8 with the
     * exact same synthetic names as in the ART profile. This means that there is no need to
     * generalize synthetic items to their synthetic context.
     */
    static SyntheticToSyntheticContextGeneralization createForD8() {
      return classReference -> null;
    }

    /**
     * When a startup profile is given to R8, the program input is class files and the startup
     * profile should have been generated by dexing the program input (in release and intermediate
     * mode) and then launching the resulting app. The synthetic items in the resulting ART profile
     * do not exist in the program input to R8 (and the same synthetics may receive different names
     * in the R8 compilation). Therefore, synthetic items in the ART profile are generalized into
     * matching all synthetics from their synthetic context.
     */
    static SyntheticToSyntheticContextGeneralization createForR8() {
      return classReference -> {
        // TODO(b/243777722): Move this logic into synthetic items and extend the mapping from
        //  synthetic classes to their synthetic context to all synthetic kinds.
        String classDescriptor = classReference.getDescriptor();
        for (int i = 1; i < classDescriptor.length() - 1; i++) {
          if (classDescriptor.charAt(i) != '$') {
            continue;
          }
          if (classDescriptor.regionMatches(
                  i, COMPANION_CLASS_SUFFIX, 0, COMPANION_CLASS_SUFFIX.length())
              || classDescriptor.regionMatches(
                  i, EXTERNAL_SYNTHETIC_SUFFIX, 0, EXTERNAL_SYNTHETIC_SUFFIX.length())) {
            return Reference.classFromDescriptor(classDescriptor.substring(0, i) + ";");
          }
        }
        return null;
      };
    }
  }

  /**
   * Helper for creating an {@link ARTProfileBuilder} that performs callbacks on the given {@param
   * startupProfileBuilder}.
   */
  public static ARTProfileBuilder createBuilderForARTProfileToStartupProfileConversion(
      StartupProfileBuilder startupProfileBuilder,
      ARTProfileRulePredicate rulePredicate,
      SyntheticToSyntheticContextGeneralization syntheticToSyntheticContextGeneralization) {
    return new ARTProfileBuilder() {

      @Override
      public void addClassRule(
          ClassReference classReference, ARTProfileClassRuleInfo classRuleInfo) {
        if (rulePredicate.testClassRule(classReference, classRuleInfo)) {
          ClassReference syntheticContextReference =
              syntheticToSyntheticContextGeneralization.getSyntheticContextReference(
                  classReference);
          if (syntheticContextReference == null) {
            startupProfileBuilder.addStartupClass(
                startupClassBuilder -> startupClassBuilder.setClassReference(classReference));
          } else {
            startupProfileBuilder.addSyntheticStartupMethod(
                syntheticStartupMethodBuilder ->
                    syntheticStartupMethodBuilder.setSyntheticContextReference(
                        syntheticContextReference));
          }
        }
      }

      @Override
      public void addMethodRule(
          MethodReference methodReference, ARTProfileMethodRuleInfo methodRuleInfo) {
        if (rulePredicate.testMethodRule(methodReference, methodRuleInfo)) {
          ClassReference syntheticContextReference =
              syntheticToSyntheticContextGeneralization.getSyntheticContextReference(
                  methodReference.getHolderClass());
          if (syntheticContextReference == null) {
            startupProfileBuilder.addStartupMethod(
                startupMethodBuilder -> startupMethodBuilder.setMethodReference(methodReference));
          } else {
            startupProfileBuilder.addSyntheticStartupMethod(
                syntheticStartupMethodBuilder ->
                    syntheticStartupMethodBuilder.setSyntheticContextReference(
                        syntheticContextReference));
          }
        }
      }
    };
  }
}
