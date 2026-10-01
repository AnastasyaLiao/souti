#!/bin/bash
# 焦点框验证：进拍照页 -> 点几个不同位置 -> 每次连拍 4 张（框只活 2 秒），最后收诊断日志
A="$HOME/Library/Android/sdk/platform-tools/adb"
S="-s M60P20250300777"
OUT=/Users/sammy/Documents/Programming/souti/shots
mkdir -p "$OUT"
$A $S logcat -c
$A $S shell am start -n com.souti.ai/.MainActivity >/dev/null 2>&1
sleep 2
$A $S shell input tap 886 334
sleep 4
burst() {
  local x=$1 y=$2 tag=$3
  $A $S shell input tap "$x" "$y"
  for i in 1 2 3 4; do
    $A $S exec-out screencap -p > "$OUT/v13_${tag}_$i.png"
  done
  sleep 1
}
burst 250 120 t1
burst 600 300 t2
burst 420 200 t3
$A $S logcat -d -s PhotoDiag:* Takeover:* > /tmp/focus_log.txt 2>&1
tail -40 /tmp/focus_log.txt
