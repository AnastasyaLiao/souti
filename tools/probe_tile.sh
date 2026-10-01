#!/bin/bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
SER=M60P20250300777
D() { "$ADB" -s $SER shell "$1" 2>&1; }
for xy in "420 100" "640 100" "850 100" "420 290" "640 290" "850 290"; do
  set -- $xy
  D "input keyevent 3" >/dev/null; sleep 1
  "$ADB" -s $SER logcat -c >/dev/null 2>&1
  D "input tap $1 $2" >/dev/null; sleep 3
  st=$("$ADB" -s $SER logcat -d -t 400 2>/dev/null | grep -m2 "START u0")
  fo=$(D "dumpsys window | grep -m1 mCurrentFocus" | tr -d '\r')
  echo "tile($1,$2)"
  echo "   $st"
  echo "   focus=$fo"
done
D "input keyevent 3" >/dev/null
