// Copyright (c) 2024, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.keepanno.ast;

import com.android.tools.r8.keepanno.ast.KeepOptions.KeepOption;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class KeepConstraints {

  public static KeepConstraints defaultConstraints() {
    return Defaults.INSTANCE;
  }

  public static KeepConstraints defaultAdditions(KeepConstraints additionalConstraints) {
    return new Additions(additionalConstraints);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private final Set<KeepConstraint> constraints = new HashSet<>();

    private Builder() {}

    public Builder add(KeepConstraint constraint) {
      constraints.add(constraint);
      return this;
    }

    public KeepConstraints build() {
      return new Constraints(constraints);
    }
  }

  private static class Defaults extends KeepConstraints {

    private static final Defaults INSTANCE = new Defaults();

    @Override
    public KeepOptions convertToKeepOptions(KeepOptions defaultOptions) {
      return defaultOptions;
    }

    @Override
    public String toString() {
      return "KeepConstraints.Defaults{}";
    }

    @Override
    public Set<KeepAttribute> getRequiredKeepAttributes() {
      // The default set of keep rules for any kind of target requires no additional attributes.
      return Collections.emptySet();
    }
  }

  private static class Additions extends KeepConstraints {

    private final KeepConstraints additions;

    public Additions(KeepConstraints additions) {
      this.additions = additions;
    }

    @Override
    public KeepOptions convertToKeepOptions(KeepOptions defaultOptions) {
      KeepOptions additionalOptions = additions.convertToKeepOptions(defaultOptions);
      KeepOptions.Builder builder = KeepOptions.disallowBuilder();
      for (KeepOption option : KeepOption.values()) {
        if (!additionalOptions.isAllowed(option) || !defaultOptions.isAllowed(option)) {
          builder.add(option);
        }
      }
      return builder.build();
    }

    @Override
    public Set<KeepAttribute> getRequiredKeepAttributes() {
      return additions.getRequiredKeepAttributes();
    }
  }

  private static class Constraints extends KeepConstraints {

    private final Set<KeepConstraint> constraints;

    public Constraints(Set<KeepConstraint> constraints) {
      this.constraints = ImmutableSet.copyOf(constraints);
    }

    @Override
    public KeepOptions convertToKeepOptions(KeepOptions defaultOptions) {
      KeepOptions.Builder builder = KeepOptions.disallowBuilder();
      for (KeepConstraint constraint : constraints) {
        constraint.convertToDisallowKeepOptions(builder);
      }
      return builder.build();
    }

    @Override
    public String toString() {
      return "KeepConstraints{"
          + constraints.stream().map(Objects::toString).collect(Collectors.joining(", "))
          + '}';
    }

    @Override
    public Set<KeepAttribute> getRequiredKeepAttributes() {
      Set<KeepAttribute> attributes = new HashSet<>();
      for (KeepConstraint constraint : constraints) {
        constraint.addRequiredKeepAttributes(attributes);
      }
      return attributes;
    }
  }

  public abstract KeepOptions convertToKeepOptions(KeepOptions defaultOptions);

  public abstract Set<KeepAttribute> getRequiredKeepAttributes();
}
