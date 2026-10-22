package io.gpsz.zygote;

public final class IdentityMapTest {
    static final class Key {
        final int v;
        static int equalsCalls, hashCalls;

        Key(int v) {
            this.v = v;
        }

        @Override
        public boolean equals(Object o) {
            equalsCalls++;
            throw new IllegalStateException("equals dipanggil");
        }

        @Override
        public int hashCode() {
            hashCalls++;
            throw new IllegalStateException("hashCode dipanggil");
        }
    }

    static void check(boolean ok, String name) {
        if (!ok) {
            System.out.println("FAIL " + name);
            System.exit(1);
        }
        System.out.println("ok   " + name);
    }

    public static void main(String[] args) throws Exception {
        WeakIdentityMap<String> m = new WeakIdentityMap<>();
        Key a = new Key(1), b = new Key(1);
        check(m.get(a) == null, "kosong mengembalikan null");
        m.put(a, "A");
        m.put(b, "B");
        check("A".equals(m.get(a)) && "B".equals(m.get(b)), "kunci dibedakan lewat identitas");
        m.put(a, "A2");
        check("A2".equals(m.get(a)) && m.size() == 2, "put ulang menimpa nilai tanpa menambah entri");
        check(Key.equalsCalls == 0 && Key.hashCalls == 0, "equals dan hashCode kunci tidak pernah dipanggil");
        Key c = new Key(3);
        m.put(c, "C");
        c = null;
        int n = 3;
        for (int i = 0; i < 50 && n != 2; i++) {
            System.gc();
            Thread.sleep(20);
            n = m.size();
        }
        check(n == 2, "entri kunci yang sudah di-GC dibersihkan");
        check(Key.equalsCalls == 0 && Key.hashCalls == 0, "tetap tanpa panggilan equals dan hashCode setelah GC");
    }
}
