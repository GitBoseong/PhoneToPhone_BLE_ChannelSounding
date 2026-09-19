# Phone CS — 연구용 거리·센서 기록 앱

Android 17(API 37) 실험 환경의 두 휴대폰 사이의 BLE Channel Sounding 거리를 측정합니다.
XML + ViewBinding을 유지하며, **Advertising TX Power는 광고 송신 전력입니다. Channel Sounding RF TX Power를 제어하지 않습니다.**
## 이번 버전의 주요 변경

- Android 17(API 37)을 기준으로 `compileSdk`/`targetSdk`를 37로 맞췄습니다.
- CS 측정이 시작된 뒤에도 Reflector의 Advertising과 Initiator의 BLE Scan을 계속 유지합니다.
- 선택된 peer의 일반 BLE `ScanResult.rssi`를 `rssi_dbm`으로 수집하여 LOG CSV와 DATA CSV 양쪽에 기록합니다.
- Channel Sounding Ranging에서 제공하는 RSSI는 기존처럼 `ranging_rssi_dbm`으로 별도 보존합니다.
- DATA CSV는 50Hz 공통 timeline을 유지하며, 거리·BLE RSSI·Ranging RSSI·센서·GPS 이벤트의 원본 시각을 함께 기록합니다.

## 화면 및 사용 순서

화면 순서: Phone CS → Status → Role → Sensor → Advertising TX Power(Reflector) → BLE Control → Measurement → Log.
Status에는 실제 capability, 적용된 역할, BLE 연결 상태, CS 상태를 표시합니다. Measurement는 3자리 소수 거리와 Raw/이동평균/Samples/RSSI를 표시하고, 하단 로그는 스크롤 가능한 160dp 영역입니다.

1. 두 기기에서 Bluetooth/Nearby Devices/Ranging 권한을 허용합니다. GPS를 사용할 때는 정확한 위치 권한도 허용합니다.
2. Reflector: Reflector 선택 → **역할 적용** → Advertising TX Power 선택 → **Start Advertising**.
3. Initiator: Initiator 선택 → **역할 적용** → Reflector 검색 → 목록에서 기기 선택 → 연결 및 페어링.
4. Bond, 서비스 탐색, CCCD 준비가 끝나면 **CS 시작**을 누릅니다.
5. 기존 순서대로 START → Reflector RangingSession open → READY → 800ms 후 Initiator CS를 시작합니다.
6. Initiator의 거리값을 기존 12바이트 GATT 패킷으로 Reflector에 중계합니다.
7. **중지 / 연결 해제**는 센서·GPS·기록기·CS·광고·GATT를 종료합니다. 재실험은 광고/검색/연결 순서부터 진행합니다.

앱을 전경에 두고 실험합니다. 백그라운드 서비스나 화면 꺼짐 중의 수집률은 보장하지 않습니다.
미지원 Android 버전에서는 실행 버튼을 비활성화합니다. CS 하드웨어와 제조사 펌웨어의 지원이 모두 필요합니다.

## IMU와 GPS

두 Switch는 독립적이며 기본 ON입니다. 기본값은 `MainActivity.DEFAULT_IMU_ENABLED`, `DEFAULT_GPS_ENABLED`에서 변경할 수 있습니다.
센서는 CS 기록 세션이 시작할 때 실제 등록하고, 세션 중 Switch 변경을 반영합니다.

- IMU: Accelerometer(m/s²), Gyroscope(rad/s), Magnetometer(μT), Game Rotation Vector(x,y,z,w), Pressure(hPa).
- SensorManager에는 20,000μs를 요청합니다. 각 콜백은 복사한 최신 값과 실제 센서 timestamp만 thread-safe하게 보관합니다.
- 별도 50Hz 기록기가 20ms 목표 간격으로 snapshot을 기록합니다. 센서 이벤트 수에 맞춰 CSV 행을 생성하지 않습니다.
- IMU OFF이면 unregister하고 모든 IMU 값과 센서 timestamp를 비웁니다. 미지원 센서도 빈 값이며, 센서별 unavailable 로그는 Activity당 한 번 기록합니다.
- GPS: LocationManager.GPS_PROVIDER, minTime=1000ms, minDistance=0m. OS/수신 환경에 따라 실제 주기는 달라집니다.
- 새 GPS fix는 다음 기록 행에서 한 번만 소비합니다. last-known location을 사용하거나 이전 fix를 반복하지 않습니다.
- GPS OFF이면 removeUpdates하고 대기 fix도 지웁니다. 위치 권한 거부/대략적 위치만 허용/GPS provider 미지원 시 Switch를 OFF로 되돌리고 로그를 남깁니다.
- 기기 위치 기능이 꺼져 있으면 새 fix를 기다리며 빈 값으로 기록합니다. 위치 기능을 다시 켜면 새 fix부터 기록합니다.

## Advertising TX Power

Slider: **Default, -21, -20, …, 0, …, +20 dBm** (Android 17/API 37). 광고 시작 대기·광고 중·연결 중·측정 중에는 잠깁니다.
중지 후 다시 변경할 수 있습니다. 요청/실제 적용/광고에서 관측한 값을 서로 대입하지 않습니다.

| 값 | 획득 방법 |
| --- | --- |
| Requested | Reflector Slider의 custom dBm. Default이면 CSV는 빈 값 |
| Applied | 성공한 `AdvertisingSetCallback.onAdvertisingSetStarted`의 `txPower` |
| Advertised | Initiator `ScanResult.txPower`, 없으면 Legacy `ScanRecord.txPowerLevel` |
| Mode | Reflector에서 DEFAULT/CUSTOM. 상대 모드는 프로토콜로 전달하지 않으므로 Initiator에서는 빈 값 |

Default에서는 앱이 `setTxPowerLevel()`을 호출하지 않고 Android Builder 기본 설정을 사용합니다.
플랫폼 내부 기본 요청값을 실제 적용값으로 간주하지 않습니다. Applied는 콜백 전에는 `-`, 실패/미획득이면 빈 CSV 필드입니다.
Requested와 Applied가 다르거나 컨트롤러가 요청을 clamp해도 콜백값 그대로 기록합니다.

Android 17/API 37에서는 공개 Builder의 허용 범위가 -127..+20 dBm으로 확장되어,
실험 Slider는 요청한 -21..+20 구간을 1dBm 간격으로 노출합니다.
[공식 API 문서](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters#TX_POWER_MAX_AVAILABLE)의
`TX_POWER_MAX_AVAILABLE`(+20)를 사용합니다. deprecated `TX_POWER_MAX`(+1)를 상한으로 사용하지 않습니다.
API 36에서 실행하면 확인된 기존 허용 상한 +1로 Slider를 제한합니다. 실제 컨트롤러 지원/적용값은 콜백으로 확인합니다.
UI의 Default index는 0, custom index는 `dBm + 22`입니다.

`startAdvertisingSet()`을 Legacy/connectable/scannable로 사용하며 기존 서비스 UUID를 그대로 광고합니다.
기존처럼 GATT 서비스 추가 성공 이후 광고하고, 연결되면 광고만 중지합니다. 연결 후에도 Applied는 실험 metadata로 유지합니다.
UUID + Flags + TX Power AD는 Legacy 31바이트 한도 안에 들어가며 장치 이름은 넣지 않습니다.
TX Power AD를 포함해도 Legacy에서는 `ScanResult.txPower`가 없을 수 있으므로 `ScanRecord`를 확인합니다.
둘 다 없으면 UI/LOG는 N/A, DATA는 빈 값입니다.

## 세션 파일

공용 **Download/PhoneCS**(다운로드/PhoneCS)에 MediaStore로 두 파일을 생성합니다. 별도 저장소 권한은 필요하지 않습니다.

```text
yyyyMMdd_HHmmss_SSS_CS_INITIATOR_LOG.csv
yyyyMMdd_HHmmss_SSS_CS_INITIATOR_DATA.csv
yyyyMMdd_HHmmss_SSS_CS_REFLECTOR_LOG.csv
yyyyMMdd_HHmmss_SSS_CS_REFLECTOR_DATA.csv
```

같은 세션의 LOG/DATA는 동일한 timestamp prefix를 가집니다. UTF-8 BOM과 CSV escaping을 적용합니다.
Initiator는 CS 시작 버튼, Reflector는 START 수신 시 파일과 50Hz recorder를 시작합니다.
광고/스캔/페어링 등 세션 전 이벤트는 원래 절대시각을 유지해 LOG에 먼저 기록합니다. 대기 로그는 최근 5,000개로 제한하며 초과 시 버린 개수를 명시합니다.
측정 세션을 시작하지 않은 광고/스캔만으로는 파일이 생성되지 않습니다.

파일 open/write/flush/close는 단일 전용 executor에서 직렬화합니다. 센서/BLE/UI 콜백에서는 파일 I/O를 하지 않습니다.
64KiB BufferedWriter를 사용하며 매 행 flush하지 않습니다. 약 1초마다, Stop/Disconnect/Closed/Error/Activity destroy에서 flush/close합니다.
MediaStore IS_PENDING은 close 시 해제합니다. 저장 오류는 UI에 표시하고 기록 자원을 정리합니다.
OS의 강제 종료/전원 차단은 정상 close를 보장할 수 없습니다.

## LOG CSV

`timestamp_iso8601,timestamp_epoch_ms,role,source,message`

Initiator의 ScanCallback은 GATT 연결과 CS 측정 시작 후에도 중지하지 않습니다. Reflector 광고도 연결 시 중지하지 않습니다. 따라서 측정 중 계속 들어오는 선택 peer의 ScanResult.rssi를 단일 rssi_dbm으로 DATA에 기록하고, 같은 관측은 LOG에도 남깁니다.

APP, BLE(scan/advertising/pairing/GATT/START/READY/STOP), CS, SENSOR, GPS, STORAGE, ERROR를 기록합니다.
스캔마다 주소·RSSI·Advertised TX를 기록하며, Ranging 결과마다 거리·RSSI 또는 N/A를 기록합니다.
TX mode/requested, 광고 시작 요청/성공/실패, applied, advertised 또는 N/A가 포함됩니다.
세션 종료 로그에는 기록 행 수, 실제 구간 길이, 유효 Hz, 평균/중앙/최소/최대 간격, 건너뛴 deadline 수가 포함됩니다.

## DATA CSV

기본 32개 요구 컬럼에 실제 센서/스캔 시각과 행 출처를 추가한 41개 컬럼입니다. 없는 값은 모두 빈 필드이며 0/Null/N/A로 대체하지 않습니다.

| 컬럼 | 의미와 단위 |
| --- | --- |
| timestamp_iso8601 | 실제 기록 시각, 밀리초와 timezone offset 포함 |
| timestamp_epoch_ms | 실제 System.currentTimeMillis(), Unix epoch ms |
| elapsed_ms | 세션 시작 후 실제 monotonic 경과 ms, 소수 포함 |
| role, peer_address | 역할과 상대 Bluetooth 주소 |
| distance_raw_m, distance_smoothed_m | Android 원본 거리와 기존 최대 5개 이동평균(m) |
| distance_sample_count | 기존 누적 거리 sample count |
| distance_timestamp_epoch_ms | Initiator: RangingData timestamp, Reflector: GATT 수신 시각 |
| rssi_dbm | BLE Scan 관측 RSSI, 선택 peer의 마지막 실제 스캔은 첫 행에 한 번 기록 |
| ranging_rssi_dbm | 같은 거리 이벤트의 `hasRssi()`가 true일 때만 `rssi` |
| tx_power_mode | DEFAULT/CUSTOM; 직접 알 수 없는 상대 설정은 빈 값 |
| tx_power_requested_dbm | custom 요청값. Default 또는 미획득이면 빈 값 |
| tx_power_applied_dbm | Reflector 광고 성공 콜백의 실제 적용값 |
| tx_power_advertised_dbm | Initiator가 선택 peer의 광고에서 실제 읽은 값 |
| gps_latitude, gps_longitude, gps_timestamp_epoch_ms | 새로운 GPS fix의 위도/경도(degrees)와 Location.time |
| acc_x/y/z | 가속도(m/s²) |
| gyro_x/y/z | 각속도(rad/s) |
| mag_x/y/z | 자기장(μT) |
| game_rv_x/y/z/w | Game Rotation Vector quaternion |
| pressure_hpa | 기압(hPa) |
| acc/gyro/mag/game_rv/pressure_timestamp_epoch_ms | 각 snapshot의 실제 센서 획득 시각을 epoch ms로 변환한 값 |
| rssi_timestamp_epoch_ms | ScanResult.timestampNanos를 epoch ms로 변환한 관측 시각 |
| distance_source | LOCAL_RANGING 또는 REMOTE_RECEIPT |
| row_kind | SAMPLE: 50Hz 공통 timeline, FINAL_PENDING: 종료 시 남은 이벤트 배출 행 |
| record_elapsed_realtime_ns | 각 행을 기록할 때의 실제 SystemClock.elapsedRealtimeNanos |

GPS/거리/Ranging RSSI/BLE Scan RSSI는 큐에서 한 번씩 소비하며 새 이벤트가 없는 행은 비웁니다.
여러 이벤트가 같은 20ms 사이에 오면 큐 순서대로 다음 행들에 기록하고 각 원본 시각을 보존합니다.
낮은 주기로 동작하는 IMU 센서는 최신 snapshot이 반복될 수 있고 sensor timestamp를 통해 구분할 수 있습니다.
TX metadata는 실제 확인한 현재 실험 조건이므로 각 행에 반복합니다.

**양쪽 휴대폰이 알 수 있는 값은 다릅니다.** 기존 GATT 패킷은 raw Float + smoothed Float + count Int = 12바이트입니다.
따라서 Reflector에는 원본 Ranging RSSI/측정시각/Initiator의 Scan RSSI가 전달되지 않습니다.
Reflector Ranging RSSI는 빈 값, distance_source는 REMOTE_RECEIPT이며 거리 timestamp는 수신 시각입니다.
Initiator에는 Reflector requested/applied/mode가 전달되지 않으므로 빈 값이고, advertised만 기록합니다.
이를 추정하거나 복사해 채우지 않습니다. 필요하면 두 파일을 별도로 분석해 비교합니다.

## Timestamp와 50Hz 검증

SensorEvent.timestamp는 elapsed realtime nanoseconds이므로 수집 시작 시 wall-clock/elapsed-realtime anchor로 변환합니다.
RangingData.getTimestampMillis는 설치된 API 36 소스에서 `@CurrentTimeMillisLong`이므로 epoch ms로 그대로 저장합니다.
GPS는 Location.time을 사용합니다. 기록 행 자체의 시각은 항상 실제 system clock으로 읽습니다.
시스템 wall clock이 변경되면 epoch 간격에 변화가 보이며 이를 숨기지 않습니다. 주기 검증은 monotonic 열을 사용합니다.

RecorderLoop는 20ms monotonic deadline에 맞춰 재예약합니다. 늦어진 deadline은 건너뛰고 개수를 남깁니다.
밀린 행을 가짜 20/40/60ms 시각으로 만들지 않습니다. 종료 시 남은 event는 실제 시각의 FINAL_PENDING 행으로 보존하며 Hz 계산에서는 제외합니다.

```powershell
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python tools/validate_recording.py path/to/20260919_143812_123_CS_INITIATOR_DATA.csv
```

검증 도구는 samples, duration, effective rate, mean/median/min/max interval, jitter, GPS/거리 중복 및 빈 값 규칙을 출력합니다.
30초라면 SAMPLE 약 1,500행, 평균 약 20ms/약 50Hz가 목표입니다.
`RecorderLoopTest`는 앱에서 쓰는 동일한 루프를 호스트 JVM에서 30초 실행합니다. **호스트 결과는 실제 Android IMU 기록률 검증을 대신하지 않습니다.**
기기 검증은 양쪽 DATA CSV와 종료 LOG 통계를 확인해야 합니다.

빌드: compileSdk/targetSdk 37, AGP 9.2.1, Gradle 9.4.1. 검증 환경은 JDK 21이며 앱 bytecode target은 기존 Java/Kotlin 17을 유지합니다.
저장소에 wrapper 실행 스크립트/JAR가 없으므로 Android Studio 또는 설치된 Gradle 9.4.1을 사용합니다.

## Galaxy / Pixel 실제 단말 검증

각 시험에서 두 기기의 모델/OS/펌웨어, 거리, 방향, 환경을 함께 기록합니다.
Reflector에서 중지 → TX 선택 → 광고, Initiator에서 재검색/연결 → 30초 CS 기록 순서로 진행합니다.

| 시험 | Requested | 확인할 값 |
| --- | --- | --- |
| 1 | Default | requested 빈 값, callback applied, scanned advertised, RSSI, distance |
| 2 | -21 dBm | requested/applied/advertised 차이와 연결 성공 |
| 3 | -15 dBm | 동일 |
| 4 | -7 dBm | 동일 |
| 5 | 0 dBm | 동일 |
| 6 | +1, +5, +20 dBm | Android 17 컨트롤러 지원/오류/clamp 결과 |

각 시험에서 LOG와 DATA 모두에 RSSI/TX가 남는지, Legacy 검색·pairing·GATT·READY 순서·양쪽 거리 중계가 유지되는지 확인합니다.
추가로 IMU/GPS를 각각 OFF/ON, GPS 권한 거부/대략적 위치만 허용, 위치 provider OFF/ON, 센서 없는 기기, 상대 전원 OFF,
빠른 Stop/역할 변경/재시작, Activity 종료, 저장소 오류에서 crash/리스너 잔존/이전 세션 callback 섞임이 없는지 확인합니다.
새 GPS/거리/RSSI가 없는 행은 비어 있어야 하며, GPS fix가 없는 실내에서는 좌표 0을 쓰면 안 됩니다.

## 유지한 BLE/CS 동작

서비스·characteristic UUID, START/PREPARING/READY/STOP/STOPPED 문자열, 12바이트 거리 packet,
페어링/서비스 탐색/CCCD 순서, READY 후 800ms, security level 1, UPDATE_RATE_NORMAL,
RawInitiator/RawResponder 설정, indoor location type, 1,000 measurement limit, 최대 5개 단순 이동평균은 유지했습니다.
수집 resource 종료와 늦은 callback 차단만 보강했습니다. PBR/RTT 내부 계산이나 CS RF 송신 전력을 임의로 조작하지 않습니다.

