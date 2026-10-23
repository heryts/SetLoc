package io.gpsz.zygote;

import java.io.File;
import java.nio.file.Files;

public final class Check {
    private static int fail;

    private static void expect(String what, boolean cond) {
        System.out.println((cond ? "  ok   " : "  FAIL ") + what);
        if (!cond) fail++;
    }

    private static File dir;

    private static File f(String n) {
        return new File(dir, n);
    }

    private static int arm() {
        return SysGuard.arm(f(SysGuard.ENABLE), f(SysGuard.PENDING), f(SysGuard.BLOCKED));
    }

    private static void touch(String n) throws Exception {
        f(n).createNewFile();
    }

    private static void clean() {
        for (String n : new String[]{SysGuard.ENABLE, SysGuard.PENDING, SysGuard.BLOCKED}) f(n).delete();
    }

    public static void main(String[] args) throws Throwable {
        dir = Files.createTempDirectory("sysguard").toFile();
        try {
            System.out.println("[tanpa opt-in]");
            clean();
            expect("enable_ls_system tidak ada -> OFF", arm() == SysGuard.OFF);
            touch(SysGuard.PENDING);
            touch(SysGuard.BLOCKED);
            expect("sisa penanda tanpa opt-in tetap OFF dan tidak diubah", arm() == SysGuard.OFF && f(SysGuard.PENDING).exists());

            System.out.println("[penanda ditulis post-fs-data, dibaca premain]");
            clean();
            touch(SysGuard.ENABLE);
            expect("opt-in tanpa pending (post-fs-data belum jalan) -> FAILED", arm() == SysGuard.FAILED);
            expect("premain tidak menulis apa pun", !f(SysGuard.PENDING).exists() && !f(SysGuard.BLOCKED).exists());
            touch(SysGuard.PENDING);
            expect("opt-in + pending -> ARMED", arm() == SysGuard.ARMED);
            expect("membaca berulang tidak mengubah file", arm() == SysGuard.ARMED && f(SysGuard.PENDING).exists());

            System.out.println("[blokir]");
            touch(SysGuard.BLOCKED);
            expect("blokir menang atas pending -> BLOCKED_NOW", arm() == SysGuard.BLOCKED_NOW);
            f(SysGuard.PENDING).delete();
            expect("blokir bertahan tanpa pending", arm() == SysGuard.BLOCKED_NOW);
            f(SysGuard.BLOCKED).delete();
            touch(SysGuard.PENDING);
            expect("setelah reset + pending baru -> ARMED", arm() == SysGuard.ARMED);
            f(SysGuard.ENABLE).delete();
            expect("opt-in dicabut -> OFF", arm() == SysGuard.OFF);

            System.out.println("[gagal baca: gagal tertutup]");
            clean();
            touch(SysGuard.ENABLE);
            expect("File null tidak melempar -> FAILED", SysGuard.arm(f(SysGuard.ENABLE), null, f(SysGuard.BLOCKED)) == SysGuard.FAILED);
            expect("enable null tidak melempar -> FAILED", SysGuard.arm(null, f(SysGuard.PENDING), f(SysGuard.BLOCKED)) == SysGuard.FAILED);
        } finally {
            for (File x : dir.listFiles()) x.delete();
            dir.delete();
        }
        System.out.println(fail == 0 ? "semua lulus" : fail + " gagal");
        if (fail != 0) System.exit(1);
    }
}
