# car

Pleos SDK를 부르는 코드를 둡니다. `core/CarPorts.kt`의 약속을 SDK로 채웁니다.

- `PleosRoadEventSource`: NaviHelper의 `onCautionPointInfo`, `onTBTInfo`, `onReRouteEnded`를 `RoadEvent`로 바꿉니다. 터널 안내를 받으면 `onCurrentLocationInfo` 위치로 터널 길이를 찾고, Vehicle 거리계로 출구 사건을 만듭니다.
- `DemoRoadEventSource`: 내비 없이 정해진 순서로 사건을 흘려보내는 시험용 입력입니다.
- `PleosAnnouncer`: TextToSpeech로 말하고, `res/raw`의 소리 파일로 효과음을 냅니다. 소리 파일은 `tools/make_cues.py`가 만듭니다.
- `PleosCabinControl`: Vehicle의 `getWindow()`, `getHVAC()`, `getLight()`로 창문 · 내기 순환 · 바람 · 조명을 바꿉니다.

클래스 이름, 권한, API 형식은 [SDK 참조](../../../../../../../docs/sdk/README.md)에 있습니다.
