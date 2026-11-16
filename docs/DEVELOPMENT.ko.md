# 빌드·구조·검증 안내

일반 사용자는 [README](../README.md)와 [화면 사용법](QUICKSTART.ko.md)을 먼저 참고하세요. 이 문서는 v1.3.1-preview.1 구현을 확인하거나 수정하는 개발자를 위한 설명입니다.

앱 버전은 `versionName = "1.3.1-preview.1"`, `versionCode = 5`입니다. 스냅샷 저장 형식은 v3이며 v1·v2 자료를 읽습니다.

## 빌드 환경

실제 검증에는 JDK 21을 사용했습니다. Android SDK Platform 35와 SDK Build Tools가 필요합니다. 컴파일·대상 SDK는 35, 최소 지원 SDK는 29(Android 10)입니다.

프로젝트의 Gradle wrapper는 9.7.1, Android Gradle Plugin은 8.7.3, Kotlin은 2.0.21, Compose BOM은 2024.12.01입니다. 고정값은 [버전 카탈로그](../gradle/libs.versions.toml)와 [wrapper 설정](../gradle/wrapper/gradle-wrapper.properties)을 기준으로 확인하세요. 다른 도구 조합의 호환성을 검증했다는 의미는 아닙니다.

1. 저장소를 복제하고 프로젝트 폴더로 이동합니다.

```shell
git clone https://github.com/junseokandylee/exam-rf-guard.git
cd exam-rf-guard
```

2. Android Studio에서 프로젝트를 열거나 SDK를 별도로 설치합니다. Android Studio의 Gradle JDK 또는 터미널의 `JAVA_HOME`을 설치한 JDK 21 경로로 설정합니다.
3. 프로젝트 루트의 `local.properties`에 자신의 SDK 경로를 지정합니다. Android Studio가 생성할 수도 있습니다. 이 파일은 Git에서 제외합니다.

```properties
# 예시이며 실제 설치 경로로 바꾸세요.
sdk.dir=C:/Android/Sdk
```

4. Windows PowerShell에서 검증과 빌드를 실행합니다.

```powershell
./gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

macOS/Linux에서는 `./gradlew testDebugUnitTest assembleDebug lintDebug`를 사용합니다. 최초 빌드에는 Gradle·의존성·테스트 런타임 다운로드를 위한 네트워크가 필요합니다. 앱의 오프라인 관측 동작과 개발용 다운로드는 별개입니다.

| 결과 | 경로 |
| --- | --- |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 단위·UI 테스트 보고서 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 보고서 | `app/build/reports/lint-results-debug.html` |
| 테스트에서 생성한 화면 | `app/build/outputs/ui-captures/` |

자체 빌드의 debug 서명은 개발 환경에 따라 달라집니다. 공개 릴리즈 APK 위에 바로 업데이트 설치되지 않을 수 있습니다. 저장 파일 v3를 읽지 못하는 이전 앱으로의 다운그레이드는 지원하지 않습니다.

## 주요 구성

| 구성 | 역할 |
| --- | --- |
| `MainActivity`, `AppViewModel` | 화면·권한·수신 생명주기, 세션과 비교 상태, 저장 조정 |
| `scanner/BleScanner` | 지원 시 기본·확장 BLE 광고와 모든 지원 PHY, 기본 모드 복구, 실제 패킷 시각·광고 메타데이터 |
| `scanner/ClassicBluetoothScanner`, `BluetoothSweepCoordinator` | 발견 방송만 기록, BLE 45초와 Classic 최대 16초 교대, 중복·이전 콜백·탐색 종료 제어 |
| `scanner/WifiScanner` | 30초 간격 요청, 완료 브로드캐스트, 새 세션·중복·오래된 결과 필터 |
| `scanner/WifiDirectScanner` | 60초 간격 목록 요청, 세션당 첫 주소 확인만 기록, RSSI·패킷 시각을 만들어내지 않음 |
| `scanner/CellEnvironmentScanner`, `core/CellEnvironment` | 기본 SIM 모뎀 기지국 환경·60초 조회·캐시 시각·지원/오류 상태. 장비 추적과 분리 |
| `core/DeviceTracker`, `ObservationPeriods` | 무선 종류+주소별 누적 관측, 구간·재등장, 신호 상태 |
| `core/RadioMetadata`, `RadioMetadataPresentation`, `ui/RadioMetadataPanel` | Wi-Fi·BLE·Classic 발견 메타데이터와 접힌 상세 표시 |
| `core/WifiChannelObservation`, `WifiChannelAnalysis` | BSSID+주파수별 이력, 기준 대비 채널·주소 변화, 최근 AP 분포와 숨김 이력 집계 |
| `core/SignalSnapshot`, `SnapshotStore` | 수동 시점 기록·비교, 버전 있는 JSON 저장 |
| `core/CheckpointStore` | 세션별 누적 자동 보존, 디스크 최신본 병합, 오래된 저장 방지 |
| `core/DeviceIdentity`, `AudioNameCatalog`, `WearableNameCatalog` | 공식 회사 코드와 이름 기반 후보 해석 |
| `core/DistanceEstimate`, `LocalizationEngine` | 가정에 기반한 거리·이동 기반 방향 추정 |
| `core/SignalReport`, `SignalExportViewModel` | 고정된 비교 내용의 텍스트 보고서와 시스템 파일 저장 |
| `ui/SnapshotScreen`, `DeviceListScreen`, `ScanStatusScreen` | 저장→비교→추가 신호 흐름, 상세와 수신 상태 |
| `ui/ChannelScreen`, `ChannelComponents` | 대역별 AP 분포, 새 채널·숨김 이력 필터, AP별 수신 상세 |

## 수신과 저장의 계약

- `radioKey`는 무선 종류와 대문자로 정규화한 주소의 조합입니다. `BLE`, `CLASSIC`, `WIFI`, `P2P`는 동일 주소 문자열이어도 별도 관측입니다. `SIM`은 가상 데이터용 구분입니다.
- 콜백에서 추적기에 즉시 기록하고 화면용 집계는 100ms마다 갱신합니다. 빠른 화면 갱신을 무선 탐지율 보장으로 해석하지 않습니다.
- BLE는 탐색 시작 이전·미래·0/음수 타임스탬프를 제외합니다. 현재 탐색 안에서 늦게 도착한 패킷은 실제 관측 시각을 유지합니다.
- Wi-Fi는 성공한 완료 브로드캐스트에서 새 세션 시작 이후의 관측만 받습니다. 같은 주소의 같거나 이전 타임스탬프를 중복 수신 횟수로 세지 않습니다.
- `HISTORY` 주소도 세션 전체 목록에는 유지합니다. 수동 기준은 전체 세션 이력, 비교는 최근 수신과 기준 이후 새 주소의 이력을 포함합니다.
- BLE 15초·Wi-Fi 90초·Classic 180초 초과 공백을 관측 구간 경계로 삼습니다. 늦은 관측이 공백을 메우면 구간을 합칠 수 있습니다. P2P는 첫 시스템 목록 확인만 기록하고 UI·보고서에서 실제 수신 구간·재등장을 산출하지 않습니다.
- 자동 보존은 5초마다·정상 중지 시 하나의 세션 기록을 갱신합니다. 수동 스냅샷은 불변입니다. 저장 실패와 강제 종료 직전 유실 가능성은 UI·사용자 안내에 명시합니다.
- 공간은 첫 수동·자동 저장에서 고정합니다. 다른 공간은 새 탐색으로 수집합니다.
- `ON_STOP`에서 수신을 중지합니다. ViewModel은 Activity 재생성에 유지되고 요청된 저장은 화면 수명과 분리해 마칩니다. 백그라운드 수신 서비스는 없습니다.

자료는 앱 내부 JSON으로 저장하며 임시 파일 쓰기·동기화 후 교체합니다. 구간 정보가 없으면 재등장을 만들어내지 않습니다. 지원하지 않는 파일이나 손상 파일을 불러오지 못했을 때 기존 자료를 빈 목록으로 덮어쓰지 않도록 호출 경로를 보호합니다.

## 탐색 범위와 출처별 증거

### BLE 우선·범위 확장

기본값 `expandedDiscovery=false`는 BLE를 연속 수신하고 Wi-Fi AP를 30초 간격으로 요청합니다. 확장 시 `BluetoothSweepCoordinator`가 BLE 45초 → Classic 최대 16초를 반복합니다. Classic 완료가 먼저 확인되면 다음 BLE 구간으로 이동합니다. 두 Bluetooth 탐색을 동시에 실행하지 않으며 일반 Bluetooth 검색 중에는 BLE 광고를 놓칠 수 있습니다. Wi-Fi AP 요청은 별도로 계속하고 Direct·기지국 조회는 60초 간격으로 추가합니다.

Classic 중지 시 앱이 시작한 회차에 대해서만 `cancelDiscovery()`를 한 번 요청합니다. 취소 요청 반환을 종료 완료로 간주하지 않으며 `adapter.isDiscovering=false` 확인 뒤 BLE를 재개합니다. 미종료 상태는 `WAITING`으로 표시하고 250ms 간격 10회 확인 뒤 5초 쉬고 재확인합니다. `BLE 우선`으로 바꾸거나 새로 시작할 때도 종료 확인을 건너뛰지 않습니다. 다른 앱의 검색을 취소하지 않으므로 최대 16초는 회차의 대기 시간이며 BLE 중단 시간 전체의 상한이 아닙니다.

`BleScanner`는 `isLeExtendedAdvertisingSupported`를 확인한 뒤 `setLegacy(false)`·`PHY_LE_ALL_SUPPORTED`를 사용합니다. LOW_LATENCY·ALL_MATCHES·보고 지연 0은 유지합니다. 확장 미지원은 기본 광고로 시작하고, 확장 시작 실패는 기본 모드로 한 번 재시도합니다. `usesExtendedAdvertisements`와 `scanModeNotice`로 사용 중인 방식과 이유를 노출합니다. 권한·Bluetooth 꺼짐 오류를 지원 실패로 숨기지 않습니다. BLE의 확장 광고 지원과 UI의 `범위 확장` 선택은 서로 다른 개념입니다. [ScanSettings 공식 계약](https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder)

`ClassicBluetoothScanner`는 자신의 discovery 시작 후 `ACTION_FOUND`로 받은 주소·이름·RSSI·DeviceClass만 사용합니다. 같은 회차의 주소는 한 번 세고, 다음 회차의 실제 발견 응답은 새 관측이 될 수 있습니다. `ACTION_FOUND`에는 원래 패킷 타임스탬프가 없으므로 기록 시각은 방송 수신 시각입니다. 페어링 목록·별칭·연결 상태를 주변 수신으로 바꾸지 않고, 페어링·소켓·연결 API를 사용하지 않습니다. 종료 후나 이전 회차 콜백은 무시합니다. [일반 Bluetooth 발견 API](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices)

### Wi-Fi Direct 목록

`discoverPeers()` 요청 수락은 RF 수신 성공이 아닙니다. `requestPeers()`가 반환한 목록은 캐시일 수 있고 패킷 시각·RSSI가 없습니다. 세션별 `seenAddresses`에 처음 들어온 주소만 `ScanSource.P2P`, `rssi=null`, `txPower=null`, 목록 확인 시각으로 전달합니다. 빈·무효 주소는 제외합니다. 반복 목록·이름 변경·목록에서의 사라짐과 재등장으로 수신 횟수를 늘리지 않습니다.

P2P 주소는 장비 주소 이력·비교·합집합에 출처를 구분해 포함하지만 `최근 수신` 집계에서는 제외합니다. UI·보고서에서는 `목록 확인`으로 표시하며 실제 수신 구간·재등장·거리로 해석하지 않습니다. AP BSSID와 같은 물리 장비로 합치지 않습니다. `lastUpdatedMs`는 빈 목록을 포함한 응답 시각입니다. 연결·그룹 생성·소켓은 사용하지 않습니다. [Wi-Fi Direct API](https://developer.android.com/develop/connectivity/wifi/wifip2p)

`stopPeerDiscovery()`는 해당 세션의 탐색 요청이 수락된 경우에만 호출합니다. 실패·BUSY 상태를 다른 앱 검색의 소유권으로 해석해 취소하지 않습니다. 시스템 채널과 콜백은 중지 시 정리하고 다음 세션의 generation으로 이전 응답을 차단합니다.

### 기지국 환경

`CellEnvironmentScanner`는 `createForSubscriptionId()`로 모든 SIM을 순회하지 않고 기본 `TelephonyManager`의 `requestCellInfoUpdate()`를 60초마다 요청합니다. 정확한 위치 권한을 사용하며 `READ_PHONE_STATE`·전화번호 권한은 요청하지 않습니다. subscriber ID·IMEI·SIM 번호·전화번호·기지국 좌표를 수집하지 않습니다.

기지국의 LTE·NR·WCDMA·GSM·CDMA 방식, 공용 식별 필드, 채널, dBm, 등록 여부와 모뎀 관측 시각을 `CellEnvironmentSnapshot`에 담습니다. 0·미래 시각과 unavailable 값을 거르고, 요청 전 timestamp는 `cached=true`로 보존합니다. 캐시라는 사실만으로 OS throttling이 확정됐다고 표시하지 않습니다. 콜백 응답 시각과 원래 모뎀 관측 시각은 구분합니다. [TelephonyManager 조회 계약](https://developer.android.com/reference/android/telephony/TelephonyManager#requestCellInfoUpdate(java.util.concurrent.Executor,%20android.telephony.TelephonyManager.CellInfoCallback))

미지원·SIM 없음·권한 부족·빈 결과·캐시·30초 응답 시간 초과·모뎀 오류를 구분합니다. SIM 없음은 명시적인 `SIM_STATE_ABSENT`에서만 사용하며 빈 기지국 목록으로 추론하지 않습니다. 실패 시 마지막 성공 결과를 유지할 수 있고 다음 주기에 재시도합니다. 세션 토큰과 요청 번호로 중지·시간 초과 후 늦은 응답을 무시합니다.

기지국은 `DeviceTracker`·`ScanSource`·신규 알림·장비 합집합에 넣지 않습니다. `SignalSnapshot.cellEnvironment`라는 별도 참고 필드로 저장하며 보고서에는 기준·대상 각각의 환경을 표시합니다. 주변 휴대전화의 존재·수·소유자·사용 내용으로 해석하지 않습니다.

### 비교 범위의 기록

`SignalSnapshot.discoverySources:Set<ScanSource>?`는 해당 세션에서 **요청한 무선 종류의 합집합**입니다. 지원·권한·실제 수신 성공을 보증하지 않으며 중간에 범위를 줄여도 앞서 요청한 종류는 남습니다. 기준보다 대상의 종류가 늘어나면 해당 종류를 기준 때 탐색하지 않았다는 경고를 표시합니다. 이것을 새 장비 반입의 증거로 해석하지 않도록 같은 설정의 입실 전·후 기록을 권장합니다. 이전 자료의 null은 범위 미기록이며 빈 집합이나 기본값으로 바꾸지 않습니다.

## 무선 메타데이터

`RfObservation`, `TrackedDevice`, `SnapshotSignal`의 `radioMetadata:RadioMetadata?`와 채널별 `WifiChannelObservation.wifiMetadata`에 저장합니다. AP 전체와 주파수별 최신 시각을 각각 확인하며 오래된 패킷이 최신 메타데이터를 덮어쓰지 않습니다. 최신 패킷에 정보가 없으면 과거 값을 새 패킷의 정보로 빌려오지 않습니다. BLE UUID 목록은 저장 시 복사해 입력 목록의 변경과 분리합니다.

| 필드 | 의미 |
| --- | --- |
| Wi-Fi `channelWidthCode` | Android 원본 코드. 20/40/80/160/80+80/320 MHz를 구분하며 모르는 값은 폭 미확인 |
| `centerFrequency0Mhz`, `centerFrequency1Mhz` | 첫 중심 주파수는 20 MHz에서 미사용, 두 번째는 80+80 MHz에서만 사용. 미제공·미사용은 null |
| `capabilities` | 인증·키 관리·암호화 등을 알리는 광고 문자열. 실제 보안 인증·위험 점수 아님 |
| BLE `serviceUuids` | 해당 광고에서 제공한 서비스 식별자 목록. GATT 접속이나 서비스 내용·모델 인증 아님 |
| `connectable`, `legacy`, `primaryPhy`, `secondaryPhy` | 수신 광고의 플래그·PHY. 연결 가능 광고와 실제 연결 상태를 구분하며 secondary PHY 0은 미사용 |
| `classicDeviceClass` | 발견 방송의 DeviceClass 원본 값. 제품 모델을 확정하지 않음 |

80+80 MHz의 합계 폭은 160이지만 UI에는 `channelWidthLabel`의 `80+80 MHz`를 사용합니다. 위 값은 Android가 제공한 발견 정보이며 직접 측정한 트래픽·간섭량이 아닙니다. 제조사 원본 payload·서비스 데이터 bytes·통신 내용은 추가 저장하지 않습니다. [Wi-Fi ScanResult](https://developer.android.com/reference/android/net/wifi/ScanResult), [BLE ScanResult](https://developer.android.com/reference/android/bluetooth/le/ScanResult)

## Wi-Fi 채널·숨김 이력의 계약

### 주소별 주변 네트워크 보기

`analyzeWifiNetworks(WifiChannelAnalysis)`는 채널 이력을 BSSID별로 묶고 가장 최근 관측을 먼저 선택합니다. 그 뒤 대역을 필터링하므로, AP가 다른 대역으로 옮겨 간 과거 채널을 현재 채널로 되살리지 않습니다. 같은 SSID를 쓰는 다른 BSSID는 각각 유지합니다. 동률의 최신 주파수가 여러 개면 채널을 확정하지 않고 별도 미확정 목록에 남깁니다.

`WifiNetworkAnalysis`는 최근·과거 이력·채널 미확정·대상 자료 없음 상태를 구분합니다. 구형 대상의 채널 정보 부재는 최근 개수 `null`이며 0으로 바꾸지 않습니다. 이름·현재 숨김·메타데이터는 최신 관측을 사용하고 숨김 이력은 관련 기록 전체에서 보존합니다. 주파수별 누적 횟수나 저장본의 횟수를 더하지 않습니다.

`WifiChannelAccessPoint.scopeFirstSeenMs`와 `scopePeakRssi`는 선택 범위의 저장본 전체에서 최초 시각과 최대 세기를 보존합니다. 탐색을 다시 시작한 같은 주소·같은 주파수도 이전 최초 시각을 잃지 않습니다. `observation`의 횟수·구간은 최신 세션의 누적 기록을 유지하며, 범위 전체의 연속 수신 시간이나 횟수로 바꾸지 않습니다.

`wifiNetworkPlot(analysis, band)`는 최근 관측 중 최신 채널이 하나이고 RSSI가 있는 주소만 좌표로 바꿉니다. 가로축은 주 채널 주파수, 세로축은 RSSI이며 같은 좌표를 인위적으로 흩뜨리지 않습니다. RSSI 축 밖의 값은 가장자리에 배치하되 원래 값과 범위 초과 표시를 유지합니다. 채널 폭을 스펙트럼 파형으로 그리거나 통신 사용률을 만들어 내지 않습니다.

`ChannelScreen`의 기본 보기는 `주변 네트워크`입니다. 새 채널 알림과 비교 화면의 채널 버튼은 `채널 비교`로 이동하고, 채널 탭을 직접 누르면 주변 보기로 돌아갑니다. 선택한 저장 대상·실시간·중지 당시 시점을 계속 구분합니다.

### 수집과 보존

`WifiScanner`는 Android `ScanResult.frequency`를 `RfObservation.frequencyMhz`에 전달합니다. 같은 BSSID의 같거나 이전 시각인 결과를 다시 세지 않으며, 채널 번호가 달라졌다는 이유만으로 오래된 결과를 새 관측으로 바꾸지 않습니다. 실제 무선 경로와 가상 데이터 경로 모두 같은 주파수 필드를 사용합니다.

`DeviceTracker`는 주소별 AP 추적과 별개로 `WifiChannelObservation`을 주파수마다 누적합니다. 키는 `WIFI:정규화한_BSSID@주파수_MHz`입니다. 각 항목에는 이름, 최초·마지막 수신, 횟수, 마지막·최대 RSSI, 관측 구간을 담습니다. 주파수가 없거나 0 이하이면 채널 항목을 만들지 않지만 기존 AP 관측은 유지합니다. 매핑할 수 없는 양수 주파수는 MHz 그대로 표시할 수 있습니다.

늦은 관측은 최초 시각·횟수·최대 RSSI·구간에 반영하지만, 해당 주파수의 최신 이름·RSSI·숨김 상태를 덮어쓰지 않습니다. AP 전체로는 늦은 관측이어도 그 주파수에서는 최신일 수 있으므로 AP와 주파수별 최신 시각을 각각 비교합니다. Wi-Fi 구간 경계는 90초 초과이며, 뒤늦은 관측으로 구간이 합쳐질 수 있습니다.

**`SignalSnapshot.capture()`의 `wifiChannels`는 `signals` 필터와 독립적입니다.** 전달받은 전체 Wi-Fi 추적 목록의 주파수 이력을 담습니다. 기준 AP가 새 채널로 옮긴 뒤 90초 넘게 안 보여 주소 비교에서 제외되더라도 채널 이력은 남아야 합니다. 이를 위해 앱은 비활성 주소도 포함한 추적 목록으로 수동·자동 스냅샷을 생성합니다.

### 기준 대비 해석

- 비교 범위는 같은 공간·실제/연습 모드의 기준~대상 저장 시점입니다. 선택한 대상보다 나중에 저장한 자료는 과거 화면을 바꾸지 않습니다.
- 새 채널은 **기준에 없는 주파수**입니다. 2.4·5·6 GHz에서 같은 채널 번호를 써도 주파수가 다르면 합치지 않습니다.
- 추가 AP는 기준의 Wi-Fi 주소 목록과 채널 이력 모두에 없는 BSSID입니다. 기준에 주소만 있고 주파수가 없던 AP를 새 주소로 단정하지 않습니다.
- 같은 주소의 채널 변경은 기준에서 마지막으로 관측한 주파수와 이후 주파수의 차이입니다. 실제 기기의 위치 이동이나 위험성 판정이 아닙니다.
- 최근 AP는 대상 시점의 90초 이내 마지막 관측 채널에만 셉니다. 최신 시각이 여러 주파수에서 동률이면 최신 채널을 확정하지 않고 최근 AP 수에서 제외합니다.
- AP별 횟수·구간에는 가장 최근 관측이 담긴 세션의 누적 요약을 사용합니다. 여러 스냅샷의 누적 횟수를 더하지 않습니다.
- 그래프는 AP 수와 마지막 RSSI를 보여줍니다. 강 ≥−60, 중 −79~−61, 약 ≤−80 dBm이며 RSSI가 없으면 미확인입니다. 채널 폭은 발견 메타데이터로 표시하고 실제 트래픽 점유율·간섭량은 측정하지 않습니다.

### 숨김 상태

Android 결과의 SSID가 null·빈 문자열·공백이면 `RfObservation.hidden=true`입니다. `TrackedDevice`, `SnapshotSignal`, `WifiChannelObservation`은 마지막 관측 상태 `hidden`과 한 번이라도 관측한 이력 `everHidden`을 구분합니다. 뒤늦은 숨김 결과는 현재 `hidden`을 바꾸지 않아도 `everHidden`에는 반영합니다.

선택한 범위 안에서 여러 세션을 합칠 때는 최신 관측 상태를 유지하면서 과거 저장본의 숨김 이력을 `hasHiddenHistory`로 보존합니다. 필터는 현재 또는 과거 숨김을 모두 포함합니다. 전체·추가 숨김 AP 수는 BSSID를 정규화해 중복 제거하므로 같은 AP의 채널 변경을 여러 AP로 세지 않습니다. 숨김 채널 필터를 켜도 막대는 해당 채널 전체의 AP 분포이며 상세 목록에서 숨김 이력 AP를 추립니다.

이 플래그는 수신 결과에 이름이 없었다는 설명입니다. 원래 SSID 복원, 위험 점수, 부정행위 판정으로 확대하지 않습니다.

### 저장 형식 v3와 이전 기록

| 필드·형식 | 해석 |
| --- | --- |
| v1 | 기존 주소 관측. 관측 구간·채널 정보를 만들어 복원하지 않음 |
| v2 | 관측 구간·1m RSSI 기준 추가. Wi-Fi 주파수 이력은 없음 |
| v3 | `wifiChannels`, `hidden`·`everHidden`, 선택적 무선 메타데이터·`cellEnvironment`·`discoverySources` 포함 |
| `wifiChannels=null` 또는 키 없음 | 이전 기록 등으로 채널 정보가 없음. 신규 채널 비교 보류 |
| `wifiChannels=[]` | 채널을 기록하는 현재 형식에서 관측된 채널 항목이 없음 |
| v3에서 숨김 키 누락 | `hidden=false`, `everHidden=hidden`으로 복원. 이름을 항상 수신했다는 증거는 아님 |
| `radioMetadata`·`wifiMetadata` 키 누락 | 해당 발견 정보 미기록, null 유지 |
| `cellEnvironment` 키 누락 | 기지국 환경 미기록. 기지국 0개로 해석하지 않음 |
| `discoverySources` 키 누락 | 요청 범위 미기록. 실제 수신된 주소로 범위를 역추정하지 않음 |

v1·v2를 읽어 v3로 다시 저장해도 채널 정보의 `null`을 빈 목록으로 바꾸지 않습니다. 구형 기준의 정보 부재를 '기준 채널 0개'로 해석하면 모든 채널이 신규로 보이기 때문입니다. 현재 버전에서 새 기준을 저장해야 채널 비교가 확정됩니다. 수동 스냅샷과 자동 보존 기록에 같은 저장 형식을 사용합니다.

## Bluetooth 채널 관측·루팅의 범위

Wi-Fi `ScanResult.frequency`와 달리 Android의 일반 BLE `ScanResult`에는 수신 RF 채널 번호나 중심 주파수 필드가 없습니다. `primaryPhy`·`secondaryPhy`는 LE 1M·LE 2M·LE Coded 같은 물리 계층 방식이며 RF 채널 번호로 변환할 수 없습니다. 발견 결과를 받는 API를 제3자 연결의 패킷 캡처나 대역 전체 에너지 측정으로 확대해 설명하지 않습니다. [Android BLE 결과 필드](https://developer.android.com/reference/android/bluetooth/le/ScanResult), [Bluetooth SIG 채널·호핑 설명](https://www.bluetooth.com/learn-about-bluetooth/tech-overview/)

루팅으로 HCI 로그를 읽더라도 일반적으로 그 단말의 host↔controller 교환을 보는 것입니다. 주변의 관계없는 두 기기 사이 무선 링크를 수신하도록 칩이 바뀌지는 않습니다. [AOSP 디버깅 설명](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging)

일부 지원 칩·펌웨어의 InternalBlue 연구에는 저수준 진단과 BLE 광고 채널 보고가 있습니다. 이는 이 앱의 기능도, 임의 Android 단말의 제3자 연결 전부를 관찰할 수 있다는 보장도 아닙니다. 앱은 root·펌웨어 패치·HCI 기록 수집을 하지 않습니다. [InternalBlue 지원 기능](https://github.com/seemoo-lab/internalblue/blob/master/doc/features.md)

외부 BLE 스니퍼는 지원되는 링크를 추적할 수 있는 경우가 있지만 연결을 놓칠 수 있고, BLE 지원을 Classic 오디오 통신 지원으로 간주하면 안 됩니다. 전송 관측과 암호화된 내용 해독도 별개입니다. 현재 앱에는 외부 수신기 연동이 없습니다. [Nordic 도구 설명](https://docs.nordicsemi.com/r/bundle/nrfutil/page/nrfutil-ble-sniffer/wireshark_sniffer_overview.html)

## 거리 모델

EMA RSSI와 아래 모델을 사용합니다.

```text
d = 10 ^ ((A - RSSI) / (10 × n))
n = 2.7
A = 기기에서 받은 유효한 1m RSSI 기준, 없으면 −59 dBm 가정
```

실제 BLE 스캐너는 일반 광고의 AD Tx Power를 1m 보정 RSSI로 사용하지 않습니다. BLE·Wi-Fi·Classic 수신 경로는 1m 기준값을 추출하지 않아 RSSI가 있을 때 미보정 가정값을 사용합니다. P2P는 RSSI가 없고 기지국 환경은 이 모델의 대상이 아닙니다. 데이터 모델에는 기준값을 담을 수 있지만 현재 앱에 사용자 보정 기능은 없습니다. 재등장 시 EMA를 새 구간부터 계산합니다.

상세 범위는 RSSI ±6 dB, n=2~4, 미보정 A ±10 dB 조합의 최소~최대입니다. 통계적 신뢰구간이나 거리 정확도 보장이 아닙니다. 근거 해석은 [Bluetooth SIG의 RSSI 설명](https://www.bluetooth.com/blog/proximity-and-rssi/)을 참고하세요.

## 검증과 남은 한계

2026년 11월 16일 v1.3.1-preview.1 기준 268개 테스트, `assembleDebug`, `lintDebug`가 통과했습니다. 테스트 실패·오류·건너뜀은 0개이며 Lint는 오류 0개·경고 21개입니다. 테스트의 전체 목록은 [test 소스](../app/src/test/java/com/classroom/rfdetector)에 있습니다.

관측·비교·보존·식별·스캐너 콜백·Android UI·내보내기를 검증합니다. Wi-Fi 채널에서는 단발 관측·채널 이동·지연 수신·재등장, 주소 필터와 독립적인 보존, 숨김→이름 표시 후 과거 숨김 유지, v1/v2/v3 호환과 null/빈 목록 구분을 검증합니다. 화면 캡처에는 연습 모드나 결정적인 테스트 입력을 사용합니다. 실제 무선 수신 환경, 시스템 권한 대화상자의 단말별 차이, 센서 정확도, 발열·배터리를 대신 검증하지 않습니다.

확장 경로의 검증에는 BLE 기본 모드 복구·광고 메타데이터, Classic 교대·중복·중지 콜백, Direct 캐시 목록의 1회 기록·거리/재등장 미산출, 기지국 캐시·SIM·권한·timeout·후속 콜백과 별도 보존이 포함됩니다. 관련 자료가 없는 이전 파일에서 메타데이터·환경·요청 범위를 지어내지 않아야 합니다.

실기기 검증 결과를 제안할 때는 단말·OS·앱 버전, 기기명과 동작 상태, 측정 조건, 수신/미수신 시각, 반복 관측을 구분해 기록해 주세요. 개인 이름이나 실제 현장의 식별 자료를 그대로 공개하지 마세요.

## 공개 자료 관리

`local.properties`, `.env`, 서명 키, 빌드 출력, 실제 현장 수신 기록을 커밋하지 않습니다. 테스트 fixture와 예시 화면은 실측 자료와 구분합니다. 일반 사용자에게 노출되는 설명에는 근거 없는 탐지율·AI 판정·정확한 위치 보장을 추가하지 않습니다.

현재 별도 오픈소스 라이선스는 미지정입니다. 외부 코드·데이터의 조건과 출처를 확인하고, 프로젝트의 배포·재사용 조건은 [README의 라이선스 상태](../README.md#sources)를 참고하세요.
