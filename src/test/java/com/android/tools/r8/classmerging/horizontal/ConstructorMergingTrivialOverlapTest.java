// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static com.android.tools.r8.utils.codeinspector.Matchers.readsInstanceField;
import static com.android.tools.r8.utils.codeinspector.Matchers.writesInstanceField;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNot.not;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoMethodStaticizing;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.FieldSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;

public class ConstructorMergingTrivialOverlapTest extends HorizontalClassMergingTestBase {

  public ConstructorMergingTrivialOverlapTest(TestParameters parameters) {
    super(parameters);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(Main.class)
        .addHorizontallyMergedClassesInspector(
            inspector ->
                inspector.assertIsCompleteMergeGroup(A.class, B.class).assertNoOtherClassesMerged())
        .enableInliningAnnotations()
        .enableNeverClassInliningAnnotations()
        .enableNoMethodStaticizingAnnotations()
        .setMinApi(parameters)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("7", "42", "13", "print a", "print b")
        .inspect(
            codeInspector -> {
              ClassSubject aClassSubject = codeInspector.clazz(A.class);
              assertThat(aClassSubject, isPresent());
              FieldSubject classIdFieldSubject = classMergerClassIdField(aClassSubject);
              assertThat(classIdFieldSubject, isPresent());

              MethodSubject firstInitSubject = aClassSubject.init("int");
              assertThat(firstInitSubject, isPresent());
              assertThat(firstInitSubject, writesInstanceField(classIdFieldSubject.getDexField()));

              MethodSubject otherInitSubject = aClassSubject.init("int", "byte");
              assertThat(otherInitSubject, isPresent());
              assertThat(otherInitSubject, writesInstanceField(classIdFieldSubject.getDexField()));

              MethodSubject printSubject = getUniqueDispatchBridgeMethod(aClassSubject);
              assertThat(printSubject, isPresent());
              assertThat(printSubject, readsInstanceField(classIdFieldSubject.getDexField()));

              assertThat(codeInspector.clazz(B.class), not(isPresent()));
            });
  }

  @NeverClassInline
  public static class A {

    @NeverInline
    public A() {
      System.out.println(7);
    }

    @NeverInline
    @NoMethodStaticizing
    public void print() {
      System.out.println("print a");
    }
  }

  @NeverClassInline
  public static class B {

    @NeverInline
    public B() {
      this(42);
    }

    @NeverInline
    public B(int x) {
      System.out.println(x);
    }

    @NeverInline
    @NoMethodStaticizing
    public void print() {
      System.out.println("print b");
    }
  }

  public static class Main {
    public static void main(String[] args) {
      A a = new A();
      B b = new B();
      b = new B(13);
      a.print();
      b.print();
    }
  }
}
