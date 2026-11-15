#!/usr/bin/env bash
# 시험 주행을 에뮬레이터 화면, 가상 콘솔, 앱 기록으로 함께 녹화합니다.
# 시험 주행은 두 구간입니다. 남양주 화도읍 맷돌로를 시속 30km로 달려 과속방지턱 세 개를 넘고, 6초 쉰 뒤
# 서울양양고속도로 월문1터널 구간을 시속 90km로 달려 터널 출구를 500m 지나 멈춥니다.
# 그동안 가상 콘솔을 기어 D, 구간마다 그 속도로 두어 에뮬레이터 왼쪽에 3인칭 주행 화면이 나오게 합니다.
# 쓰기: tools/demo_video/record.sh <녹화 폴더>
# 전제: 에뮬레이터가 adb로 연결돼 있고, adb forward tcp:8088 tcp:8088로 가상 콘솔이 열려 있으며, 앱이 설치돼 있습니다.
# 버튼은 화면 구조에서 글자로 찾아 누릅니다. 지난 이동 줄 수에 따라 위치가 바뀌기 때문입니다.
set -euo pipefail
OUT=${1:?녹화 폴더}
HERE=$(cd "$(dirname "$0")" && pwd)
# 구간마다 끝까지 가는 시간(초)과 사이에 쉬는 시간입니다. 쉬는 시간은 앱 DemoRoadEventSource.PAUSE_MILLIS와 같습니다.
# 화면 녹화와 콘솔 캡처 길이도 여기에 맞춥니다.
read -r LEG1 LEG2 <<<"$(python3 -c "import json; a = json.load(open('$HERE/route/town.json')); b = json.load(open('$HERE/route/wolmun.json')); print(round(a['route'][-1][2] / a['speed_mps'], 1), round(b['route'][-1][2] / b['speed_mps'], 1))")"
PAUSE=6
DRIVE=$(python3 -c "print($LEG1 + $PAUSE + $LEG2)")
ADB="${ADB:-adb} -s ${SERIAL:-emulator-5554}"
B=http://127.0.0.1:8088
mkdir -p "$OUT/frames" && cd "$OUT"

tap_text() {
  # 기어가 D면 왼쪽 주행 화면이 초점을 가져가 화면 구조에 앱이 안 나옵니다. 앱을 다시 불러 초점을 돌립니다.
  $ADB shell am start --user current -n com.sensoryforecast/.MainActivity >/dev/null 2>&1
  sleep 1
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null
  local xy
  xy=$($ADB shell cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
m = re.search(r'text=\"$1\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2)")
  $ADB shell input tap $xy
}
rm -f frames/* capture_ready.txt drive.txt park_at.txt

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
node "$HERE/capture.cjs" "$(python3 -c "print(round($DRIVE + 45))")" > capture.log 2>&1 &
CAP=$!
AUDIO=${AUDIO:-1}
stop_audio() {
  [ "$AUDIO" = 1 ] || return 0
  $ADB shell "pkill -f '[c]om.genymobile.scrcpy.Server'" >/dev/null 2>&1 || true
  wait ${AUD:-} ${CATP:-} 2>/dev/null || true
}
# 중간에 멈추면 녹화와 캡처, 소리 받기를 함께 끕니다.
trap 'kill ${REC:-} $CAP 2>/dev/null || true; stop_audio' EXIT
for _ in $(seq 60); do [ -f capture_ready.txt ] && break; sleep 1; done
# 차 안 소리: scrcpy 서버의 재생 소리 받기로 앱이 하는 말을 받습니다. AUDIO=0이면 건너뜁니다.
# 효과음은 이 방식에 잡히지 않아 compose.py가 앱의 소리 파일을 기록 시각에 넣습니다.
if [ "$AUDIO" = 1 ]; then
  SCRCPY_VER=$("$HERE/audio/prepare.sh")
  $ADB push "$HERE/audio/cache/scrcpy-server.jar" /data/local/tmp/scrcpy-server.jar >/dev/null
  $ADB push "$HERE/audio/cache/sockcat.jar" /data/local/tmp/sockcat.jar >/dev/null
  $ADB shell rm -f /data/local/tmp/cap.raw
  SCID=5f0c0a11
  $ADB shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server $SCRCPY_VER scid=$SCID log_level=info tunnel_forward=true video=false audio=true control=false audio_codec=raw audio_source=playback audio_dup=true raw_stream=true cleanup=false" > audio_server.log 2>&1 &
  AUD=$!
  sleep 2
  $ADB shell "CLASSPATH=/data/local/tmp/sockcat.jar app_process / SockCat scrcpy_$SCID /data/local/tmp/cap.raw" > audio_cat.log 2>&1 &
  CATP=$!
  sleep 1
fi
date +%s.%N > rec_start.txt
$ADB shell screenrecord --size 1280x720 --bit-rate 8000000 --time-limit "$(python3 -c "print(round($DRIVE + 40))")" /sdcard/demo.mp4 &
REC=$!
sleep 4
tap_text "시험 주행 시작"
# 앱 버튼을 먼저 누르고 기어를 넣습니다. 기어가 D면 앱 화면이 잠깁니다.
curl -s -m 5 -X PUT "$B/vehicle/gear?gear=Drive" >/dev/null
sleep 1.5
echo 30 > drive.txt     # capture.cjs가 콘솔 페이지의 속도를 이 값으로 유지합니다. 첫 구간은 시속 30km입니다.
# 동네 길을 다 가면 앱이 쉬는 동안 고속도로 속도로 올립니다. 영상은 쉬는 동안을 잘라 냅니다.
sleep "$(python3 -c "print(round($LEG1 - 1.5 + $PAUSE / 2, 1))")"
echo 90 > drive.txt
sleep "$(python3 -c "print(round($PAUSE / 2 + $LEG2, 1))")"
# 경로 끝에 닿으면 차를 세우고 기어를 P로 둡니다. 주행 중에는 앱 화면이 잠겨 버튼이 눌리지 않기 때문입니다.
# 그다음 예보를 멈추면 부모 평가 줄이 뜹니다.
echo 0 > drive.txt
sleep 0.5
# 페이지는 멈춘 차의 값을 서버로 보내지 않으므로 서버에도 0을 넣습니다.
curl -s -m 5 -X PUT "$B/vehicle/controls?speed_kph=0&accelerator=0&brake=0" >/dev/null
sleep 1.5
date +%s.%N > park_at.txt   # compose.py가 이 시각까지만 "주행 중 화면 잠김" 안내를 띄웁니다.
curl -s -m 5 -X PUT "$B/vehicle/gear?gear=Park" >/dev/null
curl -s -m 5 -X PUT "$B/vehicle/controls?speed_kph=0&accelerator=0&brake=0" >/dev/null
sleep 2
tap_text "멈추기"
# 부모 평가를 하나 눌러 기록이 쌓이는 모습을 남깁니다. 누르기 전과 뒤 화면을 원래 해상도로 찍어 compose.py가 확대해 보여 줍니다.
sleep 2.5
$ADB exec-out screencap -p > rate.png
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null && $ADB shell cat /sdcard/ui.xml > rate_ui.xml
tap_text "4점"
sleep 2.5
$ADB exec-out screencap -p > end.png
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null && $ADB shell cat /sdcard/ui.xml > end_ui.xml
wait $REC; wait $CAP
if [ "$AUDIO" = 1 ]; then
  stop_audio
  $ADB pull /data/local/tmp/cap.raw cap.raw >/dev/null
fi
$ADB logcat -d -v epoch > log.txt
$ADB pull /sdcard/demo.mp4 demo.mp4
mkdir -p emu && rm -f emu/* && ffmpeg -v error -i demo.mp4 -vf fps=15 -q:v 3 emu/e_%05d.jpg

# 차 상태를 처음으로 돌립니다.
curl -s -m 5 -X POST "$B/browser/session?client=record-restore" >/dev/null
curl -s -m 5 -X PUT "$B/vehicle/controls?speed_kph=0&accelerator=0" >/dev/null
curl -s -m 5 -X PUT "$B/vehicle/gear?gear=Park" >/dev/null
curl -s -m 5 -X PUT "$B/vehicle/hvac?recirc=false&fan_speed=3" >/dev/null
curl -s -m 5 -X PUT "$B/vehicle/body?window_rr=0&ambient_color=%2300e5ff" >/dev/null
echo "녹화 끝. 다음: python3 $HERE/compose.py $OUT"
