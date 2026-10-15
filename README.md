# Android BLE Channel Sounding

Android에서 BLE Channel Sounding(CS) 거리 측정과 센서·GPS·RSSI 기록을 수행하는 앱임. 앱 표시 이름은 `Phone CS`임.
Initiator와 Reflector 역할을 전환할 수 있으며, 휴대폰 간 측정 및 Nordic nRF54L15 DK 보드 연동을 지원함.
CS를 지원하는 휴대폰과 OS가 필요하며, 현재 빌드 대상은 Android 17(API 37)임.

## 빌드 및 설치

- Android SDK API 37, JDK 21, Gradle 9.4.1 및 Android Gradle Plugin 9.2.1을 사용함.
- Android Studio에서 프로젝트 루트를 열고 Android SDK 설치 및 Gradle Sync를 수행함. Gradle JDK는 JDK 21로 설정함.
- 명령줄에서는 Android SDK 경로를 `ANDROID_HOME` 또는 로컬 `local.properties`의 `sdk.dir`로 지정하고 다음 명령을 실행함.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

생성된 APK는 `app/build/outputs/apk/debug/app-debug.apk`임. 휴대폰을 USB로 연결하고 Android Studio의 Run으로 설치할 수도 있음.
Gradle Wrapper를 포함하므로 Gradle을 별도로 설치할 필요는 없음. 최초 빌드에는 인터넷 연결이 필요함.

## 사용 방법

앱 실행 후 Bluetooth, Nearby Devices, Ranging 및 위치 권한을 허용함.

### 휴대폰 간 측정

1. Reflector 폰에서 `Reflector` 선택 → `역할 적용` → `Start Advertising`을 실행함.
2. Initiator 폰에서 `Initiator` 선택 → `역할 적용` → Target Reflector Type을 `Phone CS`로 선택함.
3. `Reflector 검색`으로 상대 폰을 선택하고 `연결 및 페어링`을 수행함.
4. 연결 준비가 완료되면 `CS 시작`을 실행하고 거리 및 수집 상태를 확인함.

### 휴대폰 Initiator와 보드 Reflector 측정

1. nRF54L15 DK에 BLE CS Reflector 펌웨어를 기록하고 전원을 켬.
2. 앱에서 `Initiator` 선택 → `역할 적용` → Target Reflector Type을 `Nordic / HW Reflector`로 선택함.
3. CS Security Level을 보드 펌웨어 설정에 맞게 선택함.
4. `Reflector 검색`으로 보드를 선택하고 `연결 및 페어링` 후 `CS 시작`을 실행함.

### 보드 Initiator와 휴대폰 Reflector 측정

1. 앱에서 `Reflector` 선택 → `역할 적용` → `Start Advertising`을 실행함.
2. Phone CS의 START/READY 절차를 지원하는 BLE CS Initiator 펌웨어를 nRF54L15 DK에서 실행함.
3. 보드의 자동 연결 및 페어링을 허용하고 전달되는 거리 결과를 확인함.

측정 종료 시 `CS 중지`를 실행함. 중단 후 자동 재연결·재시작은 수행하지 않으며, 다시 측정하려면 연결 상태를 확인하고 `CS 시작`을 실행함.
Advertising TX Power는 광고 송신 전력이며, BLE PHY는 BLE 연결 PHY 설정임. 두 설정 모두 CS 측정 PHY 설정과는 구분됨.

## 기록 파일

거리, Connection/Ranging RSSI, BLE PHY, 센서 및 GPS 정보를 CSV로 기록함.
파일 저장 위치는 휴대폰의 `Download/PhoneCS`임.

```text
yyyyMMdd_HHmmss_SSS_CS_INITIATOR_LOG.csv
yyyyMMdd_HHmmss_SSS_CS_INITIATOR_DATA.csv
yyyyMMdd_HHmmss_SSS_CS_REFLECTOR_LOG.csv
yyyyMMdd_HHmmss_SSS_CS_REFLECTOR_DATA.csv
```
