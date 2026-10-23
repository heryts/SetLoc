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
        return SysGuard.arm(f(SysGuard.DISABLE), f(SysGuard.PENDING), f(SysGuard.BLOCKED));
    }

    private static void touch(String n) throws Exception {
        f(n).createNewFile();
    }

    private static void clean() {
        for (String n : new String[]{SysGuard.DISABLE, SysGuard.PENDING, SysGuard.BLOCKED}) f(n).delete();
    }

    public static void main(String[] args) throws Throwable {
        dir = Files.createTempDirectory("sysguard").toFile();
        try {
            System.out.println("[dimatikan lewat disable_ls_system]");
            clean();
            touch(SysGuard.DISABLE);
            expect("disable_ls_system ada -> OFF", arm() == SysGuard.OFF);
            expect("tidak ada file yang dibuat", !f(SysGuard.PENDING).exists() && !f(SysGuard.BLOCKED).exists());
            touch(SysGuard.PENDING);
            touch(SysGuard.BLOCKED);
            expect("sisa penanda saat dimatikan tetap OFF dan tidak diubah", arm() == SysGuard.OFF && f(SysGuard.PENDING).exists());

            System.out.println("[boot pertama]");
            clean();
            expect("default tanpa penanda -> ARMED", arm() == SysGuard.ARMED);
            expect("penanda pending dibuat sebelum LSPlant dimuat", f(SysGuard.PENDING).exists());
            expect("blokir belum dibuat", !f(SysGuard.BLOCKED).exists());

            System.out.println("[boot kedua tanpa konfirmasi]");
            expect("pending masih ada (boot sebelumnya gagal) -> BLOCKED_NOW", arm() == SysGuard.BLOCKED_NOW);
            expect("blokir permanen dibuat", f(SysGuard.BLOCKED).exists());
            expect("percobaan ketiga juga diblokir", arm() == SysGuard.BLOCKED_NOW);
            f(SysGuard.PENDING).delete();
            expect("blokir bertahan walau pending dihapus watcher", arm() == SysGuard.BLOCKED_NOW);

            System.out.println("[boot kedua setelah konfirmasi]");
            clean();
            expect("boot 1 ARMED", arm() == SysGuard.ARMED);
            f(SysGuard.PENDING).delete();
            expect("pending dihapus (stabil) -> boot 2 ARMED lagi", arm() == SysGuard.ARMED);

            System.out.println("[reset dan off]");
            f(SysGuard.BLOCKED).delete();
            f(SysGuard.PENDING).delete();
            expect("setelah reset -> ARMED", arm() == SysGuard.ARMED);
            touch(SysGuard.DISABLE);
            expect("dimatikan -> OFF", arm() == SysGuard.OFF);

            System.out.println("[gagal menulis: gagal tertutup]");
            clean();
            File ghost = new File(dir, "tidak-ada/.ls_sys_pending");
            expect("tidak bisa membuat pending -> FAILED, bukan ARMED",
                    SysGuard.arm(f(SysGuard.DISABLE), ghost, f(SysGuard.BLOCKED)) == SysGuard.FAILED);
            File asDir = f("pending-dir");
            asDir.mkdir();
            expect("jalur pending berupa folder -> tidak pernah ARMED",
                    SysGuard.arm(f(SysGuard.DISABLE), asDir, f(SysGuard.BLOCKED)) != SysGuard.ARMED);
            f(SysGuard.BLOCKED).delete();
            expect("File null tidak melempar -> FAILED", SysGuard.arm(f(SysGuard.DISABLE), null, f(SysGuard.BLOCKED)) == SysGuard.FAILED);
            File ghostBlocked = new File(dir, "tidak-ada/blocked");
            touch(SysGuard.PENDING);
            expect("pending ada tapi blokir tidak bisa ditulis -> tetap BLOCKED_NOW",
                    SysGuard.arm(f(SysGuard.DISABLE), f(SysGuard.PENDING), ghostBlocked) == SysGuard.BLOCKED_NOW);
        } finally {
            for (File x : dir.listFiles()) x.delete();
            dir.delete();
        }
        System.out.println(fail == 0 ? "semua lulus" : fail + " gagal");
        if (fail != 0) System.exit(1);
    }
}
