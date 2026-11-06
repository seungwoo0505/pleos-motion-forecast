# Replays a GPX track into the emulator with the console 'geo fix' command, one point per second.
# Used only if the emulator's own GPX playback (Extended controls > Location > Routes) cannot be driven.
param(
    [string]$Gpx = 'C:\Android\_logs\route_gwanghwamun_seoulstation.gpx',
    [double]$SpeedKmh = 40,
    [int]$IntervalMs = 1000
)
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
[xml]$x = Get-Content $Gpx -Encoding UTF8
$ns = New-Object Xml.XmlNamespaceManager($x.NameTable); $ns.AddNamespace('g', 'http://www.topografix.com/GPX/1/1')
$pts = $x.SelectNodes('//g:trkpt', $ns)
$knots = [math]::Round($SpeedKmh / 1.852, 2)
$i = 0
foreach ($p in $pts) {
    $i++
    $lat = $p.lat; $lon = $p.lon
    $v = if ($i -eq $pts.Count) { 0 } else { $knots }
    & $adb emu geo fix $lon $lat 30 10 $v | Out-Null
    "{0:HH:mm:ss} #{1}/{2} lat={3} lon={4} v={5}kn" -f (Get-Date), $i, $pts.Count, $lat, $lon, $v
    Start-Sleep -Milliseconds $IntervalMs
}
"done"
