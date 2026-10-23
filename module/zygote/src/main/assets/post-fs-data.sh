#!/system/bin/sh
MODDIR=${0%/*}
if [ ! -f "$MODDIR/enable_ls_system" ]; then
    [ -f "$MODDIR/ls_system_off" ] && exit 0
    : > "$MODDIR/enable_ls_system"
fi
[ -f "$MODDIR/ls_system_blocked" ] && exit 0
if [ -f "$MODDIR/.ls_sys_pending" ]; then
    : > "$MODDIR/ls_system_blocked"
    rm -f "$MODDIR/.ls_sys_pending"
    exit 0
fi
: > "$MODDIR/.ls_sys_pending"
