// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils.collections;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Interface that provides mutable access to the implementation of a many-to-one mapping. */
public interface MutableBidirectionalManyToOneMap<K, V> extends BidirectionalManyToOneMap<K, V> {

  void clear();

  V put(K key, V value);

  void put(Iterable<K> key, V value);

  default void putAll(BidirectionalManyToOneMap<K, V> map) {
    map.forEach(this::put);
  }

  default void putAll(Map<K, V> map) {
    map.forEach(this::put);
  }

  V remove(K key);

  void removeAll(Iterable<K> keys);

  Set<K> removeValue(V value);

  default void removeValues(Collection<V> values) {
    values.forEach(this::removeValue);
  }
}
