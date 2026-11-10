# car

Pleos SDK를 부르는 코드를 두는 자리입니다. `core/CarPorts.kt`의 약속을 SDK로 채웁니다.

- `RoadEventSource`: NaviHelper의 `onCautionPointInfo`, `onTBTInfo`, `onReRouteEnded`를 `RoadEvent`로 바꿉니다.
- `Announcer`: TextToSpeech로 말하고, 효과음을 냅니다.
- `CabinControl`: Vehicle의 `getWindow()`, `getHVAC()`, `getLight()`로 창문 · 내기 순환 · 바람 · 조명을 바꿉니다.

클래스 이름, 권한, API 형식은 [SDK 참조](../../../../../../../docs/sdk/README.md)에 있습니다.
