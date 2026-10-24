package io.gpsz.zygote;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class Check {
    private static int fail;
    private static final String KEY = Config.KEY;

    private static void expect(String what, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) fail++;
    }

    private static Method find(Class<?> c, String name, Class<?>... ps) throws Exception {
        return c.getDeclaredMethod(name, ps);
    }

    private static MatrixCursor rows(String... names) {
        MatrixCursor c = new MatrixCursor(new String[]{"name", "value"});
        for (String n : names) c.addRow(new Object[]{n, "v-" + n});
        return c;
    }

    private static void reset() {
        LsNative.HOOKS.clear();
    }

    public static void main(String[] args) throws Throwable {
    ConfigNative.ensureLoaded(); LocationNative.ensureLoaded();
        System.out.println("[pemasangan]");
        reset();
        LsNative.ready = true;
        LsNative.set = true;
        SettingsGuard.install();
        int n = LsNative.HOOKS.size();
        expect("hook terpasang lewat LSPlant (" + n + ")", n >= 12);

        ContentResolver cr = new ContentResolver();
        Settings.NameValueCache cache = new Settings.NameValueCache();
        Method getStr = find(Settings.NameValueCache.class, "getStringForUser", ContentResolver.class, String.class, int.class);
        Method qCr = find(ContentResolver.class, "query", Uri.class, String[].class, Bundle.class, Object.class);
        Method callCr = find(ContentResolver.class, "call", Uri.class, String.class, String.class, Bundle.class);

        System.out.println("[NameValueCache]");
        expect("getStringForUser(kunci modul) -> null", LsNative.dispatch(getStr, cache, cr, KEY, 0) == null);
        expect("getStringForUser(kunci lain) asli", "orig:foo".equals(LsNative.dispatch(getStr, cache, cr, "foo", 0)));
        Config.READING.set(Boolean.TRUE);
        expect("getStringForUser saat Config membaca: lewat", ("orig:" + KEY).equals(LsNative.dispatch(getStr, cache, cr, KEY, 0)));
        Config.READING.remove();
        Settings.NameValueCache.prefix.put(KEY, "1");
        Settings.NameValueCache.prefix.put("bar", "2");
        Object map = LsNative.dispatch(find(Settings.NameValueCache.class, "getStringsForPrefix", ContentResolver.class, String.class, List.class),
                cache, cr, "p", new ArrayList<String>());
        expect("getStringsForPrefix: kunci modul dibuang, lain tetap", map instanceof Map && !((Map<?, ?>) map).containsKey(KEY) && ((Map<?, ?>) map).containsKey("bar"));

        System.out.println("[query]");
        ContentResolver.next = rows(KEY, "a", "b");
        Object r = LsNative.dispatch(qCr, cr, Uri.settings("global"), null, null, null);
        MatrixCursor mc = (MatrixCursor) r;
        expect("query settings: baris kunci modul disaring", mc.rows.size() == 2 && !mc.rows.get(0)[0].equals(KEY) && !mc.rows.get(1)[0].equals(KEY));
        ContentResolver.next = rows(KEY);
        r = LsNative.dispatch(qCr, cr, Uri.settings(KEY), null, null, null);
        expect("query URI kunci modul: kursor kosong dengan kolom sama", r instanceof MatrixCursor && ((MatrixCursor) r).rows.isEmpty() && ((MatrixCursor) r).getColumnCount() == 2);
        MatrixCursor same = rows("a", KEY);
        ContentResolver.next = same;
        r = LsNative.dispatch(qCr, cr, new Uri("other", "x"), null, null, null);
        expect("query authority lain: tidak disentuh", r == same && same.rows.size() == 2);
        Bundle qa = new Bundle();
        qa.put(ContentResolver.QUERY_ARG_SQL_SELECTION, KEY);
        ContentResolver.next = rows(KEY, "z");
        r = LsNative.dispatch(qCr, cr, Uri.settings("global"), null, qa, null);
        expect("query dengan selection berisi kunci: kosong", ((MatrixCursor) r).rows.isEmpty());
        ContentResolver.next = rows(KEY, "z");
        Config.READING.set(Boolean.TRUE);
        r = LsNative.dispatch(qCr, cr, Uri.settings("global"), null, null, null);
        expect("query saat Config membaca: lewat apa adanya", ((MatrixCursor) r).rows.size() == 2);
        Config.READING.remove();
        MatrixCursor c2 = rows(KEY, "q");
        ContentResolver.next = c2;
        r = LsNative.dispatch(find(ContentProviderClient.class, "query", Uri.class, String[].class, Bundle.class, Object.class),
                new ContentProviderClient(), Uri.settings("global"), null, null, null);
        expect("ContentProviderClient.query juga disaring", ((MatrixCursor) r).rows.size() == 1);

        System.out.println("[call]");
        Bundle res = new Bundle();
        res.put("x", "y");
        ContentResolver.nextCall = res;
        r = LsNative.dispatch(callCr, cr, Uri.settings("x"), "GET_global", KEY, null);
        expect("call GET_ kunci modul: Bundle kosong", r instanceof Bundle && ((Bundle) r).isEmpty() && r != res);
        r = LsNative.dispatch(callCr, cr, Uri.settings("x"), "GET_global", "foo", null);
        expect("call GET_ kunci lain: asli", r == res);
        Bundle list = new Bundle();
        list.put(KEY, "1");
        list.put("keep", "2");
        ContentResolver.nextCall = list;
        r = LsNative.dispatch(callCr, cr, Uri.settings("x"), "LIST_global", "p", null);
        expect("call LIST_: kunci modul dibuang dari hasil", r == list && !list.keySet().contains(KEY) && list.keySet().contains("keep"));
        ContentResolver.nextCall = res;
        expect("ContentProviderClient.call (tanpa URI): tidak disentuh, perilaku sama dengan jalur lama",
                LsNative.dispatch(find(ContentProviderClient.class, "call", String.class, String.class, Bundle.class), new ContentProviderClient(), "PUT_global", KEY, null) == res);
        Class<?> proxy = Class.forName("android.content.ContentProviderProxy");
        expect("ContentProviderProxy.call(authority settings, kunci modul): kosong",
                ((Bundle) LsNative.dispatch(find(proxy, "call", String.class, String.class, String.class, Bundle.class), proxy.getDeclaredConstructor().newInstance(),
                        "settings", "GET_global", KEY, null)).isEmpty());

        System.out.println("[observer]");
        ContentObserver obs = new ContentObserver(null) { };
        Method dc = find(ContentObserver.class, "dispatchChange", boolean.class, Uri.class);
        ContentObserver.dispatched = 0;
        LsNative.dispatch(dc, obs, false, Uri.settings(KEY));
        expect("dispatchChange(URI kunci modul) dibuang", ContentObserver.dispatched == 0);
        LsNative.dispatch(dc, obs, false, Uri.settings("other"));
        expect("dispatchChange(URI lain) diteruskan", ContentObserver.dispatched == 1);
        Method dcs = find(ContentObserver.class, "dispatchChange", boolean.class, Collection.class, int.class);
        Uri keep = Uri.settings("keepme");
        LsNative.dispatch(dcs, obs, false, new ArrayList<>(Arrays.asList(Uri.settings(KEY), keep)), 0);
        expect("dispatchChange(koleksi): URI kunci dibuang, argumen diganti sebelum method asli",
                ContentObserver.dispatched == 2 && ContentObserver.lastUris instanceof List && ((List<?>) ContentObserver.lastUris).size() == 1 && ((List<?>) ContentObserver.lastUris).get(0) == keep);
        ContentObserver.dispatched = 0;
        LsNative.dispatch(dcs, obs, false, new ArrayList<>(Arrays.asList(Uri.settings(KEY))), 0);
        expect("dispatchChange(koleksi hanya kunci modul) dibuang", ContentObserver.dispatched == 0);
        Method reg = find(ContentResolver.class, "registerContentObserver", Uri.class, boolean.class, ContentObserver.class);
        ContentResolver.registered = 0;
        LsNative.dispatch(reg, cr, Uri.settings(KEY), false, obs);
        expect("registerContentObserver(URI kunci modul) dibuang", ContentResolver.registered == 0);
        LsNative.dispatch(reg, cr, Uri.settings("other"), false, obs);
        expect("registerContentObserver(URI lain) diteruskan", ContentResolver.registered == 1);

        System.out.println("[ProcessBuilder.start]");
        Method start = find(ProcessBuilder.class, "start");
        boolean denied = false;
        try {
            LsNative.dispatch(start, new ProcessBuilder("settings", "get", "global", KEY));
        } catch (IOException e) {
            denied = e.getMessage().contains("error=13");
        }
        expect("perintah 'settings get global <kunci>' ditolak IOException (error=13)", denied);
        Process p = (Process) LsNative.dispatch(start, new ProcessBuilder("true"));
        p.waitFor();
        expect("perintah biasa berjalan", p.exitValue() == 0);

        System.out.println("[location_mode]");
        Method gi = find(Settings.Secure.class, "getInt", ContentResolver.class, String.class);
        Method gs = find(Settings.Secure.class, "getString", ContentResolver.class, String.class);
        Config.enabled = false;
        expect("config mati: getInt asli", Integer.valueOf(0).equals(LsNative.dispatch(gi, null, cr, "location_mode")));
        Config.enabled = true;
        Config.pos = new Config.Pos(1, 2);
        expect("aktif: getInt(location_mode) -> 3 (Integer)", Integer.valueOf(3).equals(LsNative.dispatch(gi, null, cr, "location_mode")));
        expect("aktif: getString(location_mode) -> \"3\"", "3".equals(LsNative.dispatch(gs, null, cr, "location_mode")));
        expect("aktif: kunci lain asli", "orig".equals(LsNative.dispatch(gs, null, cr, "other")));
        Config.enabled = false;

        System.out.println("[tanpa jalur cadangan]");
        reset();
        LsNative.set = false;
        SettingsGuard.install();
        expect("disable_ls_set: tidak ada hook sama sekali", LsNative.HOOKS.isEmpty());
        reset();
        LsNative.set = true;
        LsNative.ready = false;
        SettingsGuard.install();
        expect("LSPlant tidak siap: tidak ada hook, install tidak melempar", LsNative.HOOKS.isEmpty());
        reset();
        LsNative.ready = true;
        LsNative.loc = false;
        SettingsGuard.install();
        expect("disable_ls_loc tidak memengaruhi SettingsGuard", LsNative.HOOKS.size() >= 12);

        System.out.println(fail == 0 ? "SEMUA LOLOS" : fail + " GAGAL");
        System.exit(fail == 0 ? 0 : 1);
    }
}
