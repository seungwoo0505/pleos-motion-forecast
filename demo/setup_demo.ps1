# 시연 준비: 앱 다시 켜기 → 목적지(서울역) → 탑승자 '아이' → 목소리 안내 켜기 → 설정에서 시연 모드
# 좌표는 Pleos Connect Emulator v2.1 (2560x1440) 기준 — 앱 칸은 (854, 91)에서 시작, 화면 설계 좌표(1680×1208)에 더하면 됨 (v4 화면)
param([int]$Dest = 1)
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
function Tap([int]$x, [int]$y, [int]$waitMs = 1500) { & $adb shell input tap $x $y; Start-Sleep -Milliseconds $waitMs }
function Front { cmd /c "`"$adb`" shell am start -n com.recovlab.motionforecast/.MainActivity >nul 2>nul"; Start-Sleep 2 }

& $adb shell am force-stop com.recovlab.pleosdash      # 시험 앱이 위치 서비스를 먼저 쓰면 새 앱에 위치가 안 옴
& $adb shell am force-stop com.recovlab.motionforecast
& $adb shell am start -n com.recovlab.motionforecast/.MainActivity | Out-Null; Start-Sleep 6
Tap 2254 150 1200           # 목적지 알약 → 고르기
Tap 2200 (254 + 88 * $Dest) 15000   # 0 판교역 · 1 서울역 · 2 강릉역 (내비 앱이 앞으로 나옴)
Front
Tap 1111 1209 2000          # 탑승자: 아이 → "목소리 안내를 켤까요?"
Tap 1958 793 1500           # 켜기
Tap 2428 150 2000           # ⋮ 설정
Tap 1100 588 1000           # 시연 모드
Tap 959 144 1000            # ← 돌아가기
# 목소리 엔진 미리 깨우기 — 에뮬레이터를 막 켠 뒤 첫 말은 2초쯤 늦게 나옴(11-12 확인). 개발용 말하기라 횟수에 안 들어감
& $adb shell "am start --activity-single-top -n com.recovlab.motionforecast/.MainActivity --es say '굽은 길, 먼 곳을 함께 봐요.'" | Out-Null; Start-Sleep 5
& $adb logcat -d -s MotionForecast | Select-String -CaseSensitive -Pattern ': forecast |voice enabled|demoMode' | Select-Object -Last 3 | ForEach-Object { $_.Line -replace '^.*MotionForecast: ', '' }
