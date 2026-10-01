#!/bin/bash
# 冷启动 + 各页打开耗时 + 错题本回归（列表/进详情/收藏开关）
A="$HOME/Library/Android/sdk/platform-tools/adb"
S="-s M60P20250300777"
OUT=/Users/sammy/Documents/Programming/souti/shots
sh_() { $A $S shell "$@"; }
sc() { $A $S exec-out screencap -p > "$OUT/$1.png"; }

$A $S logcat -c
sh_ am force-stop com.souti.ai; sleep 1
echo "=== 冷启动首页"
$A $S shell am start -W -n com.souti.ai/.MainActivity 2>&1 | grep -E "TotalTime|WaitTime"
sleep 2
echo "=== 进错题本"
sh_ input tap 887 28; sleep 3; sc v16_book
echo "=== 打开第一条"
sh_ input tap 300 120; sleep 3; sc v16_detail
echo "=== 收藏开关：点两下星星（取消 -> 再收藏）"
sh_ input tap 900 34; sleep 2
sh_ input tap 900 34; sleep 2; sc v16_star
echo "=== 回错题本看条数"
sh_ input tap 765 34; sleep 3; sc v16_book2
echo "=== 页面耗时"
$A $S logcat -d | grep -E "Displayed com.souti" | tail -10
echo "=== 异常"
$A $S logcat -d | grep -E "FATAL|AndroidRuntime" | tail -5
