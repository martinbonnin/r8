// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.experimental.startup.profile.art;

import com.android.tools.r8.TextInputStream;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.startup.diagnostic.HumanReadableARTProfileParserErrorDiagnostic;
import com.android.tools.r8.utils.Action;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.Reporter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public class HumanReadableARTProfileParser {

  private final ARTProfileBuilder profileBuilder;
  private final Reporter reporter;

  HumanReadableARTProfileParser(ARTProfileBuilder profileBuilder, Reporter reporter) {
    this.profileBuilder = profileBuilder;
    this.reporter = reporter;
  }

  public static Builder builder() {
    return new Builder();
  }

  public void parse(TextInputStream textInputStream, Origin origin) {
    try {
      try (InputStreamReader inputStreamReader =
              new InputStreamReader(
                  textInputStream.getInputStream(), textInputStream.getCharset());
          BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
        int lineNumber = 1;
        while (bufferedReader.ready()) {
          String rule = bufferedReader.readLine();
          if (!parseRule(rule)) {
            parseError(rule, lineNumber, origin);
          }
          lineNumber++;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void parseError(String rule, int lineNumber, Origin origin) {
    if (reporter != null) {
      reporter.warning(new HumanReadableARTProfileParserErrorDiagnostic(rule, lineNumber, origin));
    }
  }

  public boolean parseRule(String rule) {
    ARTProfileMethodRuleInfoImpl.Builder methodRuleInfoBuilder =
        ARTProfileMethodRuleInfoImpl.builder();
    rule = parseFlag(rule, 'H', methodRuleInfoBuilder::setHot);
    rule = parseFlag(rule, 'S', methodRuleInfoBuilder::setStartup);
    rule = parseFlag(rule, 'P', methodRuleInfoBuilder::setPostStartup);
    return parseClassOrMethodDescriptor(rule, methodRuleInfoBuilder.build());
  }

  private static String parseFlag(String rule, char c, Action action) {
    if (!rule.isEmpty() && rule.charAt(0) == c) {
      action.execute();
      return rule.substring(1);
    }
    return rule;
  }

  private boolean parseClassOrMethodDescriptor(
      String descriptor, ARTProfileMethodRuleInfoImpl methodRuleInfo) {
    int arrowStartIndex = descriptor.indexOf("->");
    if (arrowStartIndex >= 0) {
      return parseMethodRule(descriptor, methodRuleInfo, arrowStartIndex);
    } else if (methodRuleInfo.isEmpty()) {
      return parseClassRule(descriptor);
    } else {
      return false;
    }
  }

  private boolean parseClassRule(String descriptor) {
    ClassReference classReference = parseClassDescriptor(descriptor);
    if (classReference == null) {
      return false;
    }
    profileBuilder.addClassRule(classReference, ARTProfileClassRuleInfoImpl.empty());
    return true;
  }

  private boolean parseMethodRule(
      String descriptor, ARTProfileMethodRuleInfoImpl methodRuleInfo, int arrowStartIndex) {
    MethodReference methodReference = parseMethodDescriptor(descriptor, arrowStartIndex);
    if (methodReference == null) {
      return false;
    }
    profileBuilder.addMethodRule(methodReference, methodRuleInfo);
    return true;
  }

  private ClassReference parseClassDescriptor(String classDescriptor) {
    if (DescriptorUtils.isClassDescriptor(classDescriptor)) {
      return Reference.classFromDescriptor(classDescriptor);
    } else {
      return null;
    }
  }

  private MethodReference parseMethodDescriptor(
      String startupMethodDescriptor, int arrowStartIndex) {
    String classDescriptor = startupMethodDescriptor.substring(0, arrowStartIndex);
    ClassReference methodHolder = parseClassDescriptor(classDescriptor);
    if (methodHolder == null) {
      return null;
    }

    int methodNameStartIndex = arrowStartIndex + 2;
    String protoWithNameDescriptor = startupMethodDescriptor.substring(methodNameStartIndex);
    int methodNameEndIndex = protoWithNameDescriptor.indexOf('(');
    if (methodNameEndIndex <= 0) {
      return null;
    }
    String methodName = protoWithNameDescriptor.substring(0, methodNameEndIndex);

    String protoDescriptor = protoWithNameDescriptor.substring(methodNameEndIndex);
    return parseMethodProto(methodHolder, methodName, protoDescriptor);
  }

  private MethodReference parseMethodProto(
      ClassReference methodHolder, String methodName, String protoDescriptor) {
    List<TypeReference> parameterTypes = new ArrayList<>();
    for (String parameterTypeDescriptor :
        DescriptorUtils.getArgumentTypeDescriptors(protoDescriptor)) {
      parameterTypes.add(Reference.typeFromDescriptor(parameterTypeDescriptor));
    }
    String returnTypeDescriptor = DescriptorUtils.getReturnTypeDescriptor(protoDescriptor);
    TypeReference returnType = Reference.returnTypeFromDescriptor(returnTypeDescriptor);
    return Reference.method(methodHolder, methodName, parameterTypes, returnType);
  }

  public static class Builder {

    private ARTProfileBuilder profileBuilder;
    private Reporter reporter;

    public Builder setReporter(Reporter reporter) {
      this.reporter = reporter;
      return this;
    }

    public Builder setProfileBuilder(ARTProfileBuilder profileBuilder) {
      this.profileBuilder = profileBuilder;
      return this;
    }

    public HumanReadableARTProfileParser build() {
      return new HumanReadableARTProfileParser(profileBuilder, reporter);
    }
  }
}
