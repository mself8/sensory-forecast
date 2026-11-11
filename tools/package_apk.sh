#!/usr/bin/env bash
# 제출용 release APK를 만들어 apk/팀명_앱이름_release.apk로 둡니다.
# 쓰기: tools/package_apk.sh <팀명> <앱이름>
# 공식 규칙: 저장소 apk/ 폴더에 "팀명_앱이름_release.apk", 실행 가능한 release 버전
set -euo pipefail
TEAM=${1:?팀명}
APP=${2:?앱이름}
cd "$(dirname "$0")/.."
./gradlew -q assembleRelease
OUT="apk/${TEAM}_${APP}_release.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
ls -la "$OUT"
echo "README와 기획서 칸 7의 APK 이름도 ${TEAM}_${APP}_release.apk로 바꿔 주세요."
