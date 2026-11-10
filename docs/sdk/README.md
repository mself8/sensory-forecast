# Pleos Connect SDK 참조

앱이 쓰는 SDK, 권한, API를 한곳에 모았습니다. 모든 내용은 2026-11-09에 공식 문서와 Pleos Maven 저장소의 배포 파일로 확인했습니다.

## 설치

- Maven 저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/`
- 버전은 `gradle/libs.versions.toml`에서만 바꿉니다.

| SDK | 좌표 | 확인한 내용 |
|---|---|---|
| Vehicle | `ai.pleos.playground:Vehicle:2.3.2` | 저장소 최신 배포본, Kotlin 1.9.25로 빌드, minSdk 23 |
| NaviHelper | `ai.pleos.playground:NaviHelper:3.0.5` | 저장소 최신 배포본, Kotlin 1.9.25로 빌드, minSdk 23 |
| TextToSpeech | `ai.pleos.playground:TextToSpeech:3.3.1` | 저장소 최신 배포본, minSdk 23 |
| SpeechToText | `ai.pleos.playground:SpeechToText:3.3.1` | 저장소 최신 배포본 |

공모전 요구사항은 Android API 28 이상, Gradle 8.0 이상, Kotlin 또는 Java입니다.

## 권한

`AndroidManifest.xml`에 선언합니다. 문서에 적힌 보호 수준은 모두 normal입니다.

| 권한 | 쓰는 곳 |
|---|---|
| `pleos.car.permission.NAVI_ROUTE` | 내비 정보 조회 |
| `pleos.car.permission.TTS_SERVICE` | 음성 합성 |
| `pleos.car.permission.STT_SERVICE` | 부모 음성 명령 인식 |
| `pleos.car.permission.CAR_DRIVE` | 기어 상태 조회 |
| `pleos.car.permission.CAR_SAFETY` | 벨트 미착용 경고 조회 |
| `pleos.car.permission.CAR_SEATS` | 벨트 체결, 좌석 점유 조회 |
| `pleos.car.permission.CAR_WINDOWS` | 창문 상태 조회 |
| `pleos.car.permission.CONTROL_CAR_WINDOWS` | 창문 위치, 커튼, 스마트 글라스 제어 |
| `pleos.car.permission.CAR_CLIMATE` | 공조 상태 조회 |
| `pleos.car.permission.CONTROL_CAR_CLIMATE` | 내기 순환, 바람 세기 제어 |
| `pleos.car.permission.CAR_LIGHTS` | 실내 조명 색 설정 |

## 시작과 해제

- Vehicle: `Vehicle(context)`를 만들고 `initialize()`를 부른 뒤 도메인 객체를 꺼내 씁니다. 끝나면 `release()`를 부릅니다. 결과는 모두 콜백으로 비동기 전달됩니다.
  - 이 앱이 쓰는 도메인 객체: `getSeat()`, `getWindow()`, `getHVAC()`, `getLight()`
  - 차가 섰는지 볼 때 쓸 수 있는 도메인 객체: `getGear()`. Vehicle 2.3.2 배포 파일에 있고 `CAR_DRIVE` 권한이 필요합니다.
- NaviHelper: `NaviHelper(applicationContext)`를 만들고 `initialize(onConnected, onFailed)`를 부릅니다. 연결된 뒤 `addListener()`로 리스너를 붙이고, 끝나면 `removeListener()`와 `release()`를 부릅니다.
- TextToSpeech: `TextToSpeech(applicationContext, Mode.ON_DEVICE)`로 만들면 네트워크와 앱 등록 없이 차 안 엔진으로 읽습니다. `initialize()`, `addEventListener()`, `speak()`, `stop()`, `removeEventListener()`, `release()` 순서로 씁니다.
  - 오디오 영역을 정하는 `setAudioZoneId()`가 있습니다. 뒷좌석에만 들리게 할 수 있는지는 시험해야 합니다.
- SpeechToText: 부모의 "쉬자" 같은 음성 명령을 듣는 데 씁니다. 초기화, 결과 리스너, 시작과 중지 순서는 공식 문서를 따릅니다.

## 클래스 이름

배포 파일 안에서 확인한 이름입니다.

- `ai.pleos.playground.vehicle.Vehicle`
- `ai.pleos.playground.vehicle.api.Seat`, `Window`, `HVAC`, `Light`, `Door`
- `ai.pleos.playground.navi.helper.NaviHelper`
- `ai.pleos.playground.navi.helper.listener.NaviHelperEventListener`
- `ai.pleos.playground.navi.data.CautionPoint`, `TBTInfo`, `DestinationInfo`
- `ai.pleos.playground.navi.constants.CautionPointType`, `TurnType`
- `ai.pleos.playground.tts.TextToSpeech`, `ai.pleos.playground.tts.constant.Mode`

## 길 위 사건 받기

| 콜백 | 받는 값 |
|---|---|
| `onCautionPointInfo(cautionPointList: List<CautionPoint>)` | `CautionPoint(id: String, type: CautionPointType, remainDistance: Int)` 남은 거리는 미터 |
| `onTBTInfo(tbtInfoList: List<TBTInfo>)` | `TBTInfo(type: TurnType, distance: Double, description: String)` 거리는 미터 |
| `onDestinationInfo(destinationInfo: DestinationInfo)` | 남은 시간 `remainTime`, 남은 거리 `remainDistance` |
| `onReRouteEnded()` | 경로가 바뀐 시점 |

감각 예보에 쓰는 종류입니다.

- 주의 지점: `SPEED_BUMP`, `ROAD_DAMAGE_ALERT`, `DOWNHILL_CAUTION_AREA`, `RAILROAD_CROSSING`, `HIGHWAY_CONSTRUCTION`
- 회전 안내: `TURN_TUNNEL`, `TURN_ENTER_UNDER_L`·`_C`·`_R`, `TURN_SHARP_L`, `TURN_SHARP_R`, `TURN_UTURN`, `TURN_ENTER_ROUNDABOUT_*`, `TURN_TOLL`, `TURN_ENTER_SERVICEAREA`, `TURN_RESTAREA`, `TURN_ARRIVE`

내비 데이터에는 차 속도가 없습니다. 사건까지 남은 시간은 같은 주의 지점의 남은 거리가 줄어드는 속도로 계산하거나, FusedLocation SDK로 속도를 받아 계산합니다.

## 차 안 바꾸기

| 하는 일 | API | 권한 |
|---|---|---|
| 창문 위치 | `setWindowPosition(area: WindowArea, value: Int)` | CONTROL_CAR_WINDOWS |
| 커튼 | `moveWindowBlind(area: WindowArea, value: MoveControl)` 값은 FORWARD, BACKWARD, STOP | CONTROL_CAR_WINDOWS |
| 스마트 글라스 | `setSmartGlassLevel(area: WindowArea, value: Int)` | CONTROL_CAR_WINDOWS |
| 내기 순환 | `setHvacRecirculation(area: SeatArea, value: Boolean)` | CONTROL_CAR_CLIMATE |
| 바람 세기 | `setHvacFanSpeed(area: SeatArea, value: Int)` | CONTROL_CAR_CLIMATE |
| 실내 조명 색 | `setAmbientLightBrightnessColor(area: SeatArea, value: Int)` 값은 32비트 ARGB | CAR_LIGHTS |
| 벨트 체결 알림 | `registerSeatBeltBuckledState(area: SeatArea, listener)` | CAR_SEATS |
| 벨트 미착용 경고 | `registerSeatBeltNotBuckledWarning(area: SeatArea, listener)` | CAR_SAFETY |
| 좌석 점유 | `registerSeatOccupancy(area: SeatArea, listener)` | CAR_SEATS |

- 좌석은 달리는 중에 움직이지 않습니다. 주행 중에는 시트 설정이 막히고, 좌석이 움직이면 벨트 위치가 달라집니다.
- 차종과 트림마다 지원이 다릅니다. 기능을 쓰기 전에 `check...Capabilities`로 지원 여부와 값의 범위를 확인합니다.

## 가상 콘솔로 시연하기

1. 에뮬레이터 v2.1.0 이상을 띄웁니다.
2. `adb forward tcp:8088 tcp:8088`을 실행합니다.
3. 브라우저에서 `http://127.0.0.1:8088/`을 엽니다.

| 시연 | 가상 콘솔 카드 |
|---|---|
| 창문 닫기 | Window Position, Window Lock |
| 내기 순환, 바람 세기 | Climate 영역의 Air Recirculation, Fan Speed. 바람 세기는 0~8이고 좌석을 고를 수 없습니다 |
| 조명 색 | Ambient Color, Ambient Brightness. 색은 Cyan, Blue, Purple, White 네 가지뿐이고 좌석을 고를 수 없습니다 |
| 좌석 점유 | Seat Occupancy |
| 벨트 | 좌석별 벨트 카드가 없습니다. 아래 확인 필요 항목을 보세요 |

- 커튼과 스마트 글라스는 카드가 없어서 시연이 어렵습니다.
- 좌석별 조명은 가상 콘솔에서 좌석을 고를 수 없어 보여 주기 어렵습니다.
- 벨트: Safety 영역의 경고등 카드를 읽는 `registerTelltales`는 일반 앱이 받을 수 없는 권한이 필요합니다. 그 카드를 켰을 때 좌석별 벨트 신호가 오는지 에뮬레이터에서 먼저 시험합니다.
- 길 위 사건은 내비 모의 주행으로 만듭니다. 주의 지점과 회전 안내 이벤트가 모의 주행에서 실제로 들어오는지 먼저 시험해야 합니다.

## 명세를 더 읽을 때

API 상세 문서는 본문이 스크립트 안에 있어 브라우저 없이는 비어 보입니다. 아래 도구로 글자만 꺼내 읽을 수 있습니다.

```bash
python3 tools/pleos_api.py seat/register-seat-belt-buckled-state window/set-window-position
```

## 공식 문서

- 공모전 개발 환경: https://appdevchallenge.auto-hmg.io/guide/development
- Vehicle SDK 가져오기: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
- Vehicle 권한: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission
- NaviHelper 가져오기: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk
- NaviHelper 이벤트: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners
- TextToSpeech 소개: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro
- 주행 중 제한: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy
- 가상 콘솔: https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide
