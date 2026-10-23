package io.gpsz.zygote;

public class JniRun {
    public static void main(String[] a) throws Throwable {
        boolean want = a[0].equals("native");
        boolean loaded = ConfigNative.ensureLoaded();
        if (want != loaded) {
            System.out.println("FAIL pemuatan library: diharapkan " + want + ", hasil " + loaded);
            System.exit(1);
        }
        boolean cn = Config.cfgNative();
        if (want != cn) {
            System.out.println("FAIL verifikasi Config: diharapkan " + want + ", hasil " + cn);
            System.exit(1);
        }
        Class<?> c = Class.forName("io.gpsz.zygote." + a[1]);
        String[] rest = new String[a.length - 2];
        System.arraycopy(a, 2, rest, 0, rest.length);
        try {
            c.getMethod("main", String[].class).invoke(null, (Object) rest);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
