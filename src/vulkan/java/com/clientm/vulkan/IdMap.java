package com.clientm.vulkan;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenGL object names (buffers, textures, programs...) mapped to their objects. The names are small numbers handed
 * out in order, so a growing array does the job of a HashMap without boxing an Integer on every lookup, which the
 * draw path does millions of times a minute.
 */
final class IdMap<V> {

    private Object[] items = new Object[256];
    private int size;

    @SuppressWarnings("unchecked")
    V get(int id) {
        return id >= 0 && id < items.length ? (V) items[id] : null;
    }

    boolean containsKey(int id) {
        return get(id) != null;
    }

    V put(int id, V value) {
        if (id < 0) throw new IllegalArgumentException("negative id " + id);
        if (id >= items.length) {
            int n = items.length;
            while (n <= id) n *= 2;
            items = java.util.Arrays.copyOf(items, n);
        }
        V old = get(id);
        items[id] = value;
        if (old == null && value != null) size++;
        if (old != null && value == null) size--;
        return old;
    }

    V remove(int id) {
        V old = get(id);
        if (old != null) {
            items[id] = null;
            size--;
        }
        return old;
    }

    int size() {
        return size;
    }

    /** A snapshot of the objects (for rare walks such as freeing everything at shutdown). */
    @SuppressWarnings("unchecked")
    List<V> values() {
        List<V> list = new ArrayList<>(size);
        for (Object o : items) if (o != null) list.add((V) o);
        return list;
    }
}
