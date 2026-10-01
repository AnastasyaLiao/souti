#!/bin/bash
# 定点复测：1) 点预览中心是否会误触发快门  2) 快门进裁剪后裁剪窗是否正常
A="$HOME/Library/Android/sdk/platform-tools/adb"
S="-s M60P20250300777"
OUT=/Users/sammy/Documents/Programming/souti/shots
$A $S logcat -c
$A $S shell input keyevent KEYCODE_BACK; sleep 1
$A $S shell input keyevent KEYCODE_BACK; sleep 1
$A $S shell am start -n com.souti.ai/.MainActivity >/dev/null 2>&1; sleep 2
$A $S shell input tap 886 334; sleep 4
$A $S shell input tap 420 200; sleep 2
$A $S exec-out screencap -p > "$OUT/v13_center_tap.png"
$A $S shell input tap 904 200; sleep 1
for i in 1 2 3; do $A $S exec-out screencap -p > "$OUT/v13_crop_$i.png"; done
$A $S logcat -d -s PhotoDiag:* | tail -20
