# Phone-to-Phone Bluetooth Channel Sounding

Android 16(API 36) 이상에서 두 대의 휴대폰을 Initiator / Reflector(Responder)로 선택하여
Bluetooth Channel Sounding 거리를 측정하는 예제입니다.

## 동작 순서

1. Reflector 휴대폰: 역할을 Reflector로 선택하고 `Reflector 광고 시작`.
2. Initiator 휴대폰: 역할을 Initiator로 선택하고 `Reflector 검색`.
3. 검색된 장치를 선택하고 `연결 및 페어링`.
4. Bond와 GATT notification 설정이 완료되면 `CS 시작` 버튼이 활성화됨.
5. Initiator가 GATT `START`를 전송.
6. Reflector가 Raw Responder RangingSession을 열고 `READY`를 notify.
7. Initiator가 Raw Initiator RangingSession을 시작.
8. 거리 결과는 Initiator 화면에만 표시됨.

## 요구사항

- Android 16 / API 36 이상
- 두 휴대폰 모두 `RangingManager`의 `csCapabilities` 지원
- foreground 실행
- Bluetooth, Nearby Devices, Ranging 권한 허용

## 빌드

- Android Studio에서 프로젝트를 열어 Sync 후 실행
- compileSdk 36
- JDK 17
- AGP 8.13.2 / Gradle 8.13

Gradle wrapper JAR/스크립트는 저장소에 포함하지 않았습니다. Android Studio에서 Sync하거나 로컬 Gradle로
`gradle wrapper --gradle-version 8.13`을 실행하면 됩니다.

## 주의

- Android Ranging API는 BLE CS 거리 결과를 Initiator에만 전달합니다.
- 제조사 Bluetooth firmware 구현에 따라 phone-to-phone Reflector 역할이 지원되지 않을 수 있습니다.
- `0x2A` 충돌을 줄이기 위해 Pairing과 GATT 준비가 끝난 뒤 Reflector session을 먼저 열고, READY 이후 Initiator session을 시작합니다.
