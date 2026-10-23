#!/system/bin/sh
MODDIR=${0%/*}
[ -f "$MODDIR/enable_ls_system" ] || exit 0
[ -f "$MODDIR/ls_system_blocked" ] && exit 0
if [ -f "$MODDIR/.ls_sys_pending" ]; then
    : > "$MODDIR/ls_system_blocked"
    rm -f "$MODDIR/.ls_sys_pending"
    exit 0
fi
: > "$MODDIR/.ls_sys_pending"
