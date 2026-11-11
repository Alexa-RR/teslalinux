/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.util;

import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility methods for dealing with (typically {@code Nullable}) {@link Collection}s (the AOSP
 * class without the throwing-function helpers, which need the internal functional interfaces).
 * @hide
 */
public class CollectionUtils {
    private CollectionUtils() {
    }

    public static <T> boolean isEmpty(Collection<T> cur) {
        return size(cur) == 0;
    }

    public static <T> boolean isEmpty(Map<T, ?> cur) {
        return size(cur) == 0;
    }

    public static int size(Collection<?> cur) {
        return cur != null ? cur.size() : 0;
    }

    public static int size(Map<?, ?> cur) {
        return cur != null ? cur.size() : 0;
    }

    public static <T> List<T> emptyIfNull(List<T> cur) {
        return cur == null ? Collections.emptyList() : cur;
    }

    public static <T> Set<T> emptyIfNull(Set<T> cur) {
        return cur == null ? Collections.emptySet() : cur;
    }

    public static <K, V> Map<K, V> emptyIfNull(Map<K, V> cur) {
        return cur == null ? Collections.emptyMap() : cur;
    }

    public static <T> boolean contains(Collection<T> cur, T item) {
        return cur != null && cur.contains(item);
    }

    public static <T> T firstOrNull(List<T> cur) {
        return isEmpty(cur) ? null : cur.get(0);
    }

    public static <T> T firstOrNull(Collection<T> cur) {
        return isEmpty(cur) ? null : cur.iterator().next();
    }

    public static <T> List<T> add(List<T> cur, T val) {
        if (cur == null || cur == Collections.emptyList()) {
            cur = new ArrayList<>();
        }
        cur.add(val);
        return cur;
    }

    public static <T> List<T> add(List<T> cur, int index, T val) {
        if (cur == null || cur == Collections.emptyList()) {
            cur = new ArrayList<>();
        }
        cur.add(index, val);
        return cur;
    }

    public static <T> Set<T> add(Set<T> cur, T val) {
        if (cur == null || cur == Collections.emptySet()) {
            cur = new ArraySet<>();
        }
        cur.add(val);
        return cur;
    }

    public static <T> List<T> addAll(List<T> cur, Collection<T> val) {
        if (isEmpty(val)) {
            return cur != null ? cur : Collections.emptyList();
        }
        if (cur == null || cur == Collections.emptyList()) {
            cur = new ArrayList<>();
        }
        cur.addAll(val);
        return cur;
    }

    public static <T> List<T> remove(List<T> cur, T val) {
        if (isEmpty(cur)) {
            return Collections.emptyList();
        }
        cur.remove(val);
        return cur;
    }

    public static <T> Set<T> remove(Set<T> cur, T val) {
        if (isEmpty(cur)) {
            return Collections.emptySet();
        }
        cur.remove(val);
        return cur;
    }

    public static <T> List<T> copyOf(List<T> cur) {
        return isEmpty(cur) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(cur));
    }

    public static <T> Set<T> copyOf(Set<T> cur) {
        return isEmpty(cur) ? Collections.emptySet() : Collections.unmodifiableSet(new ArraySet<>(cur));
    }

    public static <T> List<T> toList(Set<T> cur) {
        return isEmpty(cur) ? Collections.emptyList() : new ArrayList<>(cur);
    }

    public static <T> T singleOrNull(List<T> cur) {
        return size(cur) == 1 ? cur.get(0) : null;
    }

    public static <T> List<T> filter(List<T> list, java.util.function.Predicate<? super T> predicate) {
        ArrayList<T> result = null;
        for (int i = 0; i < size(list); i++) {
            T item = list.get(i);
            if (predicate.test(item)) {
                result = add(result, item) instanceof ArrayList ? (ArrayList<T>) add(result, item) : new ArrayList<>(add(result, item));
            }
        }
        return emptyIfNull(result);
    }

    public static <T> boolean any(List<T> items, java.util.function.Predicate<T> predicate) {
        for (int i = 0; i < size(items); i++) {
            if (predicate.test(items.get(i)))
                return true;
        }
        return false;
    }

    public static <T> boolean all(List<T> items, java.util.function.Predicate<T> predicate) {
        for (int i = 0; i < size(items); i++) {
            if (!predicate.test(items.get(i)))
                return false;
        }
        return true;
    }

    public static <T> T find(List<T> items, java.util.function.Predicate<T> predicate) {
        for (int i = 0; i < size(items); i++) {
            T item = items.get(i);
            if (predicate.test(item))
                return item;
        }
        return null;
    }
}
