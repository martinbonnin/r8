// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.errors.Unreachable;
import java.util.function.Consumer;

/**
 * Similar to a {@link Consumer} but throws a single {@link Throwable}.
 *
 * @param <T> the type of the input
 * @param <E> the type of the {@link Throwable}
 */
@FunctionalInterface
public interface ThrowingConsumer<T, E extends Throwable> {
  void accept(T t) throws E;

  default void acceptWithRuntimeException(T t) {
    try {
      accept(t);
    } catch (Throwable throwable) {
      RuntimeException runtimeException =
          throwable instanceof RuntimeException
              ? (RuntimeException) throwable
              : new RuntimeException(throwable);
      throw runtimeException;
    }
  }

  static <T, E extends Throwable> ThrowingConsumer<T, E> unreachable() {
    return t -> {
      throw new Unreachable();
    };
  }
}
