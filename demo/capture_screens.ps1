# v4 화면 캡처 한 번에: 처음 화면 → 목적지 고르기 → 예보 카드 → 목소리 묻기 → 아이·목소리 켬 → 설정 → 예시 주행 40분 일기
# 좌표는 Pleos Connect Emulator v2.1 (2560x1440) 기준. 결과는 -OutDir 에 <Prefix>NN_이름.png
#  -Fc '|23.0|14|13.0|10|4080' 을 주면 예보 카드를 찍은 뒤, 전에 기록해 둔 경로 값으로 다시 그려서 나머지를 찍는다 (개발용 빌드, 값: 목적지|km|굽은 길|혼잡 km|가다 서다|초)
param([string]$OutDir = "$PSScriptRoot\..\_captures", [int]$Dest = 1, [string]$Prefix = '', [switch]$CardOnly, [string]$Fc = '')
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
New-Item -ItemType Directory -Force $OutDir | Out-Null
function Tap([int]$x, [int]$y, [int]$waitMs = 1500) { & $adb shell input tap $x $y; Start-Sleep -Milliseconds $waitMs }
function Front { cmd /c "`"$adb`" shell am start -n com.recovlab.motionforecast/.MainActivity >nul 2>nul"; Start-Sleep 3 }
function Shot([string]$name) { $f = Join-Path $OutDir ($Prefix + $name + '.png'); cmd /c "`"$adb`" exec-out screencap -p > `"$f`""; Write-Host $f }
$destName = @('판교역', '서울역', '강릉역')[$Dest]

& $adb shell am force-stop com.recovlab.pleosdash
& $adb shell am force-stop com.recovlab.motionforecast
& $adb shell am start -n com.recovlab.motionforecast/.MainActivity | Out-Null; Start-Sleep 6
if (-not $CardOnly) { Shot '00_처음화면' }
Tap 2254 150 1200                    # 목적지 알약
if (-not $CardOnly) { Shot '00_목적지_고르기' }
Tap 2200 (254 + 88 * $Dest) 15000    # 목적지 (내비 앱이 앞으로 나옴)
Front
Shot "01_예보카드_${destName}"
if ($Fc) { & $adb shell "am start --activity-single-top -n com.recovlab.motionforecast/.MainActivity --es fc '$Fc'" 2>$null | Out-Null; Start-Sleep 2; Shot "01_예보카드_${destName}_기록값" }
if ($CardOnly) { & $adb logcat -d -s MotionForecast | Select-String ': forecast ' | Select-Object -Last 1 | ForEach-Object { $_.Line -replace '^.*MotionForecast: ', '' }; return }
Tap 1111 1209 2000                   # 탑승자: 아이 → "목소리 안내를 켤까요?"
Shot '02_목소리안내_묻기'
Tap 1958 793 1500                    # 켜기
Shot "01_예보카드_${destName}_아이_목소리켬"
Tap 2428 150 2000                    # ⋮ 설정
Shot '04_설정'
Tap 1174 760 3500                    # 예시 주행 40분의 승차감 일기 보기
Shot '03_승차감일기_예시주행40분'
Tap 2347 1211 1500                   # 새 여행
& $adb logcat -d -s MotionForecast | Select-String ': forecast |예시 주행' | Select-Object -Last 2 | ForEach-Object { $_.Line -replace '^.*MotionForecast: ', '' }
