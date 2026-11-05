#!/data/data/com.termux/files/usr/bin/bash
# Rokid DeX: a second (virtual) screen on the Fold that Samsung DeX runs on, streamed to the
# glasses. Uses scrcpy-server (Genymobile, Apache-2.0) started through the phone's own ADB
# (Wireless debugging, paired once), plus a socat relay so Rokid Screen on the glasses can
# connect over the hotspot.
#
#   rokid-dex.sh pair <ip:port> <code>   one-time, from Developer options > Wireless debugging
#   rokid-dex.sh connect <ip:port>       the "IP address & port" shown on the Wireless debugging page
#   rokid-dex.sh start [WxH/dpi]         default 640x480/120
#   rokid-dex.sh stop
#   rokid-dex.sh status
set -u
DIR=$HOME/rokid-dex
JAR=$DIR/scrcpy-server-v5.0.1
VER=5.0.1
LOCAL=27183          # adb forward on the phone's loopback
RELAY=27184          # what the glasses connect to, on the hotspot address
DISC=7072            # UDP: glasses ask "RMDEX?", we answer "RMDEX 27184"
LOG=$DIR/server.log
PIDS=$DIR/pids

case "${1:-}" in
  pair)
    adb pair "$2" "$3" ;;
  connect)
    adb connect "$2" && echo "$2" > $DIR/adb-address ;;
  start)
    SIZE="${2:-640x480/120}"
    adb devices | grep -q "device$" || { ADDR=$(cat $DIR/adb-address 2>/dev/null); [ -n "$ADDR" ] && adb connect "$ADDR" >/dev/null; }
    adb devices | grep -q "device$" || { echo "No ADB connection. Developer options > Wireless debugging must be ON; then: rokid-dex.sh connect <ip:port>"; exit 1; }
    "$0" stop >/dev/null 2>&1
    adb push "$JAR" /data/local/tmp/scrcpy-server.jar >/dev/null
    adb forward tcp:$LOCAL localabstract:scrcpy >/dev/null
    # Loop: scrcpy-server exits when the glasses disconnect; start it again for the next time.
    # The pid file must exist before the loop looks for it (it is the loop's stop signal).
    : > $PIDS
    (
      while [ -f $PIDS ]; do
        adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server $VER \
          tunnel_forward=true audio=false control=false video_codec=h264 max_size=640 video_bit_rate=2000000 max_fps=30 \
          new_display="$SIZE" send_device_meta=false send_dummy_byte=false send_stream_meta=true send_frame_meta=true \
          stay_awake=true cleanup=true log_level=info >> $LOG 2>&1
        sleep 1
      done
    ) &
    echo "$!" >> $PIDS
    socat TCP-LISTEN:$RELAY,bind=0.0.0.0,reuseaddr,fork TCP:127.0.0.1:$LOCAL >> $LOG 2>&1 &
    echo "$!" >> $PIDS
    # Answers the glasses' "RMDEX?" broadcast so they find this phone on any shared Wi-Fi, not only the hotspot.
    socat UDP-RECVFROM:$DISC,reuseaddr,fork SYSTEM:"echo RMDEX $RELAY" >> $LOG 2>&1 &
    echo "$!" >> $PIDS
    sleep 2
    IP=$(ip -4 addr show 2>/dev/null | grep -oE "inet (192\.168|10\.)[0-9.]+" | grep -v "inet 127" | head -1 | cut -d' ' -f2)
    echo "DeX screen $SIZE is up. Glasses connect to ${IP:-<hotspot ip>}:$RELAY (Rokid Screen does this by itself)."
    echo "Log: $LOG" ;;
  stop)
    if [ -f $PIDS ]; then
      P=$(cat $PIDS); rm -f $PIDS
      kill $P 2>/dev/null
    fi
    pkill -f "TCP-LISTEN:$RELAY" 2>/dev/null
    pkill -f "UDP-RECVFROM:$DISC" 2>/dev/null
    adb shell pkill -f com.genymobile.scrcpy.Server 2>/dev/null
    adb forward --remove tcp:$LOCAL 2>/dev/null
    echo "DeX screen stopped." ;;
  status)
    adb devices | tail -n +2 | grep . || echo "adb: not connected"
    [ -f $PIDS ] && echo "DeX: running (relay :$RELAY)" || echo "DeX: stopped"
    tail -5 $LOG 2>/dev/null ;;
  *)
    sed -n 2,12p "$0" ;;
esac
