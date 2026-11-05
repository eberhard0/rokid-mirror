#!/data/data/com.termux/files/usr/bin/bash
# Rokid DeX: a second (virtual) screen on the Fold that Samsung DeX runs on, streamed to the
# glasses. The work is done by "dexd", a helper living on the phone with shell rights: it is
# armed ONCE PER BOOT through Wireless debugging (needs Wi-Fi at that moment) and from then
# on DeX starts and stops without ADB, on the hotspot or any network. Rokid Mirror's DeX
# buttons talk to dexd directly; this script is the manual way and does the arming.
#
#   rokid-dex.sh pair <ip:port> <code>   one-time, from Developer options > Wireless debugging
#   rokid-dex.sh arm [port]              once per boot: Wireless debugging ON, pass its port (or run "connect" first)
#   rokid-dex.sh start [WxH/dpi]         default 640x480/120 (no ADB needed once armed)
#   rokid-dex.sh stop
#   rokid-dex.sh status
set -u
export PATH=/data/data/com.termux/files/usr/bin:$PATH
DIR=$HOME/rokid-dex
SERVER_JAR=$DIR/scrcpy-server-v5.0.1
DEXD_JAR=$DIR/rokid-dexd.jar
PHONE_DIR=/data/local/tmp/rokid
CONTROL=27185

ctl() { echo "$1" | timeout 3 socat -t 2 - TCP:127.0.0.1:$CONTROL 2>/dev/null; }

case "${1:-}" in
  pair)
    adb pair "$2" "$3" ;;
  connect)
    adb connect "$2" && echo "$2" > "$DIR/adb-address" ;;
  arm)
    if [ -n "${2:-}" ]; then adb connect "127.0.0.1:$2" >/dev/null && echo "127.0.0.1:$2" > "$DIR/adb-address"; fi
    adb devices | grep -q "device$" || { ADDR=$(cat "$DIR/adb-address" 2>/dev/null); [ -n "$ADDR" ] && adb connect "$ADDR" >/dev/null; }
    adb devices | grep -q "device$" || { echo "No ADB: turn Wireless debugging ON (phone on Wi-Fi) and run: rokid-dex.sh arm <port>"; exit 1; }
    [ -f "$DEXD_JAR" ] || { echo "Missing $DEXD_JAR (from the rokid-mirror build)"; exit 1; }
    adb shell "mkdir -p $PHONE_DIR; pkill -f rokidmirror.dexd.Main 2>/dev/null; pkill -f genymobile.scrcpy.Server 2>/dev/null; true"
    adb push "$SERVER_JAR" $PHONE_DIR/scrcpy-server.jar >/dev/null
    adb push "$DEXD_JAR" $PHONE_DIR/dexd.jar >/dev/null
    # nohup + setsid: dexd keeps running after this adb session ends, until the phone reboots.
    adb shell "cd $PHONE_DIR && setsid nohup app_process -Djava.class.path=$PHONE_DIR/dexd.jar / com.eberhard.rokidmirror.dexd.Main >$PHONE_DIR/dexd.out 2>&1 &" </dev/null
    sleep 2
    if [ -n "$(ctl status)" ]; then echo "Armed: dexd is running on the phone until the next reboot. DeX now works on any network."; else echo "dexd did not answer; see: adb shell cat $PHONE_DIR/dexd.out"; exit 1; fi ;;
  start)
    R=$(ctl "start ${2:-640x480/120}")
    [ -n "$R" ] && echo "$R  (Rokid Screen on the glasses finds it by itself)" || { echo "Not armed since the last reboot: rokid-dex.sh arm <wireless-debugging port>"; exit 1; } ;;
  stop)
    R=$(ctl stop); [ -n "$R" ] && echo "$R" || echo "Not armed (nothing running)." ;;
  status)
    R=$(ctl status); [ -n "$R" ] && echo "dexd: $R" || echo "dexd: not armed since the last reboot"
    adb devices 2>/dev/null | tail -n +2 | grep . || echo "adb: not connected (only needed for arm)" ;;
  *)
    sed -n 2,13p "$0" ;;
esac
