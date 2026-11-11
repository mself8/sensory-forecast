#!/usr/bin/env bash
# 시험 주행을 에뮬레이터 화면, 가상 콘솔, 앱 기록으로 함께 녹화합니다.
# 쓰기: tools/demo_video/record.sh <녹화 폴더>
# 전제: 에뮬레이터가 adb로 연결돼 있고, adb forward tcp:8088 tcp:8088로 가상 콘솔이 열려 있으며, 앱이 설치돼 있습니다.
# 좌표는 화면 2560x1440에서 시작 화면의 "시험 주행 시작"과 "멈추기" 버튼 위치입니다.
set -euo pipefail
OUT=${1:?녹화 폴더}
HERE=$(cd "$(dirname "$0")" && pwd)
ADB="${ADB:-adb} -s ${SERIAL:-emulator-5554}"
B=http://127.0.0.1:8088
mkdir -p "$OUT/frames" && cd "$OUT"
rm -f frames/* capture_ready.txt

$ADB shell am force-stop com.sensoryforecast
$ADB shell am start -W -n com.sensoryforecast/.MainActivity >/dev/null
sleep 2
$ADB exec-out screencap -p > card.png

# 에뮬레이터 시계와 이 컴퓨터 시계의 차이
python3 - > offset.txt <<'PY'
import subprocess, time, os, shlex
adb = shlex.split(os.environ.get("ADB", "adb")) + ["-s", os.environ.get("SERIAL", "emulator-5554")]
vals = []
for _ in range(5):
    a = time.time(); e = float(subprocess.check_output(adb + ["shell", "date", "+%s.%N"]).strip()); b = time.time()
    vals.append(e - (a + b) / 2)
vals.sort(); print(vals[2])
PY

$ADB logcat -c
# 콘솔 페이지는 열릴 때 자기 화면 값을 차에 씁니다. 그래서 페이지를 연 뒤에 시작 상태를 맞춥니다. capture.cjs가 합니다.
node "$HERE/capture.cjs" 128 > capture.log 2>&1 &
CAP=$!
for _ in $(seq 60); do [ -f capture_ready.txt ] && break; sleep 1; done
date +%s.%N > rec_start.txt
$ADB shell screenrecord --size 1280x720 --bit-rate 8000000 --time-limit 120 /sdcard/demo.mp4 &
REC=$!
sleep 4
$ADB shell input tap 2089 726
sleep 108
$ADB shell input tap 2089 774
wait $REC; wait $CAP
$ADB logcat -d -v epoch > log.txt
$ADB pull /sdcard/demo.mp4 demo.mp4
mkdir -p emu && rm -f emu/* && ffmpeg -v error -i demo.mp4 -vf fps=15 -q:v 3 emu/e_%05d.jpg

# 차 상태를 처음으로 돌립니다.
curl -s -m 5 -X POST "$B/browser/session?client=record-restore" >/dev/null
curl -s -m 5 -X PUT "$B/vehicle/hvac?recirc=false&fan_speed=3" >/dev/null
curl -s -m 5 -X PUT "$B/vehicle/body?window_rr=0&ambient_color=%2300e5ff" >/dev/null
echo "녹화 끝. 다음: python3 $HERE/compose.py $OUT"
