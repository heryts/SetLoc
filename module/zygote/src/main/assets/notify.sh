#!/system/bin/sh
case "$3" in
  *_prefs.xml) kill -USR1 "$GPSZ_PID" 2>/dev/null || kill "$PPID" 2>/dev/null ;;
esac
exit 0
