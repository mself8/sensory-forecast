"""시험 주행 녹화를 한 화면으로 합쳐 시연 영상을 만듭니다.

- 왼쪽: 에뮬레이터 화면 녹화. 가상 콘솔이 기어 D라 주행 화면이 나오고, 앱 화면은 잠깁니다.
- 오른쪽 위: 경로 지도. 앱 기록의 남은 거리로 차 위치를 정합니다. route_map.py 참고.
- 오른쪽 아래: 차 안 상태. 앱이 Vehicle SDK에서 돌려받은 결과 값으로 씁니다.
- 아래 자막 줄: 앱이 하는 말, 효과음, 차 안 변화를 한 번에 한 줄씩 보여 줍니다.
- 소리: 앱이 하는 말은 record.sh가 에뮬레이터 안에서 받은 소리(cap.raw)를 씁니다. 효과음은 그 방식에 잡히지 않아
  앱 기록의 효과음 시각에 앱이 쓰는 소리 파일을 넣습니다. 받은 소리가 없으면 말은 자막으로만 보여 줍니다.
조용한 구간은 빨리 넘기고 화면에 배속을 표시합니다.
"""
import json
import os
import re
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from route_map import DATA as ROUTE, RouteMap

# 녹화 폴더. record.sh가 만든 log.txt, offset.txt, rec_start.txt, emu/, card.png가 있어야 합니다.
HERE = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
RAW = Path(__file__).resolve().parents[2] / "app/src/main/res/raw"
FF = os.environ.get("FFMPEG", "ffmpeg")  # libx264가 있는 ffmpeg
FPS = 15
W, H = 1920, 1080
SR = 48000

REGULAR = "/root/.fonts/Paperlogy-4Regular.ttf"
SEMI = "/root/.fonts/Paperlogy-6SemiBold.ttf"
BOLD = "/root/.fonts/Paperlogy-7Bold.ttf"


def font(path, size):
    return ImageFont.truetype(path, size)


BG = (12, 17, 20)
CARD = (20, 28, 32)
LINE = (38, 52, 58)
FG = (232, 238, 240)
MUTED = (138, 156, 162)
ACCENT = (82, 196, 205)
AMBER = (255, 196, 92)


def mix(a, b, t):
    """색 a에서 b로 t만큼 옮긴 색입니다."""
    return tuple(int(round(x + (y - x) * t)) for x, y in zip(a, b))


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
SEAT_NAMES = {"REAR_RIGHT": "뒷좌석 오른쪽", "REAR_LEFT": "뒷좌석 왼쪽", "FRONT_RIGHT": "조수석"}

# 차 안 상태. 시작 값은 capture.cjs가 녹화 전에 가상 콘솔에 넣는 값입니다.
light = [(-1e9, COLORS["ff00e5ff"])]
window = [(-1e9, "열림 50%")]
air = [(-1e9, "외기 유입")]
window_label = "뒷좌석 오른쪽 창문"
snapshots = []   # (tau, [(name, meters)])
actions = []     # (tau, kind) 앱이 일한 시각. 제 속도로 보여 줄 구간을 정합니다.
cues = []        # (tau, file)
captions = []    # (tau, 이름표, 글, 보여 줄 초)

lines = (HERE / "log.txt").read_text(errors="ignore").splitlines()
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
        actions.append((tau, "speech"))
        captions.append((tau, "아이에게 하는 말", f"“{msg[3:]}”", 5.0))
    elif msg.startswith("효과음: "):
        k = msg.split(": ")[1]
        cues.append((tau, CUE_FILE[k]))
        actions.append((tau, "cue"))
        captions.append((tau, "효과음", CUE_LABEL[CUE_FILE[k]], 3.0))
    elif msg.startswith("조명 결과: "):
        c = msg.split()[-1]
        light.append((tau, COLORS.get(c, (c, (200, 200, 200)))))
        actions.append((tau, "light"))
    elif msg.startswith("창문 닫기: "):
        window_label = SEAT_NAMES.get(msg.split(": ")[1], "아이 자리") + " 창문"
    elif msg.startswith("창문 결과: "):
        v = int(msg.split("= ")[-1])
        window.append((tau, "닫힘" if v == 0 else f"열림 {v}%"))
        actions.append((tau, "car"))
    elif msg.startswith("내기 결과: ") or msg.startswith("되돌린 결과: "):
        air.append((tau, "내기 순환" if msg.endswith("true") else "외기 유입"))
        actions.append((tau, "car"))
    elif msg.startswith("내기 순환 되돌리기"):
        captions.append((tau, "차 안", "터널을 나와 공기 순환을 되돌려요", 3.5))
    elif msg.startswith("바람 결과"):
        actions.append((tau, "car"))
        captions.append((tau, "차 안", f"바람을 {msg.split(': ')[1]}단으로 줄여요", 3.0))
    elif msg.startswith("이동 기록"):
        n = re.findall(r"\d+", msg)
        actions.append((tau, "record"))
        text = (f"알린 자극 {n[0]}번, 벨트 풀림 {n[1]}번을 기록하고 부모 평가를 받아요" if len(n) >= 2
                else "이동 기록을 저장하고 부모 평가를 받아요")
        captions.append((tau, "기록", text, 6.5))

# ---------- 구간 ----------
# 시험 주행은 동네 길(route/town.json)과 고속도로 터널(route/wolmun.json) 두 구간입니다. 앱 기록의 사건 종류로 구간을 가립니다.
# 예전 녹화처럼 한 구간 기록만 있으면 그 구간만 씁니다.
TOWN = json.loads((Path(__file__).with_name("route") / "town.json").read_text())
LEGS_ALL = [
    {"data": TOWN, "title": "시험 주행 1 · 동네 길", "idle": "남양주 화도읍 맷돌로, 과속방지턱 세 개",
     "card": "남양주 화도읍 맷돌로를 시속 30km로 달려요"},
    {"data": ROUTE, "title": "시험 주행 2 · 고속도로 터널", "idle": "서울양양고속도로 서울 방향, 월문1터널 구간",
     "card": "서울양양고속도로 월문1터널 구간을 시속 90km로 달려요"},
]
for leg in LEGS_ALL:
    leg["kinds"] = {e["kind"] for e in leg["data"]["events"]}
    leg["snaps"] = [(t, evs) for t, evs in snapshots if evs and evs[0][0] in leg["kinds"]]
LEGS = [leg for leg in LEGS_ALL if leg["snaps"]]
if len(LEGS) == 1:
    LEGS[0]["title"] = "시험 주행"

demo_start = LEGS[0]["snaps"][0][0]
demo_end = max(a[0] for a in actions) + 6
# record.sh가 기어를 P로 바꾼 시각을 park_at.txt에 적습니다. 그때부터 앱 화면이 다시 열립니다.
# 그 파일이 없는 예전 녹화는 멈추기 6.5초 전으로 봅니다.
stops = [a[0] for a in actions if a[1] == "record"]
if (HERE / "park_at.txt").exists():
    drive_end = float((HERE / "park_at.txt").read_text()) - rec_start
else:
    drive_end = (stops[0] - 6.5) if stops else demo_end
captions.append((demo_start + 0.5, "안내", "주행 중에는 앱 화면이 잠겨요. 예보는 말, 소리, 조명으로 전해요.", 6.0))
# 기록 자막이 뜰 때까지 이어서 보여 줍니다. 겹치면 나중에 시작한 자막이 이깁니다.
captions.append((drive_end, "안내", "차가 서면 앱 화면이 다시 열려요", 30.0))
captions.sort(key=lambda c: c[0])

# ---------- 차 안 소리 ----------
# record.sh가 받은 소리(cap.raw, 48kHz 스테레오 16비트)입니다. audio_cat.log의 first 줄은 첫 바이트가 온 기기 시각과 그 바이트 수입니다.
cap = None
cap_tau0 = None
if (HERE / "cap.raw").exists() and (HERE / "audio_cat.log").exists():
    m = re.search(r"first (\d+) (\d+)", (HERE / "audio_cat.log").read_text())
    raw = np.fromfile(HERE / "cap.raw", dtype="<i2")
    if m and raw.size > SR * 2:
        cap = raw[: raw.size // 2 * 2].reshape(-1, 2).astype(np.float64).mean(axis=1) / 32768
        cap_tau0 = tau_of_emu(int(m.group(1)) / 1000 - int(m.group(2)) / 4 / SR)

# ---------- 차 위치: 앱이 본 남은 거리에서 구간 출발부터 간 거리를 거꾸로 셉니다 ----------
# 같은 종류 사건이 여럿이면(과속방지턱 세 개), 남은 개수로 어느 사건인지 맞춥니다.
for i, leg in enumerate(LEGS):
    pos = {}
    for e in leg["data"]["events"]:
        pos.setdefault(e["kind"], []).append(e["at"])
    for k in pos:
        pos[k].sort()
    leg["speed"] = leg["data"]["speed_mps"]
    leg["end"] = leg["data"]["route"][-1][2]
    leg["start"] = leg["snaps"][0][0]
    travel = [(leg["start"], 0.0)]
    for tau, evs in leg["snaps"]:
        known = []
        for k, ats in pos.items():
            ms = sorted(m for kk, m in evs if kk == k)
            if ms and len(ms) <= len(ats):
                known.append(ats[-len(ms)] - ms[0])
        if known:
            travel.append((tau, max(known)))
    leg["travel"] = sorted(travel)
    # 구간이 끝나는 녹화 시각입니다. 마지막 구간은 기록이 끝날 때까지 갑니다.
    leg["stop"] = leg["start"] + leg["end"] / leg["speed"] if i < len(LEGS) - 1 else demo_end


def s_of(tau, leg):
    """구간 출발부터 간 거리(m)입니다. 사건이 끝난 뒤에는 같은 속도로 구간 끝까지 갑니다."""
    travel = leg["travel"]
    if tau <= travel[0][0]:
        return 0.0
    for (t0, s0), (t1, s1) in zip(travel, travel[1:]):
        if t0 <= tau <= t1:
            return s0 + (s1 - s0) * (tau - t0) / ((t1 - t0) or 1e-9)
    t_last, s_last = travel[-1]
    return min(leg["end"], s_last + leg["speed"] * (tau - t_last))

# ---------- 시간 줄이기: 구간마다 일이 있는 앞뒤만 제 속도, 구간 사이 쉬는 시간은 잘라 냅니다 ----------
FAST = 6.0
# 말 앞에는 지도의 남은 거리가 줄어드는 모습을 6초 보여 주고, 다른 일 앞에는 2초만 둡니다. 효과음과 조명은 말 뒤에 오기 때문입니다.
BEFORE = {"speech": 6.0}
AFTER = {"record": 5.0}


def plan(lo, hi, start):
    """녹화 시각 lo~hi를 (시작, 끝, 배속, 출력 프레임 수) 조각으로 나눕니다."""
    keep = [(start - 1.5, start + 3)]
    for a in actions:
        if lo <= a[0] <= hi:
            keep.append((a[0] - BEFORE.get(a[1], 2.0), a[0] + AFTER.get(a[1], 3.0)))
    merged = []
    for s, e in sorted(keep):
        s, e = max(s, lo), min(e, hi)
        if s >= e:
            continue
        if merged and s <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], e))
        else:
            merged.append((s, e))
    segs = []
    cursor = lo
    for s, e in merged:
        if s > cursor:
            segs.append((cursor, s, FAST))
        segs.append((max(s, cursor), e, 1.0))
        cursor = max(cursor, e)
    if hi > cursor:
        segs.append((cursor, hi, FAST))
    return [(s, e, sp, int(round((e - s) / sp * FPS))) for s, e, sp in segs]


for leg in LEGS:
    leg["segments"] = plan(leg["start"] - 1.5, leg["stop"], leg["start"])
    leg["times"] = [(s + i * sp / FPS, sp > 1) for s, e, sp, n in leg["segments"] for i in range(n)]

# 구간 앞에 넣는 장면 카드의 프레임 수입니다. 들어가며 바뀜, 멈춤, 나가며 바뀜 순서입니다.
CARD_IN, CARD_HOLD, CARD_OUT = 6, 22, 6


def out_time_of(tau):
    """녹화 시각 tau가 첫 구간 첫 프레임부터 몇 초 뒤에 나오는지입니다."""
    frames = 0
    for i, leg in enumerate(LEGS):
        if i > 0:
            frames += CARD_IN + CARD_HOLD + CARD_OUT
        for s, e, sp, n in leg["segments"]:
            if tau < e:
                return (frames + max(0.0, tau - s) / sp * FPS) / FPS
            frames += n
    return frames / FPS


# ---------- 화면 배치 ----------
emu_frames = sorted((HERE / "emu").glob("e_*.jpg"))

EMU = (48, 100, 48 + 1152, 100 + 648)      # 에뮬레이터 1280×720 녹화를 0.9배로
MAP = (1224, 100, 1872, 520)
STATE = (1224, 536, 1872, 748)
CAP = (48, 776, 1872, 936)
RADIUS = 18

F_HEAD = font(BOLD, 36)
F_HEAD_SUB = font(REGULAR, 28)
F_PILL = font(SEMI, 24)
F_FOOT = font(REGULAR, 20)
F_INFO1 = font(SEMI, 26)
F_INFO2 = font(BOLD, 40)
F_STATE_HEAD = font(SEMI, 24)
F_STATE = font(REGULAR, 27)
F_STATE_V = font(BOLD, 28)
F_SPEECH = font(BOLD, 50)
F_CAP = font(SEMI, 40)
F_IDLE = font(REGULAR, 32)
F_LEAD = font(BOLD, 40)
F_ITEM = font(BOLD, 36)
F_ITEM_BODY = font(REGULAR, 30)
F_BIG = font(BOLD, 112)
F_TAG = font(SEMI, 40)
F_NOTE = font(REGULAR, 28)

TAGLINE = "길 위 자극을 차가 먼저 알고, 아이에게 미리 알리고, 차 안을 미리 바꿉니다"
F_LEG = font(BOLD, 72)
for leg in LEGS:
    leg["map"] = RouteMap(MAP[2] - MAP[0], MAP[3] - MAP[1], (font(REGULAR, 20), font(REGULAR, 22), font(BOLD, 24)),
                          labels=False, credit=False, data=leg["data"])


def rounded_mask(w, h, r=RADIUS):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    return m


EMU_MASK = rounded_mask(EMU[2] - EMU[0], EMU[3] - EMU[1])
MAP_MASK = rounded_mask(MAP[2] - MAP[0], MAP[3] - MAP[1])


def last_before(items, tau):
    best = None
    for it in items:
        if it[0] <= tau:
            best = it
    return best


def caption_at(tau):
    """tau에 보여 줄 자막입니다. 겹치면 나중에 시작한 것이 이깁니다."""
    best = None
    for c in captions:
        if c[0] <= tau < c[0] + c[3]:
            best = c
    return best


def header(d, sub, fast=False):
    d.text((48, 52), "감각 예보", font=F_HEAD, fill=FG, anchor="lm")
    x = 48 + d.textlength("감각 예보", font=F_HEAD) + 18
    d.text((x, 54), sub, font=F_HEAD_SUB, fill=MUTED, anchor="lm")
    if fast:
        label = f"{FAST:g}배속"
        tw = d.textlength(label, font=F_PILL)
        d.rounded_rectangle((W - 48 - tw - 40, 33, W - 48, 73), 20, fill=mix(BG, AMBER, 0.22))
        d.text((W - 48 - 20 - tw / 2, 53), label, font=F_PILL, fill=AMBER, anchor="mm")


def footnote(d, left, right=None):
    d.text((48, 1014), left, font=F_FOOT, fill=MUTED, anchor="lm")
    if right:
        d.text((W - 48, 1014), right, font=F_FOOT, fill=MUTED, anchor="rm")


def draw_state(d, tau):
    x0, y0, x1, y1 = STATE
    d.rounded_rectangle(STATE, RADIUS, fill=CARD, outline=LINE, width=2)
    d.text((x0 + 24, y0 + 20), "차 안", font=F_STATE_HEAD, fill=MUTED)
    rows = [("실내 조명", light, True), (window_label, window, False), ("공기 순환", air, False)]
    for i, (label, series, is_light) in enumerate(rows):
        ry = y0 + 62 + i * 48
        changed, value = last_before(series, tau)
        age = tau - changed
        # 바뀐 줄은 1.5초 밝혔다가 1.5초에 걸쳐 원래대로 돌립니다.
        a = 1.0 if age < 1.5 else max(0.0, 1 - (age - 1.5) / 1.5)
        if a > 0:
            d.rounded_rectangle((x0 + 12, ry - 4, x1 - 12, ry + 40), 10, fill=mix(CARD, ACCENT, 0.2 * a))
        d.text((x0 + 28, ry + 18), label, font=F_STATE, fill=MUTED, anchor="lm")
        col = mix(FG, ACCENT, a)
        if is_light:
            name, rgb = value
            tw = d.textlength(name, font=F_STATE_V)
            d.text((x1 - 28, ry + 18), name, font=F_STATE_V, fill=col, anchor="rm")
            cx = x1 - 28 - tw - 22
            d.ellipse((cx - 11, ry + 7, cx + 11, ry + 29), fill=rgb)
        else:
            d.text((x1 - 28, ry + 18), value, font=F_STATE_V, fill=col, anchor="rm")


def draw_caption(d, tau, idle):
    x0, y0, x1, y1 = CAP
    d.rounded_rectangle(CAP, RADIUS, fill=CARD, outline=LINE, width=2)
    cy = (y0 + y1) / 2
    c = caption_at(tau)
    if c is None:
        d.text((x0 + 40, cy), idle, font=F_IDLE, fill=MUTED, anchor="lm")
        return
    start, label, text, dur = c
    a = max(0.0, min(1.0, (tau - start) / 0.3, (start + dur - tau) / 0.3))
    tone = {"아이에게 하는 말": ACCENT, "효과음": AMBER}.get(label, MUTED)
    pw = d.textlength(label, font=F_PILL) + 44
    d.rounded_rectangle((x0 + 40, cy - 24, x0 + 40 + pw, cy + 24), 24, fill=mix(CARD, mix(CARD, tone, 0.22), a))
    d.text((x0 + 40 + pw / 2, cy), label, font=F_PILL, fill=mix(CARD, tone, a), anchor="mm")
    f = F_SPEECH if label == "아이에게 하는 말" else F_CAP
    d.text((x0 + 40 + pw + 28, cy), text, font=f, fill=mix(CARD, FG, a), anchor="lm")


def draw_map_info(d, rmap, s):
    l1, l2 = rmap.status(s)
    x0, y0 = MAP[0] + 16, MAP[1] + 16
    w = max(d.textlength(l1, font=F_INFO1), d.textlength(l2, font=F_INFO2)) + 44
    d.rounded_rectangle((x0, y0, x0 + w, y0 + 100), 14, fill=(10, 15, 18), outline=LINE, width=2)
    d.text((x0 + 22, y0 + 14), l1, font=F_INFO1, fill=FG)
    d.text((x0 + 22, y0 + 46), l2, font=F_INFO2, fill=ACCENT)


def draw_demo(tau, fast, leg):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    header(d, leg["title"], fast)

    idx = min(len(emu_frames) - 1, max(0, int(round(tau * FPS))))
    emu = Image.open(emu_frames[idx]).convert("RGB").resize((EMU[2] - EMU[0], EMU[3] - EMU[1]), Image.BICUBIC)
    im.paste(emu, EMU[:2], EMU_MASK)
    d.rounded_rectangle(EMU, RADIUS, outline=LINE, width=2)

    s = s_of(tau, leg)
    im.paste(leg["map"].frame(s, info=False), MAP[:2], MAP_MASK)
    d.rounded_rectangle(MAP, RADIUS, outline=LINE, width=2)
    draw_map_info(d, leg["map"], s)

    draw_state(d, tau)
    draw_caption(d, tau, leg["idle"])
    footnote(d, "Pleos Connect 에뮬레이터 v2.1.0과 가상 콘솔로 녹화한 시험 주행입니다. 길 정보는 실제 거리로 만든 시험 입력입니다.",
             "지도 자료 © OpenStreetMap contributors")
    return im


def leg_card(leg):
    """구간 앞에 잠깐 띄우는 장면 카드입니다."""
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((W / 2, 470), leg["title"], font=F_LEG, fill=FG, anchor="mm")
    d.text((W / 2, 570), leg["card"], font=F_TAG, fill=ACCENT, anchor="mm")
    return im


def title_card(notes):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((W / 2, 410), "감각 예보", font=F_BIG, fill=FG, anchor="mm")
    d.text((W / 2, 532), TAGLINE, font=F_TAG, fill=ACCENT, anchor="mm")
    for i, ln in enumerate(notes):
        d.text((W / 2, 650 + i * 48), ln, font=F_NOTE, fill=MUTED, anchor="mm")
    return im


# 아이 카드 화면 캡처(card.png, 2560×1440)에서 아이 카드 칸만 잘라 크게 보여 줍니다.
CARD_CROP = (854, 90, 1786, 1300)
CARD_ITEMS = [  # (캡처 속 칸 제목 가운데의 세로 위치, 제목, 설명 줄)
    (276, "아이 자리", ["아이가 앉는 자리예요. 창문과 조명은 이 자리를 바꿔요."]),
    (452, "미리 알릴 자극", ["덜컹·쏠림, 터널·지하차도, 시끄러운 길, 멈춤, 경로 바뀜", "가운데 아이가 힘들어하는 것만 켜요."]),
    (993, "알리는 방식", ["짧은 말, 효과음, 아이 자리 조명 색을", "따로 켜고 꺼요."]),
]


def child_card_scene():
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    header(d, "아이 카드")
    cw, ch = CARD_CROP[2] - CARD_CROP[0], CARD_CROP[3] - CARD_CROP[1]
    sh = 880
    sw = round(cw * sh / ch)
    shot = Image.open(HERE / "card.png").convert("RGB").crop(CARD_CROP).resize((sw, sh), Image.LANCZOS)
    x0, y0 = 96, 108
    im.paste(shot, (x0, y0), rounded_mask(sw, sh))
    d.rounded_rectangle((x0, y0, x0 + sw, y0 + sh), RADIUS, outline=LINE, width=2)
    rx = 900
    d.text((rx, 140), "차가 서 있을 때 부모가 한 번 정해 둬요", font=F_LEAD, fill=FG)
    for oy, title, body in CARD_ITEMS:
        y = y0 + (oy - CARD_CROP[1]) * sh / ch
        # 연결선은 캡처 속 칸 제목 높이에 맞추고, 오른쪽 제목도 그 높이를 가운데로 둡니다.
        d.line((x0 + sw + 12, y, rx - 28, y), fill=mix(BG, ACCENT, 0.6), width=2)
        d.ellipse((x0 + sw + 6, y - 6, x0 + sw + 18, y + 6), fill=ACCENT)
        d.text((rx, y), title, font=F_ITEM, fill=ACCENT, anchor="lm")
        for i, b in enumerate(body):
            d.text((rx, y + 34 + i * 42), b, font=F_ITEM_BODY, fill=FG)
    footnote(d, "Pleos Connect 에뮬레이터 v2.1.0에서 연 앱 화면입니다.")
    return im


# 끝 장면: 평가 전(rate.png)과 평가 뒤(end.png) 캡처에서 앱 오른쪽 칸을 잘라 크게 보여 줍니다.
# 화면 구조(rate_ui.xml, end_ui.xml)에서 짚을 글자의 자리를 찾아 테두리를 그립니다.
RECORD_CROP = (1786, 90, 2534, 1000)


def bounds_of(xml_path, pattern):
    """화면 구조에서 글자가 pattern에 맞는 첫 칸의 [x0, y0, x1, y1]입니다. 없으면 None입니다."""
    if not xml_path.exists():
        return None
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml_path.read_text(errors="ignore")):
        if re.search(pattern, m.group(1)):
            return tuple(int(g) for g in m.groups()[1:])
    return None


def record_scene(shot_path, mark, lead, lines):
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    header(d, "이동 기록")
    cw, ch = RECORD_CROP[2] - RECORD_CROP[0], RECORD_CROP[3] - RECORD_CROP[1]
    sh = 860
    sw = round(cw * sh / ch)
    k = sh / ch
    shot = Image.open(shot_path).convert("RGB").crop(RECORD_CROP).resize((sw, sh), Image.LANCZOS)
    x0, y0 = 96, 112
    im.paste(shot, (x0, y0), rounded_mask(sw, sh))
    d.rounded_rectangle((x0, y0, x0 + sw, y0 + sh), RADIUS, outline=LINE, width=2)
    if mark:
        a, b, c2, e = mark
        box = (x0 + (a - RECORD_CROP[0]) * k - 14, y0 + (b - RECORD_CROP[1]) * k - 10,
               x0 + (c2 - RECORD_CROP[0]) * k + 14, y0 + (e - RECORD_CROP[1]) * k + 10)
        d.rounded_rectangle(box, 12, outline=ACCENT, width=4)
    # 설명 글은 짚은 칸 높이에 맞추고, 짚은 칸이 없으면 캡처 가운데에 둡니다.
    cy = (box[1] + box[3]) / 2 if mark else y0 + sh / 2
    top = max(150, min(760, cy - 70))
    rx = x0 + sw + 110
    d.text((rx, top), lead, font=F_LEAD, fill=FG)
    for i, ln in enumerate(lines):
        d.text((rx, top + 80 + i * 52), ln, font=F_ITEM_BODY, fill=MUTED)
    footnote(d, "Pleos Connect 에뮬레이터 v2.1.0에서 연 앱 화면입니다.")
    return im


def record_scenes():
    """평가 전과 뒤 장면입니다. 예전 녹화처럼 캡처가 없으면 빈 목록입니다."""
    if not ((HERE / "rate.png").exists() and (HERE / "end.png").exists()):
        return []
    # 평가 줄은 질문과 1~5점 단추를 함께 묶어 짚습니다.
    q = bounds_of(HERE / "rate_ui.xml", "어땠나요")
    btn = bounds_of(HERE / "rate_ui.xml", r"^5점$")
    ask = (q[0], q[1], btn[2], btn[3]) if q and btn else q
    done = bounds_of(HERE / "end_ui.xml", r"평가 \d점")
    return [
        record_scene(HERE / "rate.png", ask, "차가 서면 오늘 이동이 어땠는지 물어요",
                     ["부모가 1점에서 5점 가운데 하나를 골라요.", "알린 자극 수와 벨트 풀림은 앱이 세요."]),
        record_scene(HERE / "end.png", done, "고른 점수는 지난 이동에 쌓여요",
                     ["이동마다 알린 자극, 벨트 풀림, 평가가 남아요.", "기록은 차 밖으로 보내지 않아요."]),
    ]


def main():
    out = HERE / "out"
    out.mkdir(exist_ok=True)
    enc = subprocess.Popen(
        [FF, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
         "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", str(out / "video.mp4")],
        stdin=subprocess.PIPE)
    total = 0

    def write(img):
        nonlocal total
        enc.stdin.write(img.tobytes())
        total += 1

    def hold(img, seconds):
        for _ in range(int(seconds * FPS)):
            write(img)

    def cross(a, b, seconds=None, n=None):
        n = n if n is not None else int(seconds * FPS)
        for k in range(1, n + 1):
            write(Image.blend(a, b, k / (n + 1)))

    places = "남양주 화도읍 맷돌로와 서울양양고속도로 월문1터널 구간" if len(LEGS) == 2 else "서울양양고속도로 월문1터널 구간"
    intro = title_card(["Pleos Connect 에뮬레이터 v2.1.0과 가상 콘솔에서 녹화한 시험 주행입니다.",
                        f"길 정보는 {places}의 실제 거리로 만든 시험 입력입니다.",
                        "앱이 하는 말은 에뮬레이터에서 받은 소리이고, 효과음은 앱의 소리 파일입니다. 말은 자막으로도 보여 줍니다."
                        if cap is not None else "앱이 하는 말은 자막으로, 효과음은 앱이 낸 소리 그대로 넣었습니다."])
    child = child_card_scene()
    hold(intro, 4)
    cross(intro, child, 0.5)
    hold(child, 6)
    last = child
    demo_offset = None
    for leg in LEGS:
        # 구간마다 장면 카드를 잠깐 띄운 뒤 그 구간 녹화를 보여 줍니다. out_time_of가 이 프레임 수를 셉니다.
        card = leg_card(leg)
        first = draw_demo(*leg["times"][0], leg)
        cross(last, card, n=CARD_IN)
        for _ in range(CARD_HOLD):
            write(card)
        cross(card, first, n=CARD_OUT)
        if demo_offset is None:
            demo_offset = total / FPS
        for tau, fast in leg["times"]:
            last = draw_demo(tau, fast, leg)
            write(last)
    for scene in record_scenes():
        cross(last, scene, 0.5)
        hold(scene, 3.5)
        last = scene
    outro = title_card(["길 위 자극은 내비 안내 거리와 속도로 미리 알고, 창문 · 공기 순환 · 조명은 Pleos Vehicle SDK로 바꿉니다.",
                        "지도 자료 © OpenStreetMap contributors"])
    cross(last, outro, 0.6)
    hold(outro, 4.5)
    enc.stdin.close()
    enc.wait()

    # 소리: 제 속도 구간에는 받은 차 안 소리를 그대로 옮기고, 효과음은 기록 시각에 소리 파일을 넣습니다.
    audio = np.zeros(int(total / FPS * SR) + SR)
    if cap is not None:
        for s, e, sp, n in [seg for leg in LEGS for seg in leg["segments"]]:
            if sp > 1:
                continue
            i0, i1 = int((s - cap_tau0) * SR), int((e - cap_tau0) * SR)
            a, b = max(i0, 0), min(i1, len(cap))
            if b > a:
                o = int((demo_offset + out_time_of(s)) * SR) + (a - i0)
                audio[o:o + (b - a)] += cap[a:b][: len(audio) - o]
        # 확인용: 말 기록 뒤 소리가 처음 커지는 데 걸린 시간입니다. 음성 합성에 걸리는 시간이라 1초 안팎이어야 맞습니다.
        for c in captions:
            if c[1] == "아이에게 하는 말":
                k = int((c[0] - cap_tau0) * SR)
                loud = np.nonzero(np.abs(cap[max(k, 0):k + 4 * SR]) > 0.02)[0]
                print("말 소리 시작", f"{loud[0] / SR:.2f}초 뒤" if loud.size else "못 찾음", c[2])
    for tau, name in cues:
        with wave.open(str(RAW / f"cue_{name}.wav")) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32767
            wsr = w.getframerate()
        x = np.interp(np.arange(0, len(x) / wsr, 1 / SR), np.arange(len(x)) / wsr, x)
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
    for leg in LEGS:
        print(leg["title"])
        for s, e, sp, n in leg["segments"]:
            print(f"  {s:6.1f}~{e:6.1f}초 {f'{sp:g}배' if sp > 1 else '제 속도'}")


if __name__ == "__main__":
    main()
