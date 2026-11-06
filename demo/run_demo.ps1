# 「멀미 예보」 30초 시연 — Virtual Console API + GPX 재생 + (선택) 화면 녹화
# 전제: 에뮬레이터 켜짐, adb forward tcp:8088 tcp:8088, 앱에서 목적지·시연 모드를 미리 골라 둠
param(
    [switch]$Record,
    [string]$EmuWebm = '',   # 에뮬레이터 자체 녹화(소리 포함, webm). 경로는 영문만
    [string]$OutMp4 = '',
    [string]$Gpx = "$PSScriptRoot\route_gwanghwamun_seoulstation.gpx"
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$vc = 'http://127.0.0.1:8088'
function Put([string]$path) { try { Invoke-RestMethod -Method Put -Uri "$vc$path" -TimeoutSec 5 | Out-Null } catch { Write-Host "PUT $path failed: $_" } }
function Ctl([double]$speed, [double]$steer) { Put ("/vehicle/controls?speed={0}&accelerator=0&brake=0&steering={1}" -f [int][math]::Round($speed), [int][math]::Round($steer)) }   # VC는 정수 속도만 받음 (invalid_speed)
# VC는 브라우저 페이지의 1초 heartbeat(POST /browser/session)가 있어야 명령을 받음 → 스크립트가 직접 보냄
$beat = Start-Job -ScriptBlock { param($u) while ($true) { try { Invoke-RestMethod -Method Post -Uri "$u/browser/session?client=motion-demo-script" -TimeoutSec 3 | Out-Null } catch {}; Start-Sleep -Milliseconds 600 } } -ArgumentList $vc
Start-Sleep 2
function Stamp([string]$m) { Write-Host ("{0:HH:mm:ss.fff}  {1}" -f (Get-Date), $m) }

# 준비: P, 정지, 문 닫힘, 뒷좌석 왼쪽에 아이(VC Occupied), 앱을 앞으로
Put '/vehicle/gear?gear=Park'; Ctl 0 0
Put '/vehicle/body?door_rl=false'; Put '/vehicle/body?door_fl=false'
Put '/vehicle/seat?kind=occupied&value=true&area=rear_left'
cmd /c ""$adb" shell input tap 1693 1030 >nul 2>nul"   # 일기 화면이면 '새 여행' (출발 전 화면으로)
cmd /c ""$adb" shell am start -n com.recovlab.motionforecast/.MainActivity >nul 2>nul"

# 위치: GPX를 1초에 한 점씩 (에뮬레이터 콘솔 geo fix)
$gpxJob = Start-Process powershell -ArgumentList '-NoProfile', '-File', "$PSScriptRoot\play_route.ps1", '-Gpx', $Gpx -WindowStyle Hidden -PassThru
Start-Sleep 2

if ($EmuWebm) {
    Remove-Item $EmuWebm -ErrorAction SilentlyContinue
    & $adb emu screenrecord start --time-limit 30 $EmuWebm | Out-Null   # 에뮬레이터가 PC에 직접 기록: 화면 2560x1440 + 소리
    Start-Sleep -Milliseconds 800
}
if ($Record) {
    & $adb shell rm -f /sdcard/motion_demo.mp4 | Out-Null
    $rec = Start-Process $adb -ArgumentList 'shell', 'screenrecord', '--time-limit', '30', '--size', '1920x1080', '--bit-rate', '8000000', '/sdcard/motion_demo.mp4' -WindowStyle Hidden -PassThru
    Start-Sleep -Milliseconds 800
}
$t0 = Get-Date
Stamp 't0  P — 출발 전 날씨 카드'
Start-Sleep 3

Stamp 'D — 출발'
Put '/vehicle/gear?gear=Drive'
# 흔들림 9초: 가다 서다(±3 km/h, 0.2 Hz) + 굽은 길(조향 ±40°, 0.25 Hz)
$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt 9) {
    $t = $sw.Elapsed.TotalSeconds
    $speed = [math]::Min(30, $t * 6) + 3 * [math]::Sin(2 * [math]::PI * 0.2 * $t)
    $steer = 40 * [math]::Sin(2 * [math]::PI * 0.25 * $t)
    Ctl ([math]::Max(0, $speed)) $steer
    Start-Sleep -Milliseconds 150
}
Stamp '잔잔 5초 — 조향 0, 30 km/h (목소리 나올 때)'
$sw.Restart()
while ($sw.Elapsed.TotalSeconds -lt 5) { Ctl 30 0; Start-Sleep -Milliseconds 200 }

Stamp '멈춤 → P'
foreach ($s in 28, 25, 23, 20, 18, 15, 13, 10, 8, 5, 3, 0) { Ctl $s 0; Start-Sleep -Milliseconds 250 }   # 3초에 걸쳐 정지 (약 2.8 m/s²)
Put '/vehicle/gear?gear=Park'
Start-Sleep 1
Stamp '뒷문(왼쪽) 열림'
Put '/vehicle/body?door_rl=true'
$left = 30 - ((Get-Date) - $t0).TotalSeconds
Stamp ("일기 화면 기다림 {0:N1}초" -f $left)
if ($left -gt 0) { Start-Sleep -Milliseconds ([int]($left * 1000)) }

if ($Record) {
    $rec.WaitForExit(15000) | Out-Null
    Start-Sleep 2
    if ($OutMp4) { & $adb pull /sdcard/motion_demo.mp4 $OutMp4 | Out-Null; Stamp "영상 저장 $OutMp4" }
}
if ($EmuWebm) { Start-Sleep 3; Stamp ("에뮬레이터 녹화 {0}" -f $EmuWebm) }
try { Stop-Process -Id $gpxJob.Id -Force } catch {}
Put '/vehicle/body?door_rl=false'
Stop-Job $beat; Remove-Job $beat -Force
Stamp '끝'
