#!/system/bin/sh
MODDIR=${0%/*}
# bridge.sh ensure menjalankan daemon sinkronisasi (app SetLoc -> settings gpsz_cfg) bila belum hidup
sh "$MODDIR/bridge.sh" ensure >/dev/null 2>&1 &
# Setelah flash modul (+ reboot): system hook = mode HEARTBEAT (gpsz_sys=2). Hanya app di packages/ (Google Maps) yang dispoof;
# sistem cuma mengulang fix asli 1 Hz supaya Maps terus menerima callback. Mode penuh (system-wide) TIDAK pernah jadi default.
# Dipasang SEKALI per flash (penanda .hb_default ikut terhapus saat modul di-flash ulang); setelah itu pilihan manual
# `gpsz.sh system on|off|heartbeat` dihormati sampai flash berikutnya.
(
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
  if [ ! -f "$MODDIR/.hb_default" ]; then
    settings put global gpsz_sys 2 && : > "$MODDIR/.hb_default"
  fi
) >/dev/null 2>&1 &
