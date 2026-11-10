# car

Pleos SDK를 부르는 코드를 둡니다. `core/CarPorts.kt`의 약속을 SDK로 채웁니다.

- `PleosRoadEventSource`: NaviHelper의 `onCautionPointInfo`, `onTBTInfo`, `onReRouteEnded`를 `RoadEvent`로 바꿉니다.
- `DemoRoadEventSource`: 내비 없이 정해진 순서로 사건을 흘려보내는 시험용 입력입니다.
- `PleosAnnouncer`: TextToSpeech로 말하고, 안드로이드 신호음으로 효과음을 냅니다.
- `PleosCabinControl`: Vehicle의 `getWindow()`, `getHVAC()`, `getLight()`로 창문 · 내기 순환 · 바람 · 조명을 바꿉니다.

클래스 이름, 권한, API 형식은 [SDK 참조](../../../../../../../docs/sdk/README.md)에 있습니다.
