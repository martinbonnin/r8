// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.profile.art;

import com.android.tools.r8.TextInputStream;
import com.android.tools.r8.TextOutputStream;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.PrunedItems;
import com.android.tools.r8.graph.lens.GraphLens;
import com.android.tools.r8.ir.optimize.enums.EnumUnboxingLens;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.profile.AbstractProfile;
import com.android.tools.r8.profile.AbstractProfileRule;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Reporter;
import com.android.tools.r8.utils.ThrowingConsumer;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ArtProfile
    implements AbstractProfile<
        ArtProfileClassRule, ArtProfileMethodRule, ArtProfile, ArtProfile.Builder> {

  private final Map<DexReference, ArtProfileRule> rules;

  ArtProfile(Map<DexReference, ArtProfileRule> rules) {
    this.rules = rules;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Builder builderForInitialArtProfile(
      ArtProfileProvider artProfileProvider, InternalOptions options) {
    return new Builder(artProfileProvider, options);
  }

  public static Builder builderWithCapacity(int capacity) {
    return new Builder(capacity);
  }

  @Override
  public Builder toEmptyBuilderWithCapacity() {
    return builderWithCapacity(rules.size());
  }

  @Override
  public boolean containsClassRule(DexType type) {
    return rules.containsKey(type);
  }

  @Override
  public boolean containsMethodRule(DexMethod method) {
    return rules.containsKey(method);
  }

  public <E extends Exception> void forEachRule(ThrowingConsumer<ArtProfileRule, E> ruleConsumer)
      throws E {
    for (ArtProfileRule rule : rules.values()) {
      ruleConsumer.accept(rule);
    }
  }

  @Override
  public <E1 extends Exception, E2 extends Exception> void forEachRule(
      ThrowingConsumer<? super ArtProfileClassRule, E1> classRuleConsumer,
      ThrowingConsumer<? super ArtProfileMethodRule, E2> methodRuleConsumer)
      throws E1, E2 {
    for (ArtProfileRule rule : rules.values()) {
      rule.accept(classRuleConsumer, methodRuleConsumer);
    }
  }

  @Override
  public ArtProfileClassRule getClassRule(DexType type) {
    return (ArtProfileClassRule) rules.get(type);
  }

  @Override
  public ArtProfileMethodRule getMethodRule(DexMethod method) {
    return (ArtProfileMethodRule) rules.get(method);
  }

  @SuppressWarnings("ReferenceEquality")
  public int size() {
    return rules.size();
  }

  public ArtProfile rewrittenWithLens(AppView<?> appView, GraphLens lens) {
    if (lens.isEnumUnboxerLens()) {
      return rewrittenWithLens(appView, lens.asEnumUnboxerLens());
    }
    return transform(
        (classRule, builder) -> {
          DexType newClassRule = lens.lookupType(classRule.getType());
          assert newClassRule.isClassType();
          builder.addClassRule(ArtProfileClassRule.builder().setType(newClassRule).build());
        },
        (methodRule, builder) ->
            builder.addMethodRule(
                ArtProfileMethodRule.builder()
                    .setMethod(lens.getRenamedMethodSignature(methodRule.getMethod()))
                    .acceptMethodRuleInfoBuilder(
                        methodRuleInfoBuilder ->
                            methodRuleInfoBuilder.merge(methodRule.getMethodRuleInfo()))
                    .build()));
  }

  @SuppressWarnings("ReferenceEquality")
  public ArtProfile rewrittenWithLens(AppView<?> appView, EnumUnboxingLens lens) {
    return transform(
        (classRule, builder) -> {
          DexType newClassRule = lens.lookupType(classRule.getType());
          if (newClassRule.isClassType()) {
            builder.addClassRule(ArtProfileClassRule.builder().setType(newClassRule).build());
          } else {
            assert newClassRule.isIntType();
          }
        },
        (methodRule, builder) -> {
          DexMethod newMethod = lens.getRenamedMethodSignature(methodRule.getMethod());
          // When moving non-synthetic methods from an enum class to its enum utility class we also
          // add a rule for the utility class.
          if (newMethod.getHolderType() != methodRule.getMethod().getHolderType()) {
            assert appView
                .getSyntheticItems()
                .isSyntheticOfKind(
                    newMethod.getHolderType(), naming -> naming.ENUM_UNBOXING_LOCAL_UTILITY_CLASS);
            builder.addClassRule(
                ArtProfileClassRule.builder().setType(newMethod.getHolderType()).build());
          }
          builder.addMethodRule(
              ArtProfileMethodRule.builder()
                  .setMethod(newMethod)
                  .acceptMethodRuleInfoBuilder(
                      methodRuleInfoBuilder ->
                          methodRuleInfoBuilder.merge(methodRule.getMethodRuleInfo()))
                  .build());
        });
  }

  public ArtProfile rewrittenWithLens(AppView<?> appView, NamingLens lens) {
    if (lens.isIdentityLens()) {
      return this;
    }
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    assert !lens.isIdentityLens();
    return transform(
        (classRule, builder) ->
            builder.addClassRule(
                ArtProfileClassRule.builder()
                    .setType(lens.lookupType(classRule.getType(), dexItemFactory))
                    .build()),
        (methodRule, builder) ->
            builder.addMethodRule(
                ArtProfileMethodRule.builder()
                    .setMethod(lens.lookupMethod(methodRule.getMethod(), dexItemFactory))
                    .acceptMethodRuleInfoBuilder(
                        methodRuleInfoBuilder ->
                            methodRuleInfoBuilder.merge(methodRule.getMethodRuleInfo()))
                    .build()));
  }

  public ArtProfile withoutMissingItems(AppView<?> appView) {
    AppInfo appInfo = appView.appInfo();
    return transform(
        (classRule, builder) -> {
          if (appInfo.hasDefinitionForWithoutExistenceAssert(classRule.getType())) {
            builder.addClassRule(
                ArtProfileClassRule.builder().setType(classRule.getType()).build());
          }
        },
        (methodRule, builder) -> {
          DexClass clazz =
              appInfo.definitionForWithoutExistenceAssert(methodRule.getMethod().getHolderType());
          if (methodRule.getMethod().isDefinedOnClass(clazz)) {
            builder.addMethodRule(
                ArtProfileMethodRule.builder()
                    .setMethod(methodRule.getMethod())
                    .acceptMethodRuleInfoBuilder(
                        methodRuleInfoBuilder ->
                            methodRuleInfoBuilder.merge(methodRule.getMethodRuleInfo()))
                    .build());
          }
        });
  }

  public ArtProfile withoutPrunedItems(PrunedItems prunedItems) {
    rules.keySet().removeIf(prunedItems::isRemoved);
    return this;
  }

  public void supplyConsumer(ArtProfileConsumer consumer, Reporter reporter) {
    if (consumer != null) {
      TextOutputStream textOutputStream = consumer.getHumanReadableArtProfileConsumer();
      if (textOutputStream != null) {
        supplyHumanReadableArtProfileConsumer(textOutputStream);
      }
      ArtProfileRuleConsumer ruleConsumer = consumer.getRuleConsumer();
      if (ruleConsumer != null) {
        supplyRuleConsumer(ruleConsumer);
      }
      consumer.finished(reporter);
    }
  }

  private void supplyHumanReadableArtProfileConsumer(TextOutputStream textOutputStream) {
    try {
      try (OutputStreamWriter outputStreamWriter =
          new OutputStreamWriter(
              textOutputStream.getOutputStream(), textOutputStream.getCharset())) {
        forEachRule(
            rule -> {
              rule.writeHumanReadableRuleString(outputStreamWriter);
              outputStreamWriter.write('\n');
            });
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void supplyRuleConsumer(ArtProfileRuleConsumer ruleConsumer) {
    forEachRule(
        classRule ->
            ruleConsumer.acceptClassRule(
                classRule.getClassReference(), classRule.getClassRuleInfo()),
        methodRule ->
            ruleConsumer.acceptMethodRule(
                methodRule.getMethodReference(), methodRule.getMethodRuleInfo()));
  }

  public static class Builder
      implements ArtProfileBuilder,
          AbstractProfile.Builder<ArtProfileClassRule, ArtProfileMethodRule, ArtProfile, Builder> {

    private final ArtProfileProvider artProfileProvider;
    private final DexItemFactory dexItemFactory;
    private Reporter reporter;
    private final Map<DexReference, ArtProfileRule> rules;

    Builder() {
      this(new LinkedHashMap<>());
    }

    Builder(Map<DexReference, ArtProfileRule> rules) {
      this.artProfileProvider = null;
      this.dexItemFactory = null;
      this.reporter = null;
      this.rules = rules;
    }

    Builder(int capacity) {
      this(new LinkedHashMap<>(capacity));
    }

    // Constructor for building the initial ART profile. The input is based on the Reference API, so
    // access to the DexItemFactory is needed for conversion into the internal DexReference.
    // Moreover, access to the Reporter is needed for diagnostics reporting.
    Builder(ArtProfileProvider artProfileProvider, InternalOptions options) {
      this.artProfileProvider = artProfileProvider;
      this.dexItemFactory = options.dexItemFactory();
      this.reporter = options.reporter;
      this.rules = new LinkedHashMap<>();
    }

    @Override
    public Builder addRule(AbstractProfileRule rule) {
      return addRule(rule.asArtProfileRule());
    }

    @Override
    public Builder addClassRule(ArtProfileClassRule classRule) {
      rules.put(classRule.getType(), classRule);
      return this;
    }

    @Override
    public boolean addClassRule(DexType type) {
      int oldSize = size();
      addClassRule(ArtProfileClassRule.builder().setType(type).build());
      return size() > oldSize;
    }

    @Override
    public Builder addMethodRule(ArtProfileMethodRule methodRule) {
      rules.compute(
          methodRule.getReference(),
          (reference, existingRule) -> {
            if (existingRule == null) {
              return methodRule;
            }
            ArtProfileMethodRule existingMethodRule = (ArtProfileMethodRule) existingRule;
            return ArtProfileMethodRule.builder()
                .setMethod(methodRule.getMethod())
                .join(methodRule)
                .join(existingMethodRule)
                .build();
          });
      return this;
    }

    public Builder addRule(ArtProfileRule rule) {
      return rule.apply(this::addClassRule, this::addMethodRule);
    }

    public Builder addRuleBuilders(Collection<ArtProfileRule.Builder> ruleBuilders) {
      ruleBuilders.forEach(ruleBuilder -> addRule(ruleBuilder.build()));
      return this;
    }

    @Override
    public Builder addClassRule(Consumer<ArtProfileClassRuleBuilder> classRuleBuilderConsumer) {
      ArtProfileClassRule.Builder classRuleBuilder = ArtProfileClassRule.builder(dexItemFactory);
      classRuleBuilderConsumer.accept(classRuleBuilder);
      return addClassRule(classRuleBuilder.build());
    }

    @Override
    public Builder addMethodRule(Consumer<ArtProfileMethodRuleBuilder> methodRuleBuilderConsumer) {
      ArtProfileMethodRule.Builder methodRuleBuilder = ArtProfileMethodRule.builder(dexItemFactory);
      methodRuleBuilderConsumer.accept(methodRuleBuilder);
      return addMethodRule(methodRuleBuilder.build());
    }

    @Override
    public Builder addHumanReadableArtProfile(
        TextInputStream textInputStream,
        Consumer<HumanReadableArtProfileParserBuilder> parserBuilderConsumer) {
      HumanReadableArtProfileParser.Builder parserBuilder =
          HumanReadableArtProfileParser.builder()
              .setDiagnosticConsumer(reporter::info)
              .setReporter(reporter)
              .setProfileBuilder(this);
      parserBuilderConsumer.accept(parserBuilder);
      HumanReadableArtProfileParser parser = parserBuilder.build();
      parser.parse(textInputStream, artProfileProvider.getOrigin());
      return this;
    }

    @Override
    public ArtProfile build() {
      return new ArtProfile(rules);
    }

    @Override
    public int size() {
      return rules.size();
    }
  }
}
