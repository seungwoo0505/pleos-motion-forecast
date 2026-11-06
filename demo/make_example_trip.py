"""예시 주행 40분 (심사 캡처용, 시뮬레이션) — 잠실 → 올림픽대로 → 팔당 → 양평 두물머리.

출력
  app/src/main/assets/example_trip_40min.csv  (10 Hz: t_sec,speed_kmh,steer_deg,lat,lon,turn,brake)
  demo/example_trip_40min.gpx                  (1초 한 점, 에뮬레이터 geo fix 재생용)

구간 (가정값으로 만든 예시 — 실제 주행 기록 아님)
  0~8분   시내 가다 서다 (0↔35 km/h, 교차로 회전)
  8~20분  올림픽대로 (70~80 km/h, 거의 곧은 길)
  20~23분 정체 가다 서다 (0↔35 km/h 짧게)
  23~35분 강변 굽은 길 (40~50 km/h, 조향 좌우 반복)
  35~40분 마을길 (20~30 km/h, 도착)
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DT = 0.1
T_END = 40 * 60

# 대략의 경로 꼭짓점 (위도, 경도) — 지도와 정확히 같지 않음
WAYPOINTS = [
    (37.51330, 127.10010),  # 잠실
    (37.52150, 127.11450),
    (37.53650, 127.12900),  # 올림픽대로 강동
    (37.54500, 127.14500),
    (37.55900, 127.17400),  # 미사
    (37.56400, 127.19000),
    (37.55750, 127.21500),  # 팔당대교 북단
    (37.55300, 127.22500),
    (37.54400, 127.24300),  # 팔당 강변
    (37.53500, 127.25600),
    (37.52400, 127.28000),  # 팔당댐
    (37.52950, 127.29400),
    (37.53600, 127.30500),
    (37.53900, 127.31500),  # 양수리 두물머리
]


def haversine(a, b):
    r = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


SEGS = [haversine(WAYPOINTS[i], WAYPOINTS[i + 1]) for i in range(len(WAYPOINTS) - 1)]
ROUTE_LEN = sum(SEGS)


def point_at(dist):
    d = max(0.0, min(dist, ROUTE_LEN - 0.01))
    for i, s in enumerate(SEGS):
        if d <= s:
            f = d / s
            a, b = WAYPOINTS[i], WAYPOINTS[i + 1]
            return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        d -= s
    return WAYPOINTS[-1]


def stop_go(t, period, top, acc_s, brake_s, cruise_s):
    """한 주기: 가속 → 순항 → 감속 → 정지"""
    x = t % period
    if x < acc_s:
        return top * x / acc_s, False
    if x < acc_s + cruise_s:
        return top + 2.0 * math.sin(2 * math.pi * 0.4 * x), False
    if x < acc_s + cruise_s + brake_s:
        return top * (1 - (x - acc_s - cruise_s) / brake_s), True
    return 0.0, True


def ramp(x, a, b, v0, v1):
    """x가 a→b 사이일 때 v0→v1로 부드럽게(코사인)"""
    k = min(1.0, max(0.0, (x - a) / (b - a)))
    return v0 + (v1 - v0) * (1 - math.cos(math.pi * k)) / 2


def profile(t):
    """returns speed_kmh, steer_deg, turn(0/1), brake(0/1)"""
    if t < 480:                                   # 시내 가다 서다(가속 9초 · 순항 9초 · 감속 8초 · 정지 4초) + 교차로 회전
        x = t % 30.0
        if x < 9:
            v, br = ramp(x, 0, 9, 0, 32), 0
        elif x < 18:
            v, br = 32 + 1.0 * math.sin(2 * math.pi * 0.3 * x), 0
        elif x < 26:
            v, br = ramp(x, 18, 26, 32, 0), 1
        else:
            v, br = 0.0, 1
        steer, turn = 3 * math.sin(2 * math.pi * 0.05 * t), 0
        if int(t // 30) % 3 == 1 and 0.5 <= x < 6.5:   # 세 번에 한 번, 출발하며 우회전 (속도 낮을 때)
            steer = 200 * math.sin(math.pi * (x - 0.5) / 6.0)
            turn = 1
        return v, steer, turn, br
    if t < 1200:                                  # 올림픽대로: 거의 곧은 길 + 가끔 차로 바꾸기
        v = ramp(t, 480, 505, 0, 75) if t < 505 else 75 + 3 * math.sin(2 * math.pi * t / 140.0)
        steer = 1.5 * math.sin(2 * math.pi * 0.03 * t)
        x = t % 120.0
        if 40 <= x < 46:
            steer += 3.0 * math.sin(2 * math.pi * (x - 40) / 6.0)
        return v, steer, int(40 <= x < 46), 0
    if t < 1380:                                  # 정체 가다 서다(가속 5 · 순항 2 · 감속 5 · 정지 4초)
        x = (t - 1200) % 16.0
        if x < 5:
            v, br = ramp(x, 0, 5, 0, 32), 0
        elif x < 7:
            v, br = 32.0, 0
        elif x < 12:
            v, br = ramp(x, 7, 12, 32, 0), 1
        else:
            v, br = 0.0, 1
        if t < 1220:
            v = ramp(t, 1200, 1220, 75, 30)   # 고속도로 → 정체: 20초에 걸쳐 감속
        return v, 2 * math.sin(2 * math.pi * 0.05 * t), 0, br
    if t < 2100:                                  # 강변 굽은 길
        v = 42 + 4 * math.sin(2 * math.pi * t / 60.0)
        if t < 1395:
            v = ramp(t, 1380, 1395, 0, 42)   # 정체 → 강변길: 15초에 걸쳐 가속
        steer = 25 * math.sin(2 * math.pi * 0.12 * t) + 8 * math.sin(2 * math.pi * 0.3 * t + 1.0)
        return v, steer, 0, 0
    # 마을길, 마지막 15초에 정지
    v = 27 + 3 * math.sin(2 * math.pi * t / 45.0)
    if t < 2110:
        v = ramp(t, 2100, 2110, 42, 27)
    if t > T_END - 15:
        v = ramp(t, T_END - 15, T_END, v, 0)
    steer = 12 * math.sin(2 * math.pi * 0.08 * t)
    return v, steer, 0, int(t > T_END - 15)


def main():
    csv_path = os.path.join(ROOT, "app", "src", "main", "assets", "example_trip_40min.csv")
    gpx_path = os.path.join(HERE, "example_trip_40min.gpx")
    os.makedirs(os.path.dirname(csv_path), exist_ok=True)
    n = int(T_END / DT)
    prof = [profile(i * DT) for i in range(n + 1)]
    total = sum(max(0.0, p[0]) / 3.6 * DT for p in prof)
    scale = ROUTE_LEN / total            # 달린 거리를 경로 길이에 맞춤 (끝에서 멈춰 서지 않게)
    dist = 0.0
    rows = ["t_sec,speed_kmh,steer_deg,lat,lon,turn,brake"]
    gpx_pts = []
    for i, (v, steer, turn, br) in enumerate(prof):
        t = i * DT
        v = max(0.0, v)
        la, lo = point_at(dist * scale)
        rows.append(f"{t:.1f},{v:.2f},{steer:.1f},{la:.6f},{lo:.6f},{turn},{br}")
        if i % 10 == 0:
            gpx_pts.append((t, la, lo))
        dist += v / 3.6 * DT
    with open(csv_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(rows) + "\n")
    with open(gpx_path, "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="RECOVLAB motion-forecast example" xmlns="http://www.topografix.com/GPX/1/1">\n')
        f.write("  <trk><name>예시 주행 40분 (시뮬레이션): 잠실 → 양평 두물머리</name><trkseg>\n")
        for t, la, lo in gpx_pts:
            s = int(t)
            f.write(f'    <trkpt lat="{la:.6f}" lon="{lo:.6f}"><time>2026-11-07T00:{s // 60:02d}:{s % 60:02d}Z</time></trkpt>\n')
        f.write("  </trkseg></trk>\n</gpx>\n")
    print(f"rows={len(rows) - 1} route_len={ROUTE_LEN / 1000:.1f} km driven={dist / 1000:.1f} km -> {csv_path}, {gpx_path}")


if __name__ == "__main__":
    sys.exit(main())
