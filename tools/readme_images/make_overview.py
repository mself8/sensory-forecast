"""README 구조도: 역할별 가로 줄(부모 · 아이 · 앱 · 차)에 ①~⑦ 흐름을 놓는다.
그림 안에는 단계 이름과 짧은 꼬리말만 넣고, 설명은 README 캡션이 한다.
  python3 tools/readme_images/make_overview.py → docs/img/overview.svg, docs/img/overview.png (rsvg-convert, 2배)"""
import subprocess
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "docs" / "img"
W, H = 1800, 700
NAVY = "#1F2A44"
C = {  # 선 · 채움 · 글자
    "parent": ("#D9822B", "#FDF1E4", "#7A4312"),
    "child": ("#7B5BD6", "#F1ECFD", "#3E2A80"),
    "app": ("#2F6FD1", "#E9F1FD", "#163C7A"),
    "car": ("#1E8A5A", "#E7F5EE", "#0F4D32"),
}
FONT = "Noto Sans CJK KR, Noto Sans KR, Apple SD Gothic Neo, Malgun Gothic, sans-serif"
LANES = [("parent", "부모", None), ("child", "아이", "뒷좌석"), ("app", "앱", None), ("car", "차", "Pleos SDK")]
LANE_Y, LANE_H, LEFT = 30, 158, 150

out = []
add = out.append


def lane_mid(i):
    return LANE_Y + LANE_H * i + LANE_H / 2


add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">')
add('<defs>'
    f'<marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{NAVY}"/></marker>'
    f'<marker id="ap" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{C["parent"][0]}"/></marker>'
    '</defs>')
add(f'<rect width="{W}" height="{H}" fill="#FFFFFF"/>')
for i, (k, name, sub) in enumerate(LANES):
    y = LANE_Y + LANE_H * i
    add(f'<rect x="0" y="{y}" width="{W}" height="{LANE_H}" fill="{C[k][1]}" opacity="0.35"/>')
    add(f'<rect x="0" y="{y}" width="8" height="{LANE_H}" fill="{C[k][0]}"/>')
    add(f'<text x="26" y="{y + LANE_H / 2 + (0 if sub else 8)}" font-size="22" font-weight="700" fill="{C[k][2]}">{name}</text>')
    if sub:
        add(f'<text x="26" y="{y + LANE_H / 2 + 26}" font-size="17" fill="{C[k][2]}" opacity="0.85">{sub}</text>')
    if i:
        add(f'<line x1="0" y1="{y}" x2="{W}" y2="{y}" stroke="#D5DAE3" stroke-width="1.5"/>')
add(f'<line x1="{LEFT}" y1="{LANE_Y}" x2="{LEFT}" y2="{LANE_Y + LANE_H * 4}" stroke="#D5DAE3" stroke-width="1.5"/>')


def box(x, lane, w, title, sub=None, h=96):
    k = LANES[lane][0]
    cy = lane_mid(lane)
    y = cy - h / 2
    add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{C[k][1]}" stroke="{C[k][0]}" stroke-width="2.5"/>')
    add(f'<text x="{x + w / 2}" y="{cy + (8 if not sub else -4)}" text-anchor="middle" font-size="23" font-weight="700" fill="{C[k][2]}">{title}</text>')
    if sub:
        add(f'<text x="{x + w / 2}" y="{cy + 24}" text-anchor="middle" font-size="16" fill="{C[k][2]}" opacity="0.85">{sub}</text>')
    return {"x": x, "w": w, "cx": x + w / 2, "t": y, "b": y + h, "cy": cy}


def path(d, color=NAVY, marker="a", dash=False):
    extra = ' stroke-dasharray="9 7"' if dash else ''
    add(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="2.4"{extra} marker-end="url(#{marker})"/>')


card = box(190, 0, 220, "① 아이 카드", "자극 · 알림 방식")
event = box(190, 3, 250, "② 길 위 사건", "주의 지점 · 회전 안내")
judge = box(520, 2, 250, "③ 거르기 · 시점", "아이 카드 기준")
cue = box(850, 1, 250, "④ 미리 알림", "말 · 효과음 · 조명 색")
cabin = box(850, 3, 270, "⑤ 차 안 바꾸기", "창문 · 내기 순환 · 바람")
belt = box(1190, 3, 200, "벨트 · 좌석", "센서 상태")
log = box(1190, 2, 260, "⑥ 기록", "예고 여부 · 반응 · 벨트")
rate = box(1530, 0, 230, "⑦ 도착 후 평가", "1~5점")

# ① → ③ : 부모 줄에서 앱 줄로 내려간다
path(f"M{card['cx']} {card['b']} V{judge['cy']} H{judge['x'] - 4}")
# ② → ③ : 차 줄에서 오른쪽으로 간 뒤 ③ 아래로 올라간다
path(f"M{event['x'] + event['w']} {event['cy']} H{judge['cx']} V{judge['b'] + 4}")
# ③ → ④, ③ → ⑤
path(f"M{judge['x'] + judge['w']} {judge['cy'] - 18} H{cue['x'] - 70} V{cue['cy']} H{cue['x'] - 4}")
path(f"M{judge['x'] + judge['w']} {judge['cy'] + 18} H{cabin['x'] - 70} V{cabin['cy']} H{cabin['x'] - 4}")
# ④ → ⑥, 벨트 → ⑥
path(f"M{cue['x'] + cue['w']} {cue['cy']} H{log['x'] + 60} V{log['t'] - 4}")
path(f"M{belt['cx']} {belt['t']} V{log['b'] + 4}")
# ⑥ → ⑦
path(f"M{log['x'] + log['w']} {log['cy']} H{rate['cx']} V{rate['b'] + 4}")
# ⑦ → ① : 설정 고치기 (점선)
path(f"M{rate['cx']} {rate['t']} V{LANE_Y + 14} H{card['cx']} V{card['t'] - 4}", color=C["parent"][0], marker="ap", dash=True)

add("</svg>")
OUT.mkdir(parents=True, exist_ok=True)
svg = OUT / "overview.svg"
svg.write_text("\n".join(out), encoding="utf-8")
subprocess.run(["rsvg-convert", "-z", "2", "-o", str(OUT / "overview.png"), str(svg)], check=True)
print("docs/img/overview.svg · docs/img/overview.png")
