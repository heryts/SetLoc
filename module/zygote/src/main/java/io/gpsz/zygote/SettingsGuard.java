package io.gpsz.zygote;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.HookTransformer;
import com.v7878.vmtools.Hooks;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SettingsGuard {
    private static Set<String> KEYS = Collections.emptySet();
    private static final int K_STR = 0, K_QUERY = 1, K_CALL = 2, K_MAP = 3, K_EXEC = 4, K_OBS = 5, K_REG_OBS = 6, K_PCALL = 7;
    private static final int RET = EmulatedStackFrame.RETURN_VALUE_IDX;
    private static final String[] QUERY_STRING_ARGS = {
            ContentResolver.QUERY_ARG_SQL_SELECTION,
            ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
            ContentResolver.QUERY_ARG_SQL_GROUP_BY,
            ContentResolver.QUERY_ARG_SQL_HAVING
    };
    private static final String[] QUERY_ARRAY_ARGS = {
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            ContentResolver.QUERY_ARG_SORT_COLUMNS
    };
    private static final String LOCATION_MODE = "location_mode";

    static void install() {
        if (ZygoteEntry.isSelf()) return;
        KEYS = new HashSet<>(Arrays.asList(
                Config.KEY, Config.KEY_SYS, Config.KEY_BEAT, Config.KEY_TRK,
                Config.KEY_NET, Config.KEY_BT, Config.KEY_LIVE));
        hookCache();
        hookProvider(ContentResolver.class);
        hookProvider(ContentProviderClient.class);
        hookProxy();
        hookObserver();
        hookRegisterObserver();
        hookExec();
        hookLocationMode();
    }

    private static void hookLocationMode() {
        try {
            Class<?> secure = Class.forName("android.provider.Settings$Secure");
            for (Method m : secure.getDeclaredMethods()) {
                if (!m.getName().equals("getInt") && !m.getName().equals("getString")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 2 || ps[0] != ContentResolver.class || ps[1] != String.class) continue;
                final Class<?> ret = m.getReturnType();
                try {
                    Hooks.hook(m, Hooks.EntryPointType.DIRECT, (original, frame) -> {
                        try {
                            if (Config.READING.get() == null && Config.enabled && Config.hasPos() && !Config.sysHeartbeat) {
                                Object key = frame.accessor().getReference(1);
                                if (LOCATION_MODE.equals(key)) {
                                    if (ret == int.class) {
                                        frame.accessor().setValue(RET, 3);
                                        return;
                                    } else if (ret == String.class) {
                                        frame.accessor().setValue(RET, "3");
                                        return;
                                    }
                                }
                            }
                        } catch (Throwable ignored) { }
                        try {
                            Transformers.invokeExactNoChecks(original, frame);
                        } catch (Throwable t) {
                            ZygoteEntry.<RuntimeException>sneaky(t);
                        }
                    }, Hooks.EntryPointType.DIRECT);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static void hookObserver() {
        try {
            for (Method m : ContentObserver.class.getDeclaredMethods()) {
                if (!m.getName().equals("dispatchChange") || Modifier.isAbstract(m.getModifiers())) continue;
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == Uri.class || Collection.class.isAssignableFrom(ps[i])) {
                        put(m, K_OBS, 0, i + 1);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void hookRegisterObserver() {
        try {
            for (Method m : ContentResolver.class.getDeclaredMethods()) {
                if (!m.getName().equals("registerContentObserver") || Modifier.isAbstract(m.getModifiers())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 3 || ps[0] != Uri.class) continue;
                if (!ContentObserver.class.isAssignableFrom(ps[2])) continue;
                put(m, K_REG_OBS, ps.length, 0);
            }
        } catch (Throwable ignored) { }
    }

    private static void hookExec() {
        try {
            for (Method m : ProcessBuilder.class.getDeclaredMethods()) {
                if (!m.getName().equals("start")) continue;
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || Modifier.isStatic(mod)) continue;
                put(m, K_EXEC, 0, 0);
            }
        } catch (Throwable ignored) { }
    }

    private static void hookProxy() {
        try {
            Class<?> c = Class.forName("android.content.ContentProviderProxy");
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || Modifier.isStatic(mod)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (m.getName().equals("query") && Cursor.class.isAssignableFrom(m.getReturnType())) {
                    boolean hasUri = false;
                    for (Class<?> p : ps) if (p == Uri.class) { hasUri = true; break; }
                    if (hasUri) put(m, K_QUERY, ps.length, 0);
                } else if (m.getName().equals("call") && m.getReturnType() == Bundle.class) {
                    List<Integer> s = new ArrayList<>();
                    for (int i = 0; i < ps.length; i++) if (ps[i] == String.class) s.add(i + 1);
                    if (s.size() >= 3) put(m, K_PCALL, s.get(s.size() - 2), s.get(s.size() - 1));
                }
            }
        } catch (Throwable ignored) { }
    }

    private static boolean hiddenUri(Uri u) {
        if (u == null || !"settings".equals(u.getAuthority())) return false;
        String last = u.getLastPathSegment();
        return last != null && KEYS.contains(last);
    }

    private static boolean hiddenAuthority(String a) {
        return "settings".equals(a);
    }

    private static void hookCache() {
        try {
            Class<?> c = Class.forName("android.provider.Settings$NameValueCache");
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 2 || ps[0] != ContentResolver.class) continue;
                if (m.getName().equals("getStringForUser") && ps[1] == String.class && m.getReturnType() == String.class) {
                    put(m, K_STR, 0, 2);
                } else if (m.getName().equals("getStringsForPrefix") && Map.class.isAssignableFrom(m.getReturnType())) {
                    put(m, K_MAP, 0, 2);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static void hookProvider(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (m.getName().equals("query") && ps.length >= 3 && ps[0] == Uri.class && Cursor.class.isAssignableFrom(m.getReturnType())) {
                put(m, K_QUERY, ps.length, 0);
            } else if (m.getName().equals("call") && m.getReturnType() == Bundle.class) {
                List<Integer> s = new ArrayList<>();
                for (int i = 0; i < ps.length; i++) if (ps[i] == String.class) s.add(i + 1);
                if (s.size() >= 2) put(m, K_CALL, s.get(s.size() - 2), s.get(s.size() - 1));
            }
        }
    }

    private static void put(Method m, int kind, int a, int b) {
        try {
            Hooks.hook(m, Hooks.EntryPointType.DIRECT, transformer(kind, a, b), Hooks.EntryPointType.DIRECT);
        } catch (Throwable ignored) { }
    }

    private static boolean reveals(String part, boolean program) {
        if (part == null) return false;
        for (String k : KEYS) if (part.contains(k)) return true;
        String trimmed = part.trim();
        if (!program && trimmed.indexOf(' ') < 0 && trimmed.indexOf('\t') < 0) return false;
        for (String t : trimmed.split("\\s+")) {
            int i = t.lastIndexOf('/');
            String base = i >= 0 ? t.substring(i + 1) : t;
            if (base.equals("settings") || base.equals("content")) return true;
        }
        return false;
    }

    private static String baseName(String s) {
        if (s == null) return "";
        String t = s.trim();
        int i = t.lastIndexOf('/');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    private static boolean isSettingsTool(String base) {
        return base.equals("cmd") || base.equals("dumpsys") || base.equals("settings") || base.equals("content");
    }

    private static boolean revealsArgv(List<String> cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        for (int i = 0; i < cmd.size(); i++) {
            if (reveals(cmd.get(i), i == 0)) return true;
        }
        if (isSettingsTool(baseName(cmd.get(0)))) {
            for (int i = 1; i < cmd.size(); i++) {
                String b = baseName(cmd.get(i));
                if (b.equals("settings") || b.equals("content")) return true;
            }
        }
        return false;
    }

    private static boolean isKey(Object o) {
        return o instanceof String && KEYS.contains(o);
    }

    private static Object valueOf(Bundle b, String k) {
        Serializable v = b.getSerializable(k, Serializable.class);
        return v != null ? v : b.getBundle(k);
    }

    private static boolean bundleMentions(Bundle b) {
        if (b == null) return false;
        try {
            for (String k : b.keySet()) {
                if (KEYS.contains(k)) return true;
                Object v = valueOf(b, k);
                if (v instanceof String && isKey(v)) return true;
                if (v instanceof Bundle && bundleMentions((Bundle) v)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean mentions(Object o) {
        if (o instanceof String) return isKey(o);
        if (o instanceof String[]) {
            for (String s : (String[]) o) if (s != null && KEYS.contains(s)) return true;
            return false;
        }
        if (o instanceof Bundle) {
            try {
                Bundle b = (Bundle) o;
                for (String k : QUERY_STRING_ARGS) {
                    if (isKey(b.getString(k))) return true;
                }
                for (String k : QUERY_ARRAY_ARGS) {
                    if (mentions(b.getStringArray(k))) return true;
                }
                return bundleMentions(b);
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static boolean queryHidden(EmulatedStackFrame frame, int argc) {
        Uri uri = null;
        boolean hit = false;
        for (int i = 1; i <= argc; i++) {
            Object o;
            try { o = frame.accessor().getReference(i); } catch (Throwable t) { continue; }
            if (o instanceof Uri && uri == null) uri = (Uri) o;
            else if (mentions(o)) hit = true;
        }
        if (uri == null || !"settings".equals(uri.getAuthority())) return false;
        String last = uri.getLastPathSegment();
        return hit || (last != null && KEYS.contains(last));
    }

    private static boolean settingsUri(EmulatedStackFrame frame, int argc) {
        for (int i = 1; i <= argc; i++) {
            try {
                Object o = frame.accessor().getReference(i);
                if (o instanceof Uri) return "settings".equals(((Uri) o).getAuthority());
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static Cursor emptyLike(Cursor c) {
        String[] cols = c.getColumnNames();
        try { c.close(); } catch (Throwable ignored) { }
        return new MatrixCursor(cols);
    }

    private static Cursor filterRows(Cursor c) {
        int name = c.getColumnIndex("name");
        if (name < 0) name = c.getColumnIndex("key");
        if (name < 0) return c;
        boolean any = false;
        c.moveToPosition(-1);
        while (c.moveToNext()) {
            String n = c.getString(name);
            if (n != null && KEYS.contains(n)) { any = true; break; }
        }
        c.moveToPosition(-1);
        if (!any) return c;
        int cols = c.getColumnCount();
        MatrixCursor out = new MatrixCursor(c.getColumnNames());
        while (c.moveToNext()) {
            String n = c.getString(name);
            if (n != null && KEYS.contains(n)) continue;
            Object[] row = new Object[cols];
            for (int i = 0; i < cols; i++) {
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_INTEGER: row[i] = c.getLong(i); break;
                    case Cursor.FIELD_TYPE_FLOAT: row[i] = c.getDouble(i); break;
                    case Cursor.FIELD_TYPE_STRING: row[i] = c.getString(i); break;
                    case Cursor.FIELD_TYPE_BLOB: row[i] = c.getBlob(i); break;
                    default: row[i] = null;
                }
            }
            out.addRow(row);
        }
        c.close();
        return out;
    }

    private static void stripMap(Map<?, ?> m) {
        Iterator<?> it = m.keySet().iterator();
        while (it.hasNext()) {
            Object k = it.next();
            if (isKey(k)) it.remove();
        }
    }

    private static void stripBundle(Bundle b) {
        List<String> drop = new ArrayList<>();
        for (String k : b.keySet()) {
            if (KEYS.contains(k)) drop.add(k);
        }
        for (String k : drop) b.remove(k);
        try {
            Map<?, ?> v = b.getSerializable(Settings.NameValueTable.VALUE, HashMap.class);
            if (v != null) stripMap(v);
        } catch (Throwable ignored) { }
        try {
            for (String k : b.keySet()) {
                Object v = valueOf(b, k);
                if (v instanceof Bundle) stripBundle((Bundle) v);
            }
        } catch (Throwable ignored) { }
    }

    private static HookTransformer transformer(final int kind, final int a, final int b) {
        final boolean isCall = kind == K_CALL || kind == K_PCALL;
        return (original, frame) -> {
            if (kind == K_EXEC) {
                boolean deny = false;
                try {
                    Object self = frame.accessor().getReference(0);
                    if (self instanceof ProcessBuilder) {
                        deny = revealsArgv(((ProcessBuilder) self).command());
                    }
                } catch (Throwable ignored) { }
                if (deny) ZygoteEntry.<RuntimeException>sneaky(new IOException("Cannot run program: error=13, Permission denied"));
            } else if (kind == K_OBS) {
                boolean drop = false;
                try {
                    if (!Config.ownObserver(frame.accessor().getReference(0))) {
                        Object o = frame.accessor().getReference(b);
                        if (o instanceof Uri) {
                            drop = hiddenUri((Uri) o);
                        } else if (o instanceof Collection) {
                            List<Uri> keep = new ArrayList<>();
                            boolean cut = false;
                            for (Object e : (Collection<?>) o) {
                                if (e instanceof Uri && hiddenUri((Uri) e)) cut = true;
                                else if (e instanceof Uri) keep.add((Uri) e);
                            }
                            if (cut) {
                                if (keep.isEmpty()) drop = true;
                                else frame.accessor().setValue(b, keep);
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                if (drop) return;
            } else if (kind == K_REG_OBS) {
                try {
                    Object uriObj = frame.accessor().getReference(1);
                    Object obsObj = frame.accessor().getReference(3);
                    if (uriObj instanceof Uri && !Config.ownObserver(obsObj) && hiddenUri((Uri) uriObj)) {
                        return;
                    }
                } catch (Throwable ignored) { }
            }
            if (kind == K_EXEC || kind == K_OBS || kind == K_REG_OBS) {
                try {
                    Transformers.invokeExactNoChecks(original, frame);
                } catch (Throwable t) {
                    ZygoteEntry.<RuntimeException>sneaky(t);
                }
                return;
            }
            final boolean guard = Config.READING.get() == null;
            boolean sUri = false;
            if (guard) {
                try {
                    if (kind == K_STR) {
                        if (isKey(frame.accessor().getReference(b))) {
                            frame.accessor().setValue(RET, null);
                            return;
                        }
                    } else if (isCall) {
                        Object method = frame.accessor().getReference(a);
                        if (method instanceof String) {
                            String mn = (String) method;
                            Object first = frame.accessor().getReference(kind == K_PCALL ? a - 1 : 1);
                            boolean settings = first instanceof Uri
                                    ? hiddenAuthority(((Uri) first).getAuthority())
                                    : first instanceof String && hiddenAuthority((String) first);
                            if (settings) {
                                Object keyArg = frame.accessor().getReference(b);
                                Object extras = null;
                                for (int i = 2; i <= b + 1; i++) {
                                    try {
                                        Object o = frame.accessor().getReference(i);
                                        if (o instanceof Bundle) { extras = o; break; }
                                    } catch (Throwable ignored) { }
                                }
                                boolean keyHit = isKey(keyArg) || (extras instanceof Bundle && bundleMentions((Bundle) extras));
                                if ((mn.startsWith("GET_") || mn.startsWith("PUT_") || mn.startsWith("DELETE_")) && keyHit) {
                                    frame.accessor().setValue(RET, new Bundle());
                                    return;
                                }
                            }
                        }
                    } else if (kind == K_QUERY) {
                        sUri = settingsUri(frame, a);
                    }
                } catch (Throwable ignored) { }
            }
            boolean hide = false;
            if (guard && kind == K_QUERY && sUri) {
                try { hide = queryHidden(frame, a); } catch (Throwable ignored) { }
            }
            try {
                Transformers.invokeExactNoChecks(original, frame);
            } catch (Throwable t) {
                ZygoteEntry.<RuntimeException>sneaky(t);
            }
            if (!guard) return;
            try {
                Object r = frame.accessor().getReference(RET);
                if (r == null) return;
                if (kind == K_QUERY && sUri && r instanceof Cursor) {
                    Cursor c = (Cursor) r;
                    Cursor n = hide ? emptyLike(c) : filterRows(c);
                    if (n != c) frame.accessor().setValue(RET, n);
                } else if (isCall && r instanceof Bundle) {
                    Object method = frame.accessor().getReference(a);
                    if (method instanceof String && ((String) method).startsWith("LIST_")) stripBundle((Bundle) r);
                } else if (kind == K_MAP && r instanceof Map) {
                    stripMap((Map<?, ?>) r);
                }
            } catch (Throwable ignored) { }
        };
    }

    private SettingsGuard() { }
}