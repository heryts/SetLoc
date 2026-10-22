package io.gpsz.zygote;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;

final class WeakIdentityMap<V> {
    private static final class Ref extends WeakReference<Object> {
        private final int hash;

        Ref(Object key, ReferenceQueue<Object> queue) {
            super(key, queue);
            hash = System.identityHashCode(key);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Ref)) return false;
            Object a = get();
            return a != null && a == ((Ref) o).get();
        }
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    private final HashMap<Ref, V> map = new HashMap<>();

    synchronized V get(Object key) {
        purge();
        return map.get(new Ref(key, null));
    }

    synchronized void put(Object key, V value) {
        purge();
        map.put(new Ref(key, queue), value);
    }

    synchronized int size() {
        purge();
        return map.size();
    }

    private void purge() {
        Object r;
        while ((r = queue.poll()) != null) map.remove(r);
    }
}
