# 「승차감·흔들림 예보」 v5 시연 영상: 에뮬레이터 녹화(webm) → 1920×1080 자막판(mp4)
#  - 화면은 줄여서 위에 두고, 자막은 화면 아래 띠에 넣는다(앱 화면을 가리지 않게).
#  - 소리: 녹화 파일은 조용한 구간의 소리 조각이 빠져 있어 시간표(pts)대로 다시 놓는다(aresample async) → 키움.
#  - 색: 에뮬레이터 녹화는 색 표시가 없는 BT.601 → RGB로 바꿔 합성하고 BT.709로 표시한다(캡처와 색이 같게).
#  - 자막 시각(CAPS)은 녹화마다 다르다. 지금 값은 2026-11-14 녹화(v5_제출_04)의 것.
# 사용: python make_video.py <녹화.webm> <결과.mp4>   (이 파일이 있는 폴더에서 실행)
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = r"C:\Windows\Fonts\NotoSansKR-VF.ttf"
W, H = 1920, 1080
SCR_W, SCR_H = 1728, 972          # 2560×1440 녹화를 줄인 크기
BAND_Y, BAND_H = SCR_H, H - SCR_H  # 아래 띠 108 px
BG = (10, 16, 32)
TEAL, SLATE, AMBER = (0, 82, 255), (91, 97, 110), (244, 176, 0)   # TEAL 자리는 v5부터 파랑

# (시작, 끝, 단계 글자, 단계 색, 큰 줄, 작은 줄)
CAPS = [
    (0.0, 3.0, "출발 전 · P", TEAL, "오늘 길 흔들림 예보: 울렁", "서울역 23 km · 굽은 길 14번 · 1시간 8분 · 아이와 함께 챙길 것 3가지"),
    (3.0, 6.1, "주행 중 · D", SLATE, "출발하면 앱 화면은 잠겨요", "운전을 방해하지 않도록, 달리는 동안은 목소리만"),
    (6.1, 8.0, "주행 중 · D", AMBER, "“굽은 길, 먼 곳을 함께 봐요.”", "굽은 길 앞에서 목소리로 짧게 한 번"),
    (8.0, 19.2, "주행 중 · D", SLATE, "굽은 길 → 멈춤", "조향 · 브레이크 · 회전 안내 직전에는 말하지 않아요"),
    (19.2, 24.3, "도착 · P", TEAL, "도착해서 뒷문이 열리면", "승차감 일기가 열려요"),
    (24.3, 30.1, "도착 · P", TEAL, "승차감 일기: 예보 울렁 → 실제 울렁", "구간별 색 칸 · 가장 흔들린 곳 · 다음 여행 팁 (자체 기준 · 의학적 판정 아님)"),
]
NOTE = ("Pleos Connect 에뮬레이터 · Virtual Console 시뮬레이션 · 실제 화면 녹화", "예보 카드는 11월 12일 아침 9시의 서울역 경로 값")   # 오른쪽 위 두 줄


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_axes([weight])
    return f


def band(i, cap):
    _, _, phase, color, big, small = cap
    im = Image.new("RGB", (W, BAND_H), BG)
    d = ImageDraw.Draw(im)
    fp, fb, fs, fn = font(26, 700), font(38, 700), font(25, 500), font(18, 500)
    # 단계 알약
    pw = d.textlength(phase, font=fp) + 44
    d.rounded_rectangle((96, 30, 96 + pw, 78), radius=24, fill=color)
    d.text((96 + 22, 54), phase, font=fp, fill=(255, 255, 255) if color != AMBER else (42, 31, 0), anchor="lm")
    # 두 줄
    x = 96 + pw + 36
    d.text((x, 36), big, font=fb, fill=(255, 255, 255), anchor="lm")
    d.text((x, 80), small, font=fs, fill=(176, 196, 212), anchor="lm")
    # 오른쪽 작은 글
    d.text((W - 96, 42), NOTE[0], font=fn, fill=(120, 144, 164), anchor="rm")
    d.text((W - 96, 68), NOTE[1], font=fn, fill=(120, 144, 164), anchor="rm")
    p = os.path.join(HERE, f"band_{i}.png")
    im.save(p)
    return os.path.basename(p)


def main():
    src, out = sys.argv[1], sys.argv[2]
    bands = [band(i, c) for i, c in enumerate(CAPS)]
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", src]
    for b in bands:
        cmd += ["-loop", "1", "-framerate", "24", "-i", b]
    fc = [f"color=c=0x{BG[0]:02X}{BG[1]:02X}{BG[2]:02X}:s={W}x{H}:r=24,format=gbrp[bg]",
          f"[0:v]scale={SCR_W}:{SCR_H}:flags=lanczos:in_color_matrix=bt601:in_range=tv,format=gbrp[scr]",
          f"[bg][scr]overlay={(W - SCR_W) // 2}:0:shortest=1:format=gbrp[v0]"]
    last = "v0"
    for i, c in enumerate(CAPS):
        fc.append(f"[{last}][{i + 1}:v]overlay=0:{BAND_Y}:enable='between(t,{c[0]},{c[1]})':format=gbrp[v{i + 1}]")
        last = f"v{i + 1}"
    fc.append(f"[{last}]scale=out_color_matrix=bt709:out_range=tv,format=yuv420p[vout]")
    fc.append("[0:a]aresample=async=1000:first_pts=0,highpass=f=90,agate=threshold=0.003:ratio=8:attack=5:release=250,volume=22dB,alimiter=limit=0.9[a]")
    cmd += ["-filter_complex", ";".join(fc), "-map", "[vout]", "-map", "[a]", "-t", "30",
            "-c:v", "libx264", "-crf", "19", "-preset", "medium", "-pix_fmt", "yuv420p", "-r", "24",
            "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
            "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-movflags", "+faststart", out]
    subprocess.run(cmd, check=True, cwd=HERE)
    print("ok", out)


if __name__ == "__main__":
    main()
