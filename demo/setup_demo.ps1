# 시연 준비: 앱 다시 켜기 → 목적지(서울역) → 탑승자 '아이' → 목소리 안내 켜기 → 설정에서 시연 모드
# 좌표는 Pleos Connect Emulator v2.1 (2560x1440) 기준
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
function Tap([int]$x, [int]$y, [int]$waitMs = 1500) { & $adb shell input tap $x $y; Start-Sleep -Milliseconds $waitMs }
function Front { cmd /c "`"$adb`" shell am start -n com.recovlab.motionforecast/.MainActivity >nul 2>nul"; Start-Sleep 2 }

& $adb shell am force-stop com.recovlab.pleosdash      # 시험 앱이 위치 서비스를 먼저 쓰면 새 앱에 위치가 안 옴
& $adb shell am force-stop com.recovlab.motionforecast
& $adb shell am start -n com.recovlab.motionforecast/.MainActivity | Out-Null; Start-Sleep 6
Tap 1119 260 15000          # 목적지: 서울역 (내비 앱이 앞으로 나옴)
Front
Tap 1052 326 2000           # 탑승자: 아이 → "목소리 안내를 켤까요?"
Tap 1694 730 1500           # 켜기
Tap 2455 164 2000           # ⋮ 설정
Tap 1694 441 1000           # 시연 모드
Tap 942 154 1000            # ← 돌아가기
& $adb logcat -d -s MotionForecast | Select-String -CaseSensitive -Pattern ': forecast |voice enabled|demoMode' | Select-Object -Last 3 | ForEach-Object { $_.Line -replace '^.*MotionForecast: ', '' }
