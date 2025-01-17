// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.keepanno.keeprules;

import com.android.tools.r8.keepanno.ast.KeepAttribute;
import com.android.tools.r8.keepanno.ast.KeepBindingReference;
import com.android.tools.r8.keepanno.ast.KeepBindings;
import com.android.tools.r8.keepanno.ast.KeepBindings.KeepBindingSymbol;
import com.android.tools.r8.keepanno.ast.KeepCheck;
import com.android.tools.r8.keepanno.ast.KeepCheck.KeepCheckKind;
import com.android.tools.r8.keepanno.ast.KeepClassBindingReference;
import com.android.tools.r8.keepanno.ast.KeepClassItemPattern;
import com.android.tools.r8.keepanno.ast.KeepCondition;
import com.android.tools.r8.keepanno.ast.KeepConstraints;
import com.android.tools.r8.keepanno.ast.KeepDeclaration;
import com.android.tools.r8.keepanno.ast.KeepEdge;
import com.android.tools.r8.keepanno.ast.KeepEdgeMetaInfo;
import com.android.tools.r8.keepanno.ast.KeepFieldAccessPattern;
import com.android.tools.r8.keepanno.ast.KeepFieldPattern;
import com.android.tools.r8.keepanno.ast.KeepInstanceOfPattern;
import com.android.tools.r8.keepanno.ast.KeepItemPattern;
import com.android.tools.r8.keepanno.ast.KeepMemberItemPattern;
import com.android.tools.r8.keepanno.ast.KeepMemberPattern;
import com.android.tools.r8.keepanno.ast.KeepMethodAccessPattern;
import com.android.tools.r8.keepanno.ast.KeepMethodPattern;
import com.android.tools.r8.keepanno.ast.KeepOptions;
import com.android.tools.r8.keepanno.ast.KeepOptions.KeepOption;
import com.android.tools.r8.keepanno.ast.KeepQualifiedClassNamePattern;
import com.android.tools.r8.keepanno.ast.KeepTarget;
import com.android.tools.r8.keepanno.keeprules.PgRule.PgConditionalRule;
import com.android.tools.r8.keepanno.keeprules.PgRule.PgDependentMembersRule;
import com.android.tools.r8.keepanno.keeprules.PgRule.PgKeepAttributeRule;
import com.android.tools.r8.keepanno.keeprules.PgRule.PgUnconditionalRule;
import com.android.tools.r8.keepanno.keeprules.PgRule.TargetKeepKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Extract the PG keep rules that over-approximate a keep edge. */
public class KeepRuleExtractor {

  private final KeepRuleExtractorOptions extractorOptions;
  private final Consumer<String> ruleConsumer;

  public KeepRuleExtractor(Consumer<String> ruleConsumer) {
    this(ruleConsumer, KeepRuleExtractorOptions.getR8Options());
  }

  public KeepRuleExtractor(
      Consumer<String> ruleConsumer, KeepRuleExtractorOptions extractorOptions) {
    this.ruleConsumer = ruleConsumer;
    this.extractorOptions = extractorOptions;
  }

  public void extract(KeepDeclaration declaration) {
    List<PgRule> rules = split(declaration);
    PgRule.groupByKinds(rules);
    StringBuilder builder = new StringBuilder();
    for (PgRule rule : rules) {
      rule.printRule(builder, extractorOptions);
      builder.append("\n");
    }
    ruleConsumer.accept(builder.toString());
  }

  private List<PgRule> split(KeepDeclaration declaration) {
    if (declaration.isKeepCheck()) {
      return generateCheckRules(declaration.asKeepCheck());
    }
    return doSplit(declaration.asKeepEdge());
  }

  private List<PgRule> generateCheckRules(KeepCheck check) {
    if (!extractorOptions.hasCheckDiscardSupport()) {
      return Collections.emptyList();
    }
    KeepBindingReference itemReference = check.getItemReference();
    KeepBindings bindings = check.getBindings();

    boolean isRemovedPattern = check.getKind() == KeepCheckKind.REMOVED;
    List<PgRule> rules = new ArrayList<>(isRemovedPattern ? 2 : 1);
    Holder holder;
    Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns;
    List<KeepBindingSymbol> targetMembers;
    KeepBindingSymbol symbol;
    if (itemReference.isClassType()) {
      symbol = itemReference.getName();
      memberPatterns = Collections.emptyMap();
      targetMembers = Collections.emptyList();
    } else {
      KeepMemberItemPattern memberItemPattern =
          bindings.getMemberItem(itemReference.asMemberBindingReference());
      KeepClassBindingReference classReference = memberItemPattern.getClassReference();
      symbol = classReference.getName();
      KeepMemberPattern memberPattern = memberItemPattern.getMemberPattern();
      // This does not actually allocate a binding as the mapping is maintained in 'memberPatterns'.
      KeepBindingSymbol memberSymbol = itemReference.getName();
      memberPatterns = Collections.singletonMap(memberSymbol, memberPattern);
      targetMembers = Collections.singletonList(memberSymbol);
    }
    holder = Holder.create(symbol, bindings);
    // Add a -checkdiscard rule for the class or members.
    rules.add(
        new PgUnconditionalRule(
            check.getMetaInfo(),
            holder,
            KeepOptions.keepAll(),
            memberPatterns,
            targetMembers,
            TargetKeepKind.CHECK_DISCARD,
            extractorOptions));
    // If the check declaration is to ensure full removal we generate a soft-pin rule to disallow
    // moving/inlining the items.
    if (isRemovedPattern) {
      KeepOptions allowShrinking = KeepOptions.allow(KeepOption.SHRINKING);
      if (itemReference.isClassType()) {
        // A check removal on a class means that the entire class is removed, thus soft-pin the
        // class and *all* of its members.
        KeepBindingSymbol memberSymbol = new KeepBindingSymbol("MEMBERS");
        rules.add(
            new PgUnconditionalRule(
                check.getMetaInfo(),
                holder,
                allowShrinking,
                Collections.singletonMap(memberSymbol, KeepMemberPattern.allMembers()),
                Collections.singletonList(memberSymbol),
                TargetKeepKind.CLASS_OR_MEMBERS,
                extractorOptions));
      } else {
        // A check removal on members just soft-pins the members.
        rules.add(
            new PgDependentMembersRule(
                check.getMetaInfo(),
                holder,
                allowShrinking,
                memberPatterns,
                Collections.emptyList(),
                targetMembers,
                TargetKeepKind.JUST_MEMBERS,
                extractorOptions));
      }
    }
    return rules;
  }

  /** Utility to package up a class binding with its name and item pattern. */
  public static class Holder {
    private final KeepClassItemPattern itemPattern;

    static Holder create(KeepBindingSymbol bindingName, KeepBindings bindings) {
      KeepClassItemPattern itemPattern = bindings.get(bindingName).getItem().asClassItemPattern();
      return new Holder(itemPattern);
    }

    private Holder(KeepClassItemPattern itemPattern) {
      assert itemPattern != null;
      this.itemPattern = itemPattern;
    }

    public KeepClassItemPattern getClassItemPattern() {
      return itemPattern;
    }

    public KeepQualifiedClassNamePattern getNamePattern() {
      return getClassItemPattern().getClassNamePattern();
    }

    public void onTargetHolders(Consumer<Holder> fn) {
      KeepInstanceOfPattern instanceOfPattern = itemPattern.getInstanceOfPattern();
      if (instanceOfPattern.isAny()) {
        // An any-pattern does not give rise to 'extends' and maps as is.
        fn.accept(this);
        return;
      }
      if (instanceOfPattern.isExclusive()) {
        // An exclusive-pattern maps to the "extends" clause as is.
        fn.accept(this);
        return;
      }
      if (getNamePattern().isExact()) {
        // This case is a pattern of "Foo instance-of Bar" and only makes sense if Foo==Bar.
        // In any case we can conservatively cover this case by ignoring the instance-of clause.
        Holder holderWithoutExtends =
            new Holder(
                KeepClassItemPattern.builder()
                    .copyFrom(itemPattern)
                    .setInstanceOfPattern(KeepInstanceOfPattern.any())
                    .build());
        fn.accept(holderWithoutExtends);
        return;
      }
      if (getNamePattern().isAny()) {
        // This case is a pattern of "* instance-of Bar" and we match that as two rules, one of
        // which is just the rule on the instance-of moved to the class name.
        Holder holderWithInstanceOfAsName =
            new Holder(
                KeepClassItemPattern.builder()
                    .copyFrom(itemPattern)
                    .setClassNamePattern(instanceOfPattern.getClassNamePattern())
                    .setInstanceOfPattern(KeepInstanceOfPattern.any())
                    .build());
        fn.accept(this);
        fn.accept(holderWithInstanceOfAsName);
        return;
      }
      // The remaining case is the general "*Foo* instance-of *Bar*" case. Here it unfolds to two
      // cases matching anything of the form "*Foo*" and the other being the exclusive extends.
      Holder holderWithNoInstanceOf =
          new Holder(
              KeepClassItemPattern.builder()
                  .copyFrom(itemPattern)
                  .setInstanceOfPattern(KeepInstanceOfPattern.any())
                  .build());
      fn.accept(this);
      fn.accept(holderWithNoInstanceOf);
    }
  }

  private static KeepOptions defaultOptions =
      KeepOptions.disallowBuilder()
          .addAll(KeepOption.SHRINKING, KeepOption.OBFUSCATING, KeepOption.OPTIMIZING)
          .build();

  private static class BindingUsers {

    final Holder holder;
    final Set<KeepBindingSymbol> conditionRefs = new HashSet<>();
    final Map<KeepOptions, Set<KeepBindingSymbol>> targetRefs = new HashMap<>();

    static BindingUsers create(KeepBindingSymbol bindingName, KeepBindings bindings) {
      return new BindingUsers(Holder.create(bindingName, bindings));
    }

    private BindingUsers(Holder holder) {
      this.holder = holder;
    }

    public void addCondition(KeepCondition condition) {
      conditionRefs.add(condition.getItem().getName());
    }

    public void addTarget(KeepTarget target) {
      KeepConstraints constraints = target.getConstraints();
      KeepOptions options = constraints.convertToKeepOptions(defaultOptions);
      KeepBindingReference bindingReference = target.getItem();
      targetRefs.computeIfAbsent(options, k -> new HashSet<>()).add(bindingReference.getName());
    }
  }

  @SuppressWarnings("UnnecessaryParentheses")
  private List<PgRule> doSplit(KeepEdge edge) {
    List<PgRule> rules = new ArrayList<>();
    // Collection for all attribute constraints required for this edge.
    Set<KeepAttribute> allAttributeConstraints = new HashSet<>();
    // First step after normalizing is to group up all conditions and targets on their target class.
    // Here we use the normalized binding as the notion of identity on a class.
    KeepBindings bindings = edge.getBindings();
    Map<KeepBindingSymbol, BindingUsers> bindingUsers = new HashMap<>();
    edge.getPreconditions()
        .forEach(
            condition -> {
              KeepBindingSymbol classReference =
                  getClassItemBindingReference(condition.getItem(), bindings);
              assert classReference != null;
              bindingUsers
                  .computeIfAbsent(classReference, k -> BindingUsers.create(k, bindings))
                  .addCondition(condition);
            });
    edge.getConsequences()
        .forEachTarget(
            target -> {
              allAttributeConstraints.addAll(target.getConstraints().getRequiredKeepAttributes());
              KeepBindingSymbol classReference =
                  getClassItemBindingReference(target.getItem(), bindings);
              assert classReference != null;
              bindingUsers
                  .computeIfAbsent(classReference, k -> BindingUsers.create(k, bindings))
                  .addTarget(target);
            });

    // Generate at most one `-keepattributes` rule for the edge if needed.
    if (!allAttributeConstraints.isEmpty()) {
      rules.add(
          new PgKeepAttributeRule(edge.getMetaInfo(), allAttributeConstraints, extractorOptions));
    }

    bindingUsers.forEach(
        (targetBindingName, users) -> {
          Holder targetHolder = users.holder;
          if (!users.conditionRefs.isEmpty() && !users.targetRefs.isEmpty()) {
            // The targets depend on the condition and thus we generate just the dependent edges.
            users.targetRefs.forEach(
                (options, targets) -> {
                  createDependentRules(
                      rules,
                      targetHolder,
                      edge.getMetaInfo(),
                      bindings,
                      options,
                      users.conditionRefs,
                      targets);
                });
          } else if (!users.targetRefs.isEmpty()) {
            // The targets don't have a binding relation to any conditions, so we generate a rule
            // per condition, or a single unconditional edge if no conditions exist.
            if (edge.getPreconditions().isAlways()) {
              users.targetRefs.forEach(
                  ((options, targets) -> {
                    createUnconditionalRules(
                        rules, targetHolder, edge.getMetaInfo(), bindings, options, targets);
                  }));
            } else {
              users.targetRefs.forEach(
                  ((options, targets) -> {
                    // Note that here we iterate over *all* non-empty conditions and create rules.
                    // Doing so over-approximates the matching instances of the edge, but gives
                    // better stability of the extraction as it avoids picking a particular
                    // precondition as the "primary" one to act on.
                    bindingUsers.forEach(
                        (conditionBindingName, conditionUsers) -> {
                          if (!conditionUsers.conditionRefs.isEmpty()) {
                            createConditionalRules(
                                rules,
                                edge.getMetaInfo(),
                                conditionUsers.holder,
                                targetHolder,
                                bindings,
                                options,
                                conditionUsers.conditionRefs,
                                targets);
                          }
                        });
                  }));
            }
          }
        });

    assert !rules.isEmpty();
    return rules;
  }

  private static List<KeepBindingSymbol> computeConditions(
      Set<KeepBindingSymbol> conditions,
      KeepBindings bindings,
      Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns) {
    List<KeepBindingSymbol> conditionMembers = new ArrayList<>();
    conditions.forEach(
        conditionReference -> {
          KeepItemPattern item = bindings.get(conditionReference).getItem();
          if (!item.isClassItemPattern()) {
            KeepMemberItemPattern memberItemPattern = item.asMemberItemPattern();
            KeepMemberPattern old =
                memberPatterns.put(conditionReference, memberItemPattern.getMemberPattern());
            conditionMembers.add(conditionReference);
            assert old == null;
          }
        });
    return conditionMembers;
  }

  @FunctionalInterface
  private interface OnTargetCallback {
    void accept(
        Holder targetHolder,
        Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns,
        List<KeepBindingSymbol> memberTargets,
        TargetKeepKind keepKind);
  }

  private static void computeTargets(
      Holder targetHolder,
      Set<KeepBindingSymbol> targets,
      KeepBindings bindings,
      Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns,
      OnTargetCallback callback) {
    TargetKeepKind keepKind = TargetKeepKind.JUST_MEMBERS;
    List<KeepBindingSymbol> targetMembers = new ArrayList<>();
    boolean isAllMembersTarget = false;
    for (KeepBindingSymbol targetReference : targets) {
      KeepItemPattern item = bindings.get(targetReference).getItem();
      if (item.isClassItemPattern()) {
        keepKind = TargetKeepKind.CLASS_AND_MEMBERS;
      } else if (!isAllMembersTarget) {
        KeepMemberItemPattern memberItemPattern = item.asMemberItemPattern();
        if (memberItemPattern.getMemberPattern().isAllMembers()) {
          targetMembers.clear();
          isAllMembersTarget = true;
        }
        memberPatterns.putIfAbsent(targetReference, memberItemPattern.getMemberPattern());
        targetMembers.add(targetReference);
      }
    }
    if (isAllMembersTarget && keepKind == TargetKeepKind.CLASS_AND_MEMBERS) {
      // If the rule is keeping the class and all of its members, the member pattern should
      // match even the empty set of members, e.g., be disjoint from keeping the class.
      keepKind = TargetKeepKind.CLASS_OR_MEMBERS;
    }
    if (targetMembers.isEmpty()) {
      keepKind = TargetKeepKind.CLASS_OR_MEMBERS;
    }
    final TargetKeepKind finalKeepKind = keepKind;
    targetHolder.onTargetHolders(
        newTargetHolder ->
            callback.accept(newTargetHolder, memberPatterns, targetMembers, finalKeepKind));
  }

  private void createUnconditionalRules(
      List<PgRule> rules,
      Holder holder,
      KeepEdgeMetaInfo metaInfo,
      KeepBindings bindings,
      KeepOptions options,
      Set<KeepBindingSymbol> targets) {
    computeTargets(
        holder,
        targets,
        bindings,
        new HashMap<>(),
        (targetHolder, memberPatterns, targetMembers, targetKeepKind) -> {
          if (targetKeepKind.equals(TargetKeepKind.JUST_MEMBERS)) {
            // Members dependent on the class, so they go to the implicitly dependent rule.
            rules.add(
                new PgDependentMembersRule(
                    metaInfo,
                    targetHolder,
                    options,
                    memberPatterns,
                    Collections.emptyList(),
                    targetMembers,
                    targetKeepKind,
                    extractorOptions));
          } else {
            rules.add(
                new PgUnconditionalRule(
                    metaInfo,
                    targetHolder,
                    options,
                    memberPatterns,
                    targetMembers,
                    targetKeepKind,
                    extractorOptions));
          }
        });
  }

  private void createConditionalRules(
      List<PgRule> rules,
      KeepEdgeMetaInfo metaInfo,
      Holder conditionHolder,
      Holder targetHolder,
      KeepBindings bindings,
      KeepOptions options,
      Set<KeepBindingSymbol> conditions,
      Set<KeepBindingSymbol> targets) {
    if (conditionHolder.getNamePattern().isExact()
        && conditionHolder.getClassItemPattern().equals(targetHolder.getClassItemPattern())) {
      // If the targets are conditional on its holder, the rule can be simplified as a dependent
      // rule. Note that this is only valid on an *exact* class matching as otherwise any
      // wildcard is allowed to be matched independently on the left and right of the edge.
      createDependentRules(rules, targetHolder, metaInfo, bindings, options, conditions, targets);
      return;
    }
    Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns = new HashMap<>();
    List<KeepBindingSymbol> conditionMembers =
        computeConditions(conditions, bindings, memberPatterns);
    computeTargets(
        targetHolder,
        targets,
        bindings,
        memberPatterns,
        (newTargetHolder, ignore, targetMembers, targetKeepKind) ->
            rules.add(
                new PgConditionalRule(
                    metaInfo,
                    options,
                    conditionHolder,
                    newTargetHolder,
                    memberPatterns,
                    conditionMembers,
                    targetMembers,
                    targetKeepKind,
                    extractorOptions)));
  }

  private void createDependentRules(
      List<PgRule> rules,
      Holder initialHolder,
      KeepEdgeMetaInfo metaInfo,
      KeepBindings bindings,
      KeepOptions options,
      Set<KeepBindingSymbol> conditions,
      Set<KeepBindingSymbol> targets) {
    Map<KeepBindingSymbol, KeepMemberPattern> memberPatterns = new HashMap<>();
    List<KeepBindingSymbol> conditionMembers =
        computeConditions(conditions, bindings, memberPatterns);
    computeTargets(
        initialHolder,
        targets,
        bindings,
        memberPatterns,
        (holder, ignore, targetMembers, targetKeepKind) -> {
          List<KeepBindingSymbol> nonAllMemberTargets = new ArrayList<>(targetMembers.size());
          for (KeepBindingSymbol targetMember : targetMembers) {
            KeepMemberPattern memberPattern = memberPatterns.get(targetMember);
            if (memberPattern.isGeneralMember() && conditionMembers.contains(targetMember)) {
              // This pattern is on "members in general" and it is bound by a condition.
              // Since backrefs can't reference a *-member we split this target in two, one for
              // fields and one for methods.
              HashMap<KeepBindingSymbol, KeepMemberPattern> copyWithMethod =
                  new HashMap<>(memberPatterns);
              copyWithMethod.put(targetMember, copyMethodFromMember(memberPattern));
              rules.add(
                  new PgDependentMembersRule(
                      metaInfo,
                      holder,
                      options,
                      copyWithMethod,
                      conditionMembers,
                      Collections.singletonList(targetMember),
                      targetKeepKind,
                      extractorOptions));
              HashMap<KeepBindingSymbol, KeepMemberPattern> copyWithField =
                  new HashMap<>(memberPatterns);
              copyWithField.put(targetMember, copyFieldFromMember(memberPattern));
              rules.add(
                  new PgDependentMembersRule(
                      metaInfo,
                      holder,
                      options,
                      copyWithField,
                      conditionMembers,
                      Collections.singletonList(targetMember),
                      targetKeepKind,
                      extractorOptions));
            } else {
              nonAllMemberTargets.add(targetMember);
            }
          }
          if (targetKeepKind.equals(TargetKeepKind.JUST_MEMBERS) && nonAllMemberTargets.isEmpty()) {
            return;
          }
          rules.add(
              new PgDependentMembersRule(
                  metaInfo,
                  holder,
                  options,
                  memberPatterns,
                  conditionMembers,
                  nonAllMemberTargets,
                  targetKeepKind,
                  extractorOptions));
        });
  }

  private static KeepMethodPattern copyMethodFromMember(KeepMemberPattern pattern) {
    KeepMethodAccessPattern accessPattern =
        KeepMethodAccessPattern.builder().copyOfMemberAccess(pattern.getAccessPattern()).build();
    return KeepMethodPattern.builder().setAccessPattern(accessPattern).build();
  }

  private static KeepFieldPattern copyFieldFromMember(KeepMemberPattern pattern) {
    KeepFieldAccessPattern accessPattern =
        KeepFieldAccessPattern.builder().copyOfMemberAccess(pattern.getAccessPattern()).build();
    return KeepFieldPattern.builder().setAccessPattern(accessPattern).build();
  }

  private static KeepBindingSymbol getClassItemBindingReference(
      KeepBindingReference itemReference, KeepBindings bindings) {
    return itemReference.apply(
        KeepBindingReference::getName,
        member -> bindings.getMemberItem(member).getClassReference().getName());
  }
}
