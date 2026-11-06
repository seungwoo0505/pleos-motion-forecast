# Builds a 1-point-per-second GPX along Sejong-daero (Gwanghwamun -> City Hall -> Sungnyemun -> Seoul Station).
param(
    [string]$Out = 'C:\Android\_logs\route_gwanghwamun_seoulstation.gpx',
    [double]$SpeedKmh = 40
)
$wp = @(
    @(37.575940, 126.976850, '광화문 앞'),
    @(37.571000, 126.976900, '광화문광장'),
    @(37.569900, 126.977050, '세종대로 사거리'),
    @(37.566300, 126.977900, '서울시청 앞'),
    @(37.562600, 126.976800, '북창동 앞'),
    @(37.560000, 126.975300, '숭례문'),
    @(37.556600, 126.973300, '서울역 버스환승센터'),
    @(37.554648, 126.970702, '서울역')
)
function Dist($a1, $o1, $a2, $o2) {
    $R = 6371000.0; $d2r = [math]::PI / 180
    $dLat = ($a2 - $a1) * $d2r; $dLon = ($o2 - $o1) * $d2r
    $h = [math]::Sin($dLat/2)*[math]::Sin($dLat/2) + [math]::Cos($a1*$d2r)*[math]::Cos($a2*$d2r)*[math]::Sin($dLon/2)*[math]::Sin($dLon/2)
    return 2 * $R * [math]::Asin([math]::Sqrt($h))
}
$v = $SpeedKmh / 3.6
$pts = New-Object System.Collections.Generic.List[object]
$pts.Add(@($wp[0][0], $wp[0][1]))
$carry = 0.0; $total = 0.0
for ($i = 0; $i -lt $wp.Count - 1; $i++) {
    $a = $wp[$i]; $b = $wp[$i+1]
    $seg = Dist $a[0] $a[1] $b[0] $b[1]; $total += $seg
    $s = $v - $carry
    while ($s -le $seg) {
        $t = $s / $seg
        $pts.Add(@(($a[0] + ($b[0]-$a[0])*$t), ($a[1] + ($b[1]-$a[1])*$t)))
        $s += $v
    }
    $carry = $seg - ($s - $v)
}
$pts.Add(@($wp[-1][0], $wp[-1][1]))
$start = [datetime]::SpecifyKind([datetime]'2026-11-06T05:00:00', 'Utc')
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
[void]$sb.AppendLine('<gpx version="1.1" creator="RECOVLAB pleos location test" xmlns="http://www.topografix.com/GPX/1/1">')
foreach ($w in $wp) { [void]$sb.AppendLine(('  <wpt lat="{0:F6}" lon="{1:F6}"><name>{2}</name></wpt>' -f $w[0], $w[1], $w[2])) }
[void]$sb.AppendLine('  <trk><name>광화문-서울역 세종대로 40km/h</name><trkseg>')
for ($k = 0; $k -lt $pts.Count; $k++) {
    $tm = $start.AddSeconds($k).ToString('yyyy-MM-ddTHH:mm:ssZ')
    [void]$sb.AppendLine(('    <trkpt lat="{0:F6}" lon="{1:F6}"><ele>30</ele><time>{2}</time></trkpt>' -f $pts[$k][0], $pts[$k][1], $tm))
}
[void]$sb.AppendLine('  </trkseg></trk>')
[void]$sb.AppendLine('</gpx>')
[IO.File]::WriteAllText($Out, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
"points=$($pts.Count) total_m=$([int]$total) seconds=$($pts.Count - 1) -> $Out"
