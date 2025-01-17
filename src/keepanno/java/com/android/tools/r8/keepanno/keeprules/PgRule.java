// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.keepanno.keeprules;

import static com.android.tools.r8.keepanno.keeprules.RulePrintingUtils.CHECK_DISCARD;
import static com.android.tools.r8.keepanno.keeprules.RulePrintingUtils.KEEP;
import static com.android.tools.r8.keepanno.keeprules.RulePrintingUtils.KEEP_ATTRIBUTES;
import static com.android.tools.r8.keepanno.keeprules.RulePrintingUtils.KEEP_CLASSES_WITH_MEMBERS;
import static com.android.tools.r8.keepanno.keeprules.RulePrintingUtils.KEEP_CLASS_MEMBERS;
import static com.android.tools.r8.keepanno.keeprules.RulePrintingUtils.printClassHeader;
import static com.android.tools.r8.keepanno.keeprules.RulePrintingUtils.printMemberClause;

import com.android.tools.r8.keepanno.ast.KeepAttribute;
import com.android.tools.r8.keepanno.ast.KeepBindings.KeepBindingSymbol;
import com.android.tools.r8.keepanno.ast.KeepClassItemPattern;
import com.android.tools.r8.keepanno.ast.KeepEdgeException;
import com.android.tools.r8.keepanno.ast.KeepEdgeMetaInfo;
import com.android.tools.r8.keepanno.ast.KeepMemberPattern;
import com.android.tools.r8.keepanno.ast.KeepOptions;
import com.android.tools.r8.keepanno.ast.KeepQualifiedClassNamePattern;
import com.android.tools.r8.keepanno.keeprules.KeepRuleExtractor.Holder;
import com.android.tools.r8.keepanno.keeprules.RulePrinter.BackReferencePrinter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public abstract class PgRule {

  /**
   * Group the rules such that unconditional rules appear first, followed by class rules and then
   * member rules. The original order of the rules is retained within each of the groups.
   */
  public static void groupByKinds(List<PgRule> rules) {
    IdentityHashMap<PgRule, Integer> order = new IdentityHashMap<>();
    rules.forEach(r -> order.put(r, order.size()));
    rules.sort(
        Comparator.comparingInt((PgRule p) -> p.hasCondition() ? 1 : 0)
            .thenComparingInt(
                p -> {
                  switch (p.getConsequenceKeepType()) {
                    case KEEP_ATTRIBUTES:
                      return 0;
                    case KEEP:
                      return 1;
                    case KEEP_CLASSES_WITH_MEMBERS:
                      return 2;
                    case KEEP_CLASS_MEMBERS:
                      return 3;
                    case CHECK_DISCARD:
                      return 4;
                    default:
                      throw new KeepEdgeException(
                          "Unexpected consequence keep type: " + p.getConsequenceKeepType());
                  }
                })
            .thenComparingInt(order::get));
  }

  public enum TargetKeepKind {
    JUST_MEMBERS(RulePrintingUtils.KEEP_CLASS_MEMBERS),
    CLASS_OR_MEMBERS(RulePrintingUtils.KEEP),
    CLASS_AND_MEMBERS(RulePrintingUtils.KEEP_CLASSES_WITH_MEMBERS),
    CHECK_DISCARD(RulePrintingUtils.CHECK_DISCARD);

    private final String ruleKind;

    TargetKeepKind(String ruleKind) {
      this.ruleKind = ruleKind;
    }

    String getKeepRuleKind() {
      return ruleKind;
    }

    boolean isKeepKind() {
      return this != CHECK_DISCARD;
    }
  }

  private static void printNonEmptyMembersPatternAsDefaultInitWorkaround(
      StringBuilder builder, TargetKeepKind kind) {
    if (kind.isKeepKind()) {
      // If no members is given, compat R8 and legacy full mode will implicitly keep <init>().
      // Add a keep of finalize which is a library method that would be kept in any case.
      builder.append(" { void finalize(); }");
    }
  }

  private final KeepEdgeMetaInfo metaInfo;
  private final KeepOptions options;
  private final KeepRuleExtractorOptions extractorOptions;

  private PgRule(
      KeepEdgeMetaInfo metaInfo, KeepOptions options, KeepRuleExtractorOptions extractorOptions) {
    this.metaInfo = metaInfo;
    this.options = options;
    this.extractorOptions = extractorOptions;
  }

  public KeepEdgeMetaInfo getMetaInfo() {
    return metaInfo;
  }

  public KeepRuleExtractorOptions getExtractorOptions() {
    return extractorOptions;
  }

  // Helper to print the class-name pattern in a class-item.
  public static BiConsumer<StringBuilder, KeepQualifiedClassNamePattern> classNamePrinter(
      KeepQualifiedClassNamePattern classNamePattern) {
    return (builder, className) -> {
      assert className.equals(classNamePattern);
      RulePrintingUtils.printClassName(
          classNamePattern, RulePrinter.withoutBackReferences(builder));
    };
  }

  void printKeepOptions(StringBuilder builder, KeepRuleExtractorOptions extractorOptions) {
    RulePrintingUtils.printKeepOptions(builder, options, extractorOptions);
  }

  public void printRule(StringBuilder builder, KeepRuleExtractorOptions extractorOptions) {
    RulePrintingUtils.printHeader(builder, metaInfo);
    printCondition(builder);
    printConsequence(builder, extractorOptions);
  }

  void printCondition(StringBuilder builder) {
    if (hasCondition()) {
      builder.append(RulePrintingUtils.IF).append(' ');
      printConditionHolder(builder);
      List<KeepBindingSymbol> members = getConditionMembers();
      if (!members.isEmpty()) {
        builder.append(" {");
        for (KeepBindingSymbol member : members) {
          builder.append(' ');
          printConditionMember(builder, member);
        }
        builder.append(" }");
      }
      builder.append(' ');
    }
  }

  void printConsequence(StringBuilder builder, KeepRuleExtractorOptions extractorOptions) {
    builder.append(getConsequenceKeepType());
    printKeepOptions(builder, extractorOptions);
    builder.append(' ');
    printTargetHolder(builder);
    List<KeepBindingSymbol> members = getTargetMembers();
    if (!members.isEmpty()) {
      builder.append(" {");
      for (KeepBindingSymbol member : members) {
        builder.append(' ');
        printTargetMember(builder, member);
      }
      builder.append(" }");
    }
  }

  boolean hasCondition() {
    return false;
  }

  List<KeepBindingSymbol> getConditionMembers() {
    throw new KeepEdgeException("Unreachable");
  }

  abstract String getConsequenceKeepType();

  abstract List<KeepBindingSymbol> getTargetMembers();

  void printConditionHolder(StringBuilder builder) {
    throw new KeepEdgeException("Unreachable");
  }

  void printConditionMember(StringBuilder builder, KeepBindingSymbol member) {
    throw new KeepEdgeException("Unreachable");
  }

  abstract void printTargetHolder(StringBuilder builder);

  abstract void printTargetMember(StringBuilder builder, KeepBindingSymbol member);

  /**
   * Representation of an unconditional rule to keep a class and methods.
   *
   * <pre>
   *   -keep[classeswithmembers] class <holder> [{ <members> }]
   * </pre>
   *
   * and with no dependencies / back-references.
   */
  static class PgUnconditionalRule extends PgRule {
    private final KeepQualifiedClassNamePattern holderNamePattern;
    private final KeepClassItemPattern holderPattern;
    private final TargetKeepKind targetKeepKind;
    private final List<KeepBindingSymbol> targetMembers;
    private final Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns;

    public PgUnconditionalRule(
        KeepEdgeMetaInfo metaInfo,
        Holder holder,
        KeepOptions options,
        Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns,
        List<KeepBindingSymbol> targetMembers,
        TargetKeepKind targetKeepKind,
        KeepRuleExtractorOptions extractorOptions) {
      super(metaInfo, options, extractorOptions);
      assert !targetKeepKind.equals(TargetKeepKind.JUST_MEMBERS);
      this.holderNamePattern = holder.getNamePattern();
      this.holderPattern = holder.getClassItemPattern();
      this.targetKeepKind = targetKeepKind;
      this.memberPatterns = memberPatterns;
      this.targetMembers = targetMembers;
    }

    @Override
    String getConsequenceKeepType() {
      return targetKeepKind.getKeepRuleKind();
    }

    @Override
    List<KeepBindingSymbol> getTargetMembers() {
      return targetMembers;
    }

    @Override
    void printTargetHolder(StringBuilder builder) {
      printClassHeader(builder, holderPattern, classNamePrinter(holderNamePattern));
      if (getTargetMembers().isEmpty()) {
        printNonEmptyMembersPatternAsDefaultInitWorkaround(builder, targetKeepKind);
      }
    }

    @Override
    void printTargetMember(StringBuilder builder, KeepBindingSymbol memberReference) {
      KeepMemberPattern memberPattern = memberPatterns.get(memberReference);
      printMemberClause(
          memberPattern, RulePrinter.withoutBackReferences(builder), getExtractorOptions());
    }
  }

  static class PgKeepAttributeRule extends PgRule {

    private final Set<KeepAttribute> attributes;

    public PgKeepAttributeRule(
        KeepEdgeMetaInfo metaInfo,
        Set<KeepAttribute> attributes,
        KeepRuleExtractorOptions extractorOptions) {
      super(metaInfo, null, extractorOptions);
      assert !attributes.isEmpty();
      this.attributes = attributes;
    }

    @Override
    public void printRule(StringBuilder builder, KeepRuleExtractorOptions options) {
      RulePrintingUtils.printHeader(builder, getMetaInfo());
      builder.append(getConsequenceKeepType()).append(" ");
      List<KeepAttribute> sorted = new ArrayList<>(attributes);
      sorted.sort(KeepAttribute::compareTo);
      builder.append(sorted.get(0).getPrintName());
      for (int i = 1; i < sorted.size(); i++) {
        builder.append(',').append(sorted.get(i).getPrintName());
      }
    }

    @Override
    String getConsequenceKeepType() {
      return KEEP_ATTRIBUTES;
    }

    @Override
    List<KeepBindingSymbol> getTargetMembers() {
      throw new IllegalStateException();
    }

    @Override
    void printTargetHolder(StringBuilder builder) {
      throw new IllegalStateException();
    }

    @Override
    void printTargetMember(StringBuilder builder, KeepBindingSymbol member) {
      throw new IllegalStateException();
    }
  }

  /**
   * Representation of conditional rules but without dependencies between condition and target.
   *
   * <pre>
   *   -if class <class-condition> [{ <member-conditions> }]
   *   -keepX class <class-target> [{ <member-targets> }]
   * </pre>
   *
   * and with no dependencies / back-references.
   */
  static class PgConditionalRule extends PgRule {

    final KeepClassItemPattern classCondition;
    final KeepClassItemPattern classTarget;
    final Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns;
    final List<KeepBindingSymbol> memberConditions;
    private final List<KeepBindingSymbol> memberTargets;
    private final TargetKeepKind keepKind;

    public PgConditionalRule(
        KeepEdgeMetaInfo metaInfo,
        KeepOptions options,
        Holder classCondition,
        Holder classTarget,
        Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns,
        List<KeepBindingSymbol> memberConditions,
        List<KeepBindingSymbol> memberTargets,
        TargetKeepKind keepKind,
        KeepRuleExtractorOptions extractorOptions) {
      super(metaInfo, options, extractorOptions);
      this.classCondition = classCondition.getClassItemPattern();
      this.classTarget = classTarget.getClassItemPattern();
      this.memberPatterns = memberPatterns;
      this.memberConditions = memberConditions;
      this.memberTargets = memberTargets;
      this.keepKind = keepKind;
    }

    @Override
    boolean hasCondition() {
      return true;
    }

    @Override
    List<KeepBindingSymbol> getConditionMembers() {
      return memberConditions;
    }

    @Override
    void printConditionHolder(StringBuilder builder) {
      printClassHeader(builder, classCondition, this::printClassName);
    }

    @Override
    void printConditionMember(StringBuilder builder, KeepBindingSymbol member) {
      KeepMemberPattern memberPattern = memberPatterns.get(member);
      printMemberClause(
          memberPattern, RulePrinter.withoutBackReferences(builder), getExtractorOptions());
    }

    @Override
    void printTargetHolder(StringBuilder builder) {
      printClassHeader(builder, classTarget, this::printClassName);
      if (getTargetMembers().isEmpty()) {
        PgRule.printNonEmptyMembersPatternAsDefaultInitWorkaround(builder, keepKind);
      }
    }

    @Override
    String getConsequenceKeepType() {
      return keepKind.getKeepRuleKind();
    }

    @Override
    List<KeepBindingSymbol> getTargetMembers() {
      return memberTargets;
    }

    @Override
    void printTargetMember(StringBuilder builder, KeepBindingSymbol member) {
      KeepMemberPattern memberPattern = memberPatterns.get(member);
      printMemberClause(
          memberPattern, RulePrinter.withoutBackReferences(builder), getExtractorOptions());
    }

    private void printClassName(StringBuilder builder, KeepQualifiedClassNamePattern clazzName) {
      RulePrintingUtils.printClassName(clazzName, RulePrinter.withoutBackReferences(builder));
    }
  }

  /**
   * Representation of a conditional rule that is match/instance dependent.
   *
   * <pre>
   *   -if class <class-pattern> [{ <member-condition>* }]
   *   -keepX class <class-backref> [{ <member-target | member-backref>* }]
   * </pre>
   *
   * or if the only condition is the class itself and targeting members, just:
   *
   * <pre>
   *   -keepclassmembers <class-pattern> { <member-target> }
   * </pre>
   */
  static class PgDependentMembersRule extends PgRule {

    private final KeepQualifiedClassNamePattern holderNamePattern;
    private final KeepClassItemPattern holderPattern;
    private final Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns;
    private final List<KeepBindingSymbol> memberConditions;
    private final List<KeepBindingSymbol> memberTargets;
    private final TargetKeepKind keepKind;

    private int nextBackReferenceNumber = 1;
    private String holderBackReferencePattern = null;
    private final Map<KeepBindingSymbol, String> membersBackReferencePatterns = new HashMap<>();

    public PgDependentMembersRule(
        KeepEdgeMetaInfo metaInfo,
        Holder holder,
        KeepOptions options,
        Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns,
        List<KeepBindingSymbol> memberConditions,
        List<KeepBindingSymbol> memberTargets,
        TargetKeepKind keepKind,
        KeepRuleExtractorOptions extractorOptions) {
      super(metaInfo, options, extractorOptions);
      this.holderNamePattern = holder.getNamePattern();
      this.holderPattern = holder.getClassItemPattern();
      this.memberPatterns = memberPatterns;
      this.memberConditions = memberConditions;
      this.memberTargets = memberTargets;
      this.keepKind = keepKind;
    }

    private int getNextBackReferenceNumber() {
      return nextBackReferenceNumber++;
    }

    @Override
    boolean hasCondition() {
      // We can avoid an if-rule if the condition is simply the class and the target is just
      // members.
      boolean canUseDependentRule =
          memberConditions.isEmpty() && keepKind == TargetKeepKind.JUST_MEMBERS;
      return !canUseDependentRule;
    }

    @Override
    String getConsequenceKeepType() {
      return keepKind.getKeepRuleKind();
    }

    @Override
    List<KeepBindingSymbol> getConditionMembers() {
      return memberConditions;
    }

    @Override
    List<KeepBindingSymbol> getTargetMembers() {
      return memberTargets;
    }

    @Override
    void printConditionHolder(StringBuilder b) {
      printClassHeader(
          b,
          holderPattern,
          (builder, classReference) -> {
            BackReferencePrinter printer =
                RulePrinter.withBackReferences(b, this::getNextBackReferenceNumber);
            RulePrintingUtils.printClassName(holderNamePattern, printer);
            holderBackReferencePattern = printer.getBackReference();
          });
    }

    @Override
    void printConditionMember(StringBuilder builder, KeepBindingSymbol member) {
      KeepMemberPattern memberPattern = memberPatterns.get(member);
      BackReferencePrinter printer =
          RulePrinter.withBackReferences(builder, this::getNextBackReferenceNumber);
      printMemberClause(memberPattern, printer, getExtractorOptions());
      membersBackReferencePatterns.put(member, printer.getBackReference());
    }

    @Override
    void printTargetHolder(StringBuilder builder) {
      printClassHeader(
          builder,
          holderPattern,
          (b, className) -> {
            assert className.equals(holderNamePattern);
            if (hasCondition()) {
              b.append(holderBackReferencePattern);
            } else {
              assert holderBackReferencePattern == null;
              RulePrintingUtils.printClassName(
                  holderNamePattern, RulePrinter.withoutBackReferences(builder));
            }
          });
      if (getTargetMembers().isEmpty()) {
        PgRule.printNonEmptyMembersPatternAsDefaultInitWorkaround(builder, keepKind);
      }
    }

    @Override
    void printTargetMember(StringBuilder builder, KeepBindingSymbol member) {
      if (hasCondition()) {
        String backref = membersBackReferencePatterns.get(member);
        if (backref != null) {
          builder.append(backref);
          return;
        }
      }
      KeepMemberPattern memberPattern = memberPatterns.get(member);
      printMemberClause(
          memberPattern, RulePrinter.withoutBackReferences(builder), getExtractorOptions());
    }
  }
}
