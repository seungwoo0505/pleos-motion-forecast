# 멀미 예보 (Motion Forecast) — MVP

Pleos Connect(AAOS) 차량용 앱. 아이 멀미를 날씨처럼 예보하고, 달리는 동안 목소리로 챙기고, 도착하면 승차감 일기를 남깁니다.
현대차 Pleos 앱 공모전용 최소 동작 앱(2026-11-06). **비공개 저장소** — 외부 공개 금지.

- 설계: 「멀미 예보」 설계 v1 (공모전 폴더 `1. 기획/261106_Claude_멀미예보_설계v1.md`) 그대로
- 패키지: `com.recovlab.motionforecast` (시험 앱 `com.recovlab.pleosdash`와 별도)

## 흐름
| 때 | 화면 | 소리 |
|---|---|---|
| 출발 전 (P) | 목적지 → NaviHelper 경로 → 회전 안내 수 · 혼잡 · 남은 시간으로 **흔들림 날씨 카드** 4단계(맑음 · 구름 · 울렁 · 폭풍) + 준비 3가지 | — |
| 주행 중 (D) | 자동 차단 (CarUxRestrictions) | Pleos TTS 온디바이스 한 마디 (목소리 규칙 · 말하지 않는 조건) |
| 도착 (P 30초 + 문 열림) | **승차감 일기**: 흔들림 그래프 · 가장 흔들린 3구간 · 다음 추천 한 줄 | — |

## 계산 (설계서 2장, 모두 가정값)
- a_x = dv/dt (속도 1초 이동 평균 후 미분), a_y ≈ v²·tan(δ/14.5)/2.9 (조향각 없으면 v·Δheading/Δt)
- 0.1~0.5 Hz 성분 제곱 → 시간 누적 → 제곱근 = 흔들림 지수 (MSDV 개념 참고 자체 지수, 의학적 판정 아님)
- 출발 전 점수 = 회전/km + 혼잡 km × 0.5 + 시간(h), 맑음 < 1.5 ≤ 구름 < 3 ≤ 울렁 < 4.5 ≤ 폭풍
- 보정 스위치: 착석 반대(기본 켬), 조향 부호(기본 켬) — 에뮬레이터에서 값이 반대로 들어옴

## 파일
- `ShakeEngine.kt` 흔들림 계산 · `Forecast.kt` 출발 전 날씨 · `VoiceCoach.kt` 목소리 규칙 · `Views.kt` 날씨 아이콘 4개 · 그래프 · `MainActivity.kt` 화면 · 입력
- `demo/run_demo.ps1` 30초 시연 (Virtual Console API + GPX 재생 + 화면 녹화)

## 빌드 · 실행
```
gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Pleos Connect Emulator v2.1, `local.properties`에 `sdk.dir` 필요. 시연: 앱에서 목적지·시연 모드 선택 → `powershell -File demo/run_demo.ps1 -Record -OutMp4 <경로>`.

## 시험에서 정해진 함정
- Pleos TTS: `pleos.car.permission.TTS_SERVICE`, `initialize()` 성공 뒤 `addEventListener()`, `setLocale(Locale.KOREA)`
- `pleos.car.permission.FUSED_LOCATION`은 dangerous → 실행 중 요청
- NaviHelper `requestRoute`는 내비 앱을 앞으로 띄움, 경로 상태 값은 새 경로 시작 뒤 다시 요청해야 맞음
- Virtual Console API는 `/browser/session` heartbeat가 있어야 받고, 속도는 정수만 받음
