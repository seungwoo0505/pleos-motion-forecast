# 숏폼용 화면 녹화 — 긴 예보 카드, 버튼 누르는 장면, 승차감 일기, 한 번의 주행
# 전제: 에뮬레이터 켜짐(SolidWorks 꺼짐), adb forward tcp:8088 tcp:8088, 앱 설치됨
# 결과: <OutDir>\<장면>.webm (화면 2560x1440 + 소리) 과 <장면>.marks.txt (녹화 시작부터 몇 초에 무엇을 했는지)
# 좌표는 Pleos Connect Emulator v2.1 (2560x1440), 앱 칸은 (854, 91)에서 시작
param(
    [Parameter(Mandatory = $true)][string]$OutDir,        # 영문 경로만
    [string[]]$Seg = @('ui', 'levels', 'look', 'diary', 'drive'),
    [string]$Fc = '',                                     # '|23.0|14|13.0|10|4080' — 서울역 예보 카드를 전에 기록해 둔 경로 값으로 다시 그림 (개발용 빌드)
    [string]$Gpx = "$PSScriptRoot\route_gwanghwamun_seoulstation.gpx"
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$pkg = 'com.recovlab.motionforecast'
$vc = 'http://127.0.0.1:8088'
New-Item -ItemType Directory -Force $OutDir | Out-Null

function Tap([int]$x, [int]$y, [double]$waitSec = 1.5) { & $adb shell input tap $x $y; Start-Sleep -Milliseconds ([int]($waitSec * 1000)) }
function Front { cmd /c "`"$adb`" shell am start -n $pkg/.MainActivity >nul 2>nul"; Start-Sleep 3 }
function Put([string]$path) { try { Invoke-RestMethod -Method Put -Uri "$vc$path" -TimeoutSec 5 | Out-Null } catch { Write-Host "PUT $path failed: $_" } }
function Ctl([double]$speed, [double]$steer) { Put ("/vehicle/controls?speed={0}&accelerator=0&brake=0&steering={1}" -f [int][math]::Round($speed), [int][math]::Round($steer)) }

$script:sw = $null; $script:marks = $null
function RecStart([string]$name, [int]$limit) {
    $f = Join-Path $OutDir "$name.webm"; Remove-Item $f -ErrorAction SilentlyContinue
    $script:marks = Join-Path $OutDir "$name.marks.txt"; Set-Content -Path $script:marks -Value "# 녹화 시작부터의 초`t장면" -Encoding utf8
    & $adb emu screenrecord start --time-limit $limit $f | Out-Null
    $script:sw = [Diagnostics.Stopwatch]::StartNew()
    Start-Sleep -Milliseconds 1500     # 녹화 첫 1초는 압축 때문에 색이 덜 맞음 — 쓰지 않음
}
function Mark([string]$label) { $t = '{0:N1}' -f $script:sw.Elapsed.TotalSeconds; Add-Content -Path $script:marks -Value "$t`t$label" -Encoding utf8; Write-Host "  $t  $label" }
function RecStop { Mark '끝'; & $adb emu screenrecord stop | Out-Null; Start-Sleep 3 }

function Dev([string]$extra) { & $adb shell "am start --activity-single-top -n $pkg/.MainActivity $extra" 2>$null | Out-Null }   # 개발용 빌드에만 있는 기능
function Replay { if ($Fc) { Dev "--es fc '$Fc'"; Start-Sleep 2 } }
# 목적지 고르기: 0 판교역 · 1 서울역 · 2 강릉역 (내비 앱이 앞으로 나왔다가 돌아옴)
function PickDest([int]$i) { Tap 2254 150 2.0; Tap 2200 (254 + 88 * $i) 14; Front }

& $adb shell settings put system show_touches 1 | Out-Null      # 누르는 자리에 흰 점

if ($Seg -contains 'ui') {
    Write-Host '== ui: 처음 화면 → 목적지 고르기 → 예보 카드(길게) → 탑승자 · 목소리'
    & $adb shell am force-stop com.recovlab.pleosdash
    & $adb shell am force-stop $pkg
    & $adb shell am start -n $pkg/.MainActivity | Out-Null; Start-Sleep 7
    RecStart 'ui' 80
    Mark '처음 화면'; Start-Sleep 4
    Mark '목적지 알약 누름'; Tap 2254 150 2.5
    Mark '서울역 누름 (내비 앱이 앞으로)'; Tap 2200 342 14
    Front
    if ($Fc) { Mark '기록해 둔 경로 값으로 다시 그림'; Replay }
    Mark '예보 카드'; Start-Sleep 13
    Mark '아이 누름 → 목소리 묻기'; Tap 1111 1209 3.5
    Mark '켜기 누름'; Tap 1958 793 4.5
    Mark '어른 누름'; Tap 1250 1209 4
    Mark '어르신 누름'; Tap 1403 1209 4
    Mark '아이 누름'; Tap 1111 1209 4
    RecStop
}

if ($Seg -contains 'levels') {
    Write-Host '== levels: 다른 목적지의 예보 카드'
    RecStart 'levels' 80
    Mark '강릉역 고르기 시작'; Tap 2254 150 2.0; Tap 2200 430 14; Front
    Mark '강릉역 예보 카드'; Start-Sleep 11
    Mark '판교역 고르기 시작'; Tap 2254 150 2.0; Tap 2200 254 14; Front
    Mark '판교역 예보 카드'; Start-Sleep 11
    RecStop
}

if ($Seg -contains 'look') {
    Write-Host '== look: 단계별 모습 — 맑음 · 구름 · 울렁 · 출렁 (개발용 빌드, 숫자는 단계마다 달라 가림)'
    PickDest 1
    RecStart 'look' 50
    foreach ($lv in 0..3) { Dev "--ei look $lv"; Start-Sleep -Milliseconds 700; Mark ('단계 ' + $lv + ' ' + @('맑음', '구름', '울렁', '출렁')[$lv]); Start-Sleep 8 }
    RecStop
    Dev '--ei look -1'
}

if ($Seg -contains 'diary') {
    Write-Host '== diary: 예시 주행 40분 승차감 일기'
    RecStart 'diary' 45
    Mark '예보 카드'; Start-Sleep 2
    Mark '설정 누름'; Tap 2428 150 2.0
    Mark '예시 일기 누름'; Tap 1174 760 1.0
    Mark '승차감 일기(40분 예시)'; Start-Sleep 16
    Mark '새 여행 누름'; Tap 2347 1211 3
    RecStop
}

if ($Seg -contains 'drive') {
    Write-Host '== drive: 서울역 예보 → 주행(목소리) → 도착 확인 → 승차감 일기(길게)'
    & $adb shell settings put system show_touches 0 | Out-Null
    PickDest 1; Replay
    Tap 2428 150 2.0; Tap 1100 588 1.0; Tap 959 144 1.5        # 설정 → 시연 모드 → 돌아가기
    $beat = Start-Job -ScriptBlock { param($u) while ($true) { try { Invoke-RestMethod -Method Post -Uri "$u/browser/session?client=shortform-rec" -TimeoutSec 3 | Out-Null } catch {}; Start-Sleep -Milliseconds 600 } } -ArgumentList $vc
    Start-Sleep 2
    Put '/vehicle/gear?gear=Park'; Ctl 0 0
    Put '/vehicle/body?door_rl=false'; Put '/vehicle/body?door_fl=false'
    Put '/vehicle/seat?kind=occupied&value=true&area=rear_left'
    $gpxJob = Start-Process powershell -ArgumentList '-NoProfile', '-File', "$PSScriptRoot\play_route.ps1", '-Gpx', $Gpx -WindowStyle Hidden -PassThru
    Start-Sleep 2
    RecStart 'drive' 75
    Mark '예보 카드'; Start-Sleep 7
    Mark 'D — 출발'; Put '/vehicle/gear?gear=Drive'
    $t = [Diagnostics.Stopwatch]::StartNew()
    while ($t.Elapsed.TotalSeconds -lt 5) { Ctl ([math]::Min(24, $t.Elapsed.TotalSeconds * 4.0)) 0; Start-Sleep -Milliseconds 150 }
    Mark '굽은 길'; $t.Restart()
    while ($t.Elapsed.TotalSeconds -lt 6) { Ctl ([math]::Min(24, 20 + $t.Elapsed.TotalSeconds * 4.0)) (18 * [math]::Sin(2 * [math]::PI * 0.25 * $t.Elapsed.TotalSeconds)); Start-Sleep -Milliseconds 150 }
    Ctl 24 0; Start-Sleep 1
    Mark '멈춤'
    foreach ($s in 23, 21, 20, 18, 17, 15, 14, 12, 11, 9, 8, 6, 5, 3, 2, 0) { Ctl $s 0; Start-Sleep -Milliseconds 250 }
    Put '/vehicle/gear?gear=Park'; Mark 'P — 도착 확인'; Start-Sleep 1
    Put '/vehicle/body?door_rl=true'; Mark '뒷문 열림'
    Start-Sleep 6
    Mark '승차감 일기(이 무렵)'; Start-Sleep 17
    RecStop
    try { Stop-Process -Id $gpxJob.Id -Force } catch {}
    Put '/vehicle/body?door_rl=false'
    Stop-Job $beat; Remove-Job $beat -Force
}

& $adb shell settings put system show_touches 0 | Out-Null
Write-Host '끝'
