package io.gpsz.zygote;
final class PackageGuardLs { static int installs; static boolean install() { installs++; return true; } }
