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
            System.out.println("[dimatikan]");
            clean();
            touch(SysGuard.DISABLE);
            touch(SysGuard.PENDING);
            expect("disable_ls_system ada -> OFF walau pending ada", arm() == SysGuard.OFF);
            touch(SysGuard.BLOCKED);
            expect("OFF mendahului blokir", arm() == SysGuard.OFF);
            expect("OFF tidak mengubah penanda", f(SysGuard.PENDING).exists() && f(SysGuard.BLOCKED).exists());

            System.out.println("[default hidup, penanda dari daemon]");
            clean();
            expect("tanpa pending -> FAILED, tidak ada file dibuat", arm() == SysGuard.FAILED && !f(SysGuard.PENDING).exists() && !f(SysGuard.BLOCKED).exists());
            touch(SysGuard.PENDING);
            expect("pending dari daemon -> ARMED", arm() == SysGuard.ARMED);
            expect("membaca tidak mengubah penanda", f(SysGuard.PENDING).exists() && !f(SysGuard.BLOCKED).exists());
            expect("dibaca berulang tetap ARMED", arm() == SysGuard.ARMED);

            System.out.println("[blokir]");
            touch(SysGuard.BLOCKED);
            expect("blokir ada -> BLOCKED_NOW walau pending ada", arm() == SysGuard.BLOCKED_NOW);
            f(SysGuard.PENDING).delete();
            expect("blokir bertahan tanpa pending", arm() == SysGuard.BLOCKED_NOW);
            expect("blokir tidak dihapus oleh arm", f(SysGuard.BLOCKED).exists());

            System.out.println("[reset]");
            f(SysGuard.BLOCKED).delete();
            touch(SysGuard.PENDING);
            expect("setelah reset dan pending baru -> ARMED", arm() == SysGuard.ARMED);

            System.out.println("[gagal tertutup]");
            clean();
            File asDir = f("pending-dir");
            asDir.mkdir();
            expect("pending berupa folder tidak melempar", SysGuard.arm(f(SysGuard.DISABLE), asDir, f(SysGuard.BLOCKED)) == SysGuard.ARMED);
            expect("pending null -> FAILED", SysGuard.arm(f(SysGuard.DISABLE), null, f(SysGuard.BLOCKED)) == SysGuard.FAILED);
            expect("disable null -> FAILED", SysGuard.arm(null, f(SysGuard.PENDING), f(SysGuard.BLOCKED)) == SysGuard.FAILED);
            File ghost = new File(dir, "tidak-ada/.ls_sys_pending");
            expect("pending di folder yang tidak ada -> FAILED", SysGuard.arm(f(SysGuard.DISABLE), ghost, f(SysGuard.BLOCKED)) == SysGuard.FAILED);
        } finally {
            for (File x : dir.listFiles()) x.delete();
            dir.delete();
        }
        System.out.println(fail == 0 ? "semua lulus" : fail + " gagal");
        if (fail != 0) System.exit(1);
    }
}
