#!/bin/bash
# 上滑取消语音：首页 -> 错题本 -> 第一条 -> 问 AI -> 按住输入框上滑
A="$HOME/Library/Android/sdk/platform-tools/adb"
S="-s M60P20250300777"
OUT=/Users/sammy/Documents/Programming/souti/shots
sc() { $A $S exec-out screencap -p > "$OUT/$1.png"; }
sh_() { $A $S shell "$@"; }

$A $S logcat -c
sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ am start -n com.souti.ai/.MainActivity >/dev/null 2>&1; sleep 2
sh_ input tap 887 28; sleep 2; sc v15_book
sh_ input tap 300 120; sleep 3; sc v15_result
sh_ input tap 820 370; sleep 4; sc v15_chat
echo "--- 上滑中 / 松手后"
$A $S shell input swipe 400 368 400 140 1800 >/dev/null 2>&1 &
sleep 1.2; sc v15_mid_swipe
wait; sleep 0.8; sc v15_after_cancel
$A $S logcat -d -s AsrDiag:* | tail -10
