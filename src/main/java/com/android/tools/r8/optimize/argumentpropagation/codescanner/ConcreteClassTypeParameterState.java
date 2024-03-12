// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.optimize.argumentpropagation.codescanner;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.fieldaccess.state.ConcreteReferenceTypeFieldState;
import com.android.tools.r8.ir.analysis.type.DynamicType;
import com.android.tools.r8.ir.analysis.type.Nullability;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.optimize.argumentpropagation.utils.WideningUtils;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.Action;
import com.android.tools.r8.utils.SetUtils;
import java.util.Collections;
import java.util.Set;

public class ConcreteClassTypeParameterState extends ConcreteReferenceTypeParameterState {

  private AbstractValue abstractValue;
  private DynamicType dynamicType;

  public ConcreteClassTypeParameterState(InFlow inFlow) {
    this(AbstractValue.bottom(), DynamicType.bottom(), SetUtils.newHashSet(inFlow));
  }

  public ConcreteClassTypeParameterState(AbstractValue abstractValue, DynamicType dynamicType) {
    this(abstractValue, dynamicType, Collections.emptySet());
  }

  public ConcreteClassTypeParameterState(
      AbstractValue abstractValue, DynamicType dynamicType, Set<InFlow> inFlow) {
    super(inFlow);
    this.abstractValue = abstractValue;
    this.dynamicType = dynamicType;
    assert !isEffectivelyBottom() : "Must use BottomClassTypeParameterState instead";
    assert !isEffectivelyUnknown() : "Must use UnknownParameterState instead";
  }

  @Override
  public ParameterState clearInFlow() {
    if (hasInFlow()) {
      if (abstractValue.isBottom()) {
        assert dynamicType.isBottom();
        return bottomClassTypeParameter();
      }
      internalClearInFlow();
    }
    assert !isEffectivelyBottom();
    return this;
  }

  @Override
  public AbstractValue getAbstractValue(AppView<AppInfoWithLiveness> appView) {
    if (getDynamicType().getNullability().isDefinitelyNull()) {
      assert abstractValue.isNull() || abstractValue.isUnknown();
      return appView.abstractValueFactory().createUncheckedNullValue();
    }
    return abstractValue;
  }

  @Override
  public DynamicType getDynamicType() {
    return dynamicType;
  }

  @Override
  public Nullability getNullability() {
    return getDynamicType().getNullability();
  }

  @Override
  public ConcreteParameterStateKind getKind() {
    return ConcreteParameterStateKind.CLASS;
  }

  @Override
  public boolean isClassParameter() {
    return true;
  }

  @Override
  public ConcreteClassTypeParameterState asClassParameter() {
    return this;
  }

  @Override
  public boolean isEffectivelyBottom() {
    return abstractValue.isBottom() && dynamicType.isBottom() && !hasInFlow();
  }

  @Override
  public boolean isEffectivelyUnknown() {
    return abstractValue.isUnknown() && dynamicType.isUnknown();
  }

  @Override
  public ParameterState mutableCopy() {
    return new ConcreteClassTypeParameterState(abstractValue, dynamicType, copyInFlow());
  }

  @Override
  public ParameterState mutableJoin(
      AppView<AppInfoWithLiveness> appView,
      ConcreteReferenceTypeParameterState parameterState,
      DexType parameterType,
      Action onChangedAction) {
    assert parameterType.isClassType();
    boolean abstractValueChanged =
        mutableJoinAbstractValue(appView, parameterState.getAbstractValue(appView), parameterType);
    boolean dynamicTypeChanged =
        mutableJoinDynamicType(appView, parameterState.getDynamicType(), parameterType);
    if (isEffectivelyUnknown()) {
      return unknown();
    }
    boolean inFlowChanged = mutableJoinInFlow(parameterState);
    if (widenInFlow(appView)) {
      return unknown();
    }
    if (abstractValueChanged || dynamicTypeChanged || inFlowChanged) {
      onChangedAction.execute();
    }
    return this;
  }

  @Override
  public ParameterState mutableJoin(
      AppView<AppInfoWithLiveness> appView,
      ConcreteReferenceTypeFieldState fieldState,
      DexType parameterType,
      Action onChangedAction) {
    assert parameterType.isClassType();
    boolean abstractValueChanged =
        mutableJoinAbstractValue(appView, fieldState.getAbstractValue(), parameterType);
    boolean dynamicTypeChanged =
        mutableJoinDynamicType(appView, fieldState.getDynamicType(), parameterType);
    if (isEffectivelyUnknown()) {
      return unknown();
    }
    boolean inFlowChanged = mutableJoinInFlow(fieldState.getInFlow());
    if (widenInFlow(appView)) {
      return unknown();
    }
    if (abstractValueChanged || dynamicTypeChanged || inFlowChanged) {
      onChangedAction.execute();
    }
    return this;
  }

  private boolean mutableJoinAbstractValue(
      AppView<AppInfoWithLiveness> appView,
      AbstractValue otherAbstractValue,
      DexType parameterType) {
    AbstractValue oldAbstractValue = abstractValue;
    abstractValue =
        appView
            .getAbstractValueParameterJoiner()
            .join(abstractValue, otherAbstractValue, parameterType);
    return !abstractValue.equals(oldAbstractValue);
  }

  private boolean mutableJoinDynamicType(
      AppView<AppInfoWithLiveness> appView, DynamicType otherDynamicType, DexType parameterType) {
    DynamicType oldDynamicType = dynamicType;
    DynamicType joinedDynamicType = dynamicType.join(appView, otherDynamicType);
    DynamicType widenedDynamicType =
        WideningUtils.widenDynamicNonReceiverType(appView, joinedDynamicType, parameterType);
    dynamicType = widenedDynamicType;
    return !dynamicType.equals(oldDynamicType);
  }
}
