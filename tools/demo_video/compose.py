"""시험 주행 녹화를 한 화면으로 합쳐 시연 영상을 만듭니다.

- 왼쪽 위: 에뮬레이터 화면 녹화
- 오른쪽 위: 앱 기록에서 읽은 다가오는 사건과 앱이 한 일
- 아래: 가상 콘솔 패널 캡처
- 소리: 앱 기록의 효과음 시각에 앱이 쓰는 소리 파일을 그대로 넣습니다. 말은 자막으로만 보여 줍니다.
조용한 구간은 4배속으로 넘기고 화면에 표시합니다.
"""
import os
import re
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# 녹화 폴더. record.sh가 만든 log.txt, offset.txt, rec_start.txt, frames/, emu/, card.png가 있어야 합니다.
HERE = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
RAW = Path(__file__).resolve().parents[2] / "app/src/main/res/raw"
FF = os.environ.get("FFMPEG", "ffmpeg")  # libx264가 있는 ffmpeg
FPS = 15
W, H = 1920, 1080
SR = 22050

TITLE = "/root/.fonts/Paperlogy-7Bold.ttf"
BODY = "/root/.fonts/NotoSansCJKkr-Regular.otf"
BOLD = "/root/.fonts/NotoSansCJKkr-Bold.otf"


def font(path, size):
    return ImageFont.truetype(path, size)


BG = (13, 20, 23)
PANEL = (21, 31, 35)
LINE = (44, 60, 66)
FG = (228, 236, 238)
MUTED = (140, 160, 166)
ACCENT = (82, 196, 205)

# ---------- 시각 맞추기 ----------
offset = float((HERE / "offset.txt").read_text())          # 에뮬레이터 시계 - 서버 시계
rec_start = float((HERE / "rec_start.txt").read_text())     # 화면 녹화 시작, 서버 시계


def tau_of_emu(t):
    """에뮬레이터 시각을 녹화 영상 안의 초로 바꿉니다."""
    return t - offset - rec_start


# ---------- 앱 기록 읽기 ----------
NAMES = {
    "SPEED_BUMP": "과속방지턱", "ROAD_DAMAGE": "도로 파손", "DOWNHILL": "내리막", "SHARP_TURN": "급회전",
    "U_TURN": "유턴", "ROUNDABOUT": "회전교차로", "TUNNEL": "터널", "UNDERPASS": "지하차도",
    "RAILROAD_CROSSING": "철도 건널목", "CONSTRUCTION": "공사 구간", "TOLLGATE": "톨게이트",
    "REST_AREA": "휴게소", "ARRIVAL": "도착", "REROUTE": "경로 변경", "TUNNEL_EXIT": "터널 출구",
}
CUE_FILE = {
    "SPEED_BUMP": "motion", "ROAD_DAMAGE": "motion", "DOWNHILL": "motion", "SHARP_TURN": "motion",
    "U_TURN": "motion", "ROUNDABOUT": "motion", "TUNNEL": "light_and_sound", "UNDERPASS": "light_and_sound",
    "RAILROAD_CROSSING": "sound", "CONSTRUCTION": "sound", "TOLLGATE": "stop", "REST_AREA": "stop",
    "ARRIVAL": "stop", "REROUTE": "change",
}
CUE_LABEL = {"motion": "덜컹·쏠림 소리", "light_and_sound": "터널 소리", "sound": "시끄러운 길 소리",
             "stop": "멈춤 소리", "change": "경로 바뀜 소리"}
COLORS = {"ffffb300": ("주황", (255, 179, 0)), "ff2979ff": ("파랑", (41, 121, 255)),
          "ffaa00ff": ("보라", (170, 0, 255)), "ff00e676": ("초록", (0, 230, 118)),
          "ff00e5ff": ("평소 색", (0, 229, 255))}

lines = (HERE / "log.txt").read_text(errors="ignore").splitlines()
snapshots = []   # (tau, [(name, meters)])
actions = []     # (tau, kind, text)
cues = []        # (tau, file)
light = [(0.0, COLORS["ff00e5ff"])]
for ln in lines:
    if "SensoryForecast" not in ln:
        continue
    t = float(ln.split()[0])
    msg = ln[ln.index("SensoryForecast:") + len("SensoryForecast:"):].strip()
    tau = tau_of_emu(t)
    if msg.startswith("사건 "):
        evs = [(m.group(1), int(m.group(2))) for m in re.finditer(r"([A-Z_]+)@(\d+)m", msg)]
        snapshots.append((tau, evs))
    elif msg.startswith("말: "):
        actions.append((tau, "speech", msg[3:]))
    elif msg.startswith("효과음: "):
        k = msg.split(": ")[1]
        cues.append((tau, CUE_FILE[k]))
        actions.append((tau, "cue", CUE_LABEL[CUE_FILE[k]]))
    elif msg.startswith("조명: "):
        c = msg.split()[-1]
        name, rgb = COLORS.get(c, (c, (200, 200, 200)))
        light.append((tau, (name, rgb)))
        actions.append((tau, "light", f"아이 자리 조명 {name}" if name != "평소 색" else "조명 평소 색으로"))
    elif msg.startswith("창문 닫기"):
        actions.append((tau, "car", "뒷좌석 오른쪽 창문 닫기"))
    elif msg == "내기 순환: true":
        actions.append((tau, "car", "내기 순환 켜기"))
    elif msg.startswith("내기 순환 되돌리기"):
        actions.append((tau, "car", "터널 지나 내기 순환 원래대로"))
    elif msg.startswith("바람 결과"):
        actions.append((tau, "car", f"바람 {msg.split(': ')[1]}단으로 줄이기"))

demo_start = snapshots[0][0]
demo_end = max(a[0] for a in actions) + 3.5

# ---------- 시간 줄이기: 일이 있는 구간 앞뒤만 제 속도 ----------
keep = []
for a in actions:
    keep.append((a[0] - 9.5, a[0] + 3.5))
keep.append((demo_start - 1.5, demo_start + 3))
keep.sort()
merged = []
for s, e in keep:
    if merged and s <= merged[-1][1]:
        merged[-1] = (merged[-1][0], max(merged[-1][1], e))
    else:
        merged.append((s, e))
FAST = 4.0
segments = []    # (src_start, src_end, speed)
cursor = demo_start - 1.5
for s, e in merged:
    s = max(s, cursor)
    if s > cursor:
        segments.append((cursor, s, FAST))
    segments.append((s, e, 1.0))
    cursor = e
if demo_end > cursor:
    segments.append((cursor, demo_end, FAST))

demo_times = []  # 출력 프레임마다 (원본 초, 빨리 감기 여부)
for s, e, sp in segments:
    n = int(round((e - s) / sp * FPS))
    for i in range(n):
        demo_times.append((s + i * sp / FPS, sp > 1))


def out_time_of(tau):
    t = 0.0
    for s, e, sp in segments:
        if tau < e:
            return t + max(0.0, tau - s) / sp
        t += (e - s) / sp
    return t


# ---------- 재료 ----------
emu_frames = sorted((HERE / "emu").glob("e_*.jpg"))
con_frames = sorted((HERE / "frames").glob("c_*.jpg"), key=lambda p: int(p.stem[2:]))
con_taus = np.array([int(p.stem[2:]) / 1000 - rec_start for p in con_frames])
CON_BOX = (340, 600, 1910, 1050)

f_title = font(TITLE, 30)
f_small = font(BODY, 22)
f_body = font(BODY, 26)
f_bold = font(BOLD, 28)
f_speech = font(BOLD, 40)
f_big = font(TITLE, 88)
f_mid = font(BODY, 34)

EMU_BOX = (24, 72, 24 + 1008, 72 + 567)
INFO_BOX = (1056, 72, 1896, 639)
CON_Y = 655
con_w = int(1570 * (H - CON_Y - 16) / 450)
CON_POS = ((W - con_w) // 2, CON_Y)


def last_before(items, tau):
    best = None
    for it in items:
        if it[0] <= tau:
            best = it
    return best


def draw_demo(tau, fast):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((24, 18), "감각 예보 · 시험 주행", font=f_title, fill=FG)
    d.text((W - 24, 24), "Pleos Connect 에뮬레이터 v2.1.0 · 가상 콘솔 녹화 · 시험 입력 시속 72km", font=f_small, fill=MUTED, anchor="ra")
    if fast:
        d.rounded_rectangle((760, 14, 1010, 56), 10, fill=(60, 52, 20))
        d.text((885, 35), "빨리 감기 4배", font=f_small, fill=(255, 214, 120), anchor="mm")

    # 에뮬레이터
    idx = min(len(emu_frames) - 1, max(0, int(round(tau * FPS))))
    emu = Image.open(emu_frames[idx]).resize((EMU_BOX[2] - EMU_BOX[0], EMU_BOX[3] - EMU_BOX[1]), Image.BILINEAR)
    im.paste(emu, EMU_BOX[:2])
    d.rectangle(EMU_BOX, outline=LINE, width=2)

    # 앱 기록
    x0, y0, x1, y1 = INFO_BOX
    d.rounded_rectangle(INFO_BOX, 14, fill=PANEL, outline=LINE, width=2)
    d.text((x0 + 24, y0 + 18), "다가오는 사건", font=f_bold, fill=ACCENT)
    snap = last_before(snapshots, tau)
    evs = [e for e in (snap[1] if snap else []) if e[0] != "TUNNEL_EXIT"][:4]
    y = y0 + 62
    for k, m in evs:
        d.text((x0 + 24, y), NAMES.get(k, k), font=f_body, fill=FG)
        d.text((x1 - 24, y), f"{m:,}m", font=f_body, fill=FG, anchor="ra")
        y += 38
    if not evs:
        d.text((x0 + 24, y), "없음", font=f_body, fill=MUTED)

    yl = y0 + 230
    d.line((x0 + 24, yl, x1 - 24, yl), fill=LINE, width=2)
    d.text((x0 + 24, yl + 14), "앱이 한 일", font=f_bold, fill=ACCENT)
    # 아이 자리 조명 색
    lt = last_before(light, tau)[1]
    d.text((x1 - 70, yl + 18), "조명", font=f_small, fill=MUTED, anchor="ra")
    d.rounded_rectangle((x1 - 60, yl + 14, x1 - 24, yl + 50), 8, fill=lt[1])

    done = [a for a in actions if a[0] <= tau and a[1] != "speech"]
    y = yl + 64
    for a in reversed(done[-6:]):
        age = tau - a[0]
        col = FG if age < 4 else MUTED
        d.text((x0 + 24, y), a[2], font=f_body, fill=col)
        y += 38

    # 말 자막
    sp = last_before([a for a in actions if a[1] == "speech"], tau)
    if sp and tau - sp[0] < 5:
        text = f"말  “{sp[2]}”"
        tw = d.textlength(text, font=f_speech)
        bx = EMU_BOX[0] + (EMU_BOX[2] - EMU_BOX[0] - tw) / 2
        by = EMU_BOX[3] - 92
        d.rounded_rectangle((bx - 24, by - 12, bx + tw + 24, by + 60), 14, fill=(0, 0, 0))
        d.text((bx, by), text, font=f_speech, fill=(255, 255, 255))

    # 가상 콘솔
    j = int(np.argmin(np.abs(con_taus - tau)))
    con = Image.open(con_frames[j]).crop(CON_BOX).resize((con_w, H - CON_Y - 16), Image.BILINEAR)
    im.paste(con, CON_POS)
    lx = CON_POS[0] - 24
    d.text((lx, CON_Y + 8), "가상 콘솔", font=f_bold, fill=ACCENT, anchor="ra")
    for i, ln in enumerate(["차 상태를", "그대로 보여 줍니다"]):
        d.text((lx, CON_Y + 52 + i * 32), ln, font=f_small, fill=MUTED, anchor="ra")
    rx = CON_POS[0] + con_w + 24
    for i, (where, what) in enumerate([("왼쪽 아래", "실내 조명 색"), ("가운데", "내기 순환·바람"), ("오른쪽 아래", "창문")]):
        d.text((rx, CON_Y + 12 + i * 76), where, font=f_small, fill=MUTED)
        d.text((rx, CON_Y + 42 + i * 76), what, font=f_small, fill=FG)
    return im


def card(title, sub, note=None):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((W / 2, 400), title, font=f_big, fill=FG, anchor="mm")
    d.text((W / 2, 520), sub, font=f_mid, fill=ACCENT, anchor="mm")
    if note:
        for i, ln in enumerate(note):
            d.text((W / 2, 640 + i * 44), ln, font=f_small, fill=MUTED, anchor="mm")
    return im


def child_card_scene():
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((24, 18), "감각 예보 · 아이 카드", font=f_title, fill=FG)
    shot = Image.open(HERE / "card.png").convert("RGB").resize((1632, 918), Image.LANCZOS)
    im.paste(shot, ((W - 1632) // 2, 72))
    d.text((W / 2, 1030), "부모가 차가 서 있을 때 아이 자리, 미리 알릴 자극, 알리는 방식을 정합니다.", font=f_mid, fill=FG, anchor="mm")
    return im


def main():
    out = HERE / "out"
    out.mkdir(exist_ok=True)
    enc = subprocess.Popen(
        [FF, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
         "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", str(out / "video.mp4")],
        stdin=subprocess.PIPE)
    total = 0

    def put(img, seconds):
        nonlocal total
        b = img.tobytes()
        for _ in range(int(seconds * FPS)):
            enc.stdin.write(b)
            total += 1

    intro = card("감각 예보", "길 위 자극을 차가 먼저 알고, 아이에게 미리 알리고, 차 안을 미리 바꿉니다",
                 ["Pleos Connect 에뮬레이터 v2.1.0과 가상 콘솔에서 녹화한 시험 주행입니다.",
                  "앱이 하는 말은 자막으로 보여 줍니다. 효과음은 앱이 낸 소리 그대로입니다."])
    put(intro, 5)
    put(child_card_scene(), 5)
    demo_offset = total / FPS
    for tau, fast in demo_times:
        enc.stdin.write(draw_demo(tau, fast).tobytes())
        total += 1
    outro = card("감각 예보", "차가 먼저 알고, 아이에게 미리 말하고, 차 안을 미리 바꿉니다",
                 ["과속방지턱 · 터널 · 공사 구간 · 도착을 내비 안내 거리와 속도로 미리 알립니다.",
                  "창문 · 내기 순환 · 바람 · 아이 자리 조명은 Pleos Vehicle SDK로 바꿉니다."])
    put(outro, 5)
    enc.stdin.close()
    enc.wait()

    # 소리
    audio = np.zeros(int(total / FPS * SR) + SR)
    for tau, name in cues:
        with wave.open(str(RAW / f"cue_{name}.wav")) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32767
        i = int((demo_offset + out_time_of(tau)) * SR)
        audio[i:i + len(x)] += x[: len(audio) - i]
    pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2")
    with wave.open(str(out / "audio.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    subprocess.run([FF, "-y", "-v", "error", "-i", str(out / "video.mp4"), "-i", str(out / "audio.wav"),
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-shortest", str(out / "sensory-forecast-demo.mp4")],
                   check=True)
    print("프레임", total, "길이", round(total / FPS, 1), "초, 효과음", len(cues), "번")
    for s, e, sp in segments:
        print(f"  {s:6.1f}~{e:6.1f}초 {'4배' if sp > 1 else '제 속도'}")


if __name__ == "__main__":
    main()
