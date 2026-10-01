#!/bin/bash
# 一轮设备回归：裁剪期摄像头是否真关掉 -> 重拍能否接回来 -> 上滑取消语音 -> 断网挡拍照
A="$HOME/Library/Android/sdk/platform-tools/adb"
S="-s M60P20250300777"
OUT=/Users/sammy/Documents/Programming/souti/shots
d() { $A $S $S_ARGS "$@"; }
sh_() { $A $S shell "$@"; }

$A $S install -r /Users/sammy/Documents/Programming/souti/app/app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1
$A $S logcat -c
sh_ input keyevent KEYCODE_BACK; sleep 1; sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ am start -n com.souti.ai/.MainActivity >/dev/null 2>&1; sleep 2
sh_ input tap 886 334; sleep 4

echo "=== [1] 快门进裁剪，之后 4 秒内不该再有 preview resolution"
sh_ input tap 904 200; sleep 4
$A $S logcat -d -s PhotoDiag:* | tail -12

echo "=== [2] 重拍：摄像头要立刻绑回来"
sh_ input tap 904 293; sleep 3
$A $S logcat -d -s PhotoDiag:* | tail -6

echo "=== [3] 问 AI 页：长按录音后上滑取消"
sh_ input tap 904 200; sleep 1        # 拍一张
sh_ input tap 904 107; sleep 6        # 完成 -> 结果页
sh_ input tap 900 372; sleep 5        # 问 AI
$A $S exec-out screencap -p > "$OUT/v14_chat.png"
sh_ input swipe 400 368 400 120 1600  # 按住输入框 -> 上滑 -> 松手
sleep 2
$A $S exec-out screencap -p > "$OUT/v14_swipe_cancel.png"
echo "=== [4] 回到首页，断网后点拍照搜题"
sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ input keyevent KEYCODE_BACK; sleep 1
sh_ svc wifi disable; sh_ svc data disable; sleep 3
sh_ am start -n com.souti.ai/.MainActivity >/dev/null 2>&1; sleep 2
sh_ input tap 886 334; sleep 2
$A $S exec-out screencap -p > "$OUT/v14_offline_gate.png"
sh_ svc wifi enable; sleep 2
echo "=== 恢复网络：$($A $S shell dumpsys connectivity | grep -m1 'NetworkAgentInfo' || echo pending)"
echo "=== 语音相关日志 ==="
$A $S logcat -d -s AsrDiag:* AskAI:* PhotoDiag:* Net:* | tail -30
