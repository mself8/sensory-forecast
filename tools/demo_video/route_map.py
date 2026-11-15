"""시연 영상의 경로 지도 칸을 그립니다. 기본은 route/wolmun.json이고, 다른 경로 자료(route/town.json 등)도 받습니다.

- 바탕(산, 물, 도로, 마을 이름)은 한 번만 그려 두고, 프레임마다 차 위치와 남은 거리만 얹습니다.
- 지도 자료: © OpenStreetMap contributors, ODbL 1.0. 칸 안에 출처를 씁니다.
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

DATA = json.loads((Path(__file__).with_name("route") / "wolmun.json").read_text())

BG = (17, 25, 29)
WOOD = (27, 47, 37)
WATER = (22, 45, 62)
ROAD = {"motorway": (104, 120, 126), "trunk": (88, 104, 110), "primary": (80, 96, 102),
        "secondary": (66, 80, 86), "tertiary": (56, 70, 76), "residential": (40, 52, 57), "unclassified": (40, 52, 57)}
WIDTH = {"motorway": 5, "trunk": 4, "primary": 3, "secondary": 3, "tertiary": 2, "residential": 1, "unclassified": 1}
FG = (228, 236, 238)
MUTED = (140, 160, 166)
ACCENT = (82, 196, 205)
TUNNEL = (255, 196, 92)
DONE = (120, 136, 140)

NAMES = {"TUNNEL": "터널", "TOLLGATE": "요금소", "TUNNEL_EXIT": "터널 출구"}


class RouteMap:
    def __init__(self, w, h, fonts, labels=True, credit=True, data=None):
        """labels가 거짓이면 마을·산 이름을, credit이 거짓이면 칸 안 출처 글자를 그리지 않습니다. 출처는 그때 화면 다른 곳에 씁니다."""
        self.w, self.h = w, h
        self.f_small, self.f_body, self.f_bold = fonts
        self.labels, self.credit = labels, credit
        self.data = data or DATA
        la0, lo0, la1, lo1 = self.data["extent"]
        kx = math.cos(math.radians((la0 + la1) / 2))
        span_x, span_y = (lo1 - lo0) * kx, (la1 - la0)
        self.scale = min(w / span_x, h / span_y)
        self.ox = (w - span_x * self.scale) / 2
        self.oy = (h - span_y * self.scale) / 2
        self.la1, self.lo0, self.kx = la1, lo0, kx
        # 많이 확대한 지도(동네 길)에서는 도로가 너무 가늘어 보이지 않게 굵기를 키웁니다.
        meters_per_px = 110540 / self.scale
        self.thick = max(1.0, min(3.0, 4.0 / meters_per_px))
        self.route = self.data["route"]
        self.events = self.data["events"]
        self.base = self._base()

    def px(self, lat, lon):
        return (self.ox + (lon - self.lo0) * self.kx * self.scale, self.oy + (self.la1 - lat) * self.scale)

    def at(self, s):
        """출발부터 s미터 지점의 화면 좌표와 진행 방향(라디안)입니다."""
        r = self.route
        s = max(0.0, min(r[-1][2], s))
        for a, b in zip(r, r[1:]):
            if a[2] <= s <= b[2]:
                t = (s - a[2]) / ((b[2] - a[2]) or 1e-9)
                p, q = self.px(a[0], a[1]), self.px(b[0], b[1])
                return (p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])), math.atan2(q[1] - p[1], q[0] - p[0])
        return self.px(r[-1][0], r[-1][1]), 0.0

    def piece(self, s0, s1):
        pts = [self.at(s0)[0]] + [self.px(p[0], p[1]) for p in self.route if s0 < p[2] < s1] + [self.at(s1)[0]]
        return pts

    def _base(self):
        im = Image.new("RGB", (self.w, self.h), BG)
        d = ImageDraw.Draw(im)
        for a in self.data["areas"]:
            d.polygon([self.px(*p) for p in a["p"]], fill=WOOD if a["c"] == "wood" else WATER)
        for ln in self.data["lines"]:
            if ln["c"] == "stream":
                d.line([self.px(*p) for p in ln["p"]], fill=WATER, width=round(2 * self.thick), joint="curve")
        order = ["residential", "living_street", "unclassified", "tertiary", "secondary", "primary", "trunk", "motorway"]
        for c in order:
            for ln in self.data["lines"]:
                if ln["c"] != c:
                    continue
                pts = [self.px(*p) for p in ln["p"]]
                col = ROAD.get(c, ROAD["residential"])
                if ln["tunnel"]:
                    col = tuple(int(v * 0.55) for v in col)
                d.line(pts, fill=col, width=round(WIDTH.get(c, 1) * self.thick), joint="curve")
        # 경로 이름표 자리를 먼저 정해, 겹치는 마을·산 이름은 그리지 않습니다.
        taken = [(0, 0, 350, 120),
                 (self.w - 380, self.h - 44, self.w, self.h)]   # 출처 글자 자리
        # 터널은 경로에 있을 때만 점선과 이름표를 그립니다.
        tun = next((e for e in self.events if e["kind"] == "TUNNEL"), None)
        if tun:
            t0, t1 = tun["at"], tun["at"] + tun["length"]
            (mx, my), ang = self.at((t0 + t1) / 2)
            nx, ny = -math.sin(ang), math.cos(ang)
            if nx + ny < 0:
                nx, ny = -nx, -ny
            tun_tag = (mx + nx * 90, my + ny * 90)
            taken.append((tun_tag[0] - 150, tun_tag[1] - 34, tun_tag[0] + 150, tun_tag[1] + 34))
        # 과속방지턱은 자리마다 점을 찍고, 가운데쯤 경로 아래에 개수를 씁니다.
        bumps = [e for e in self.events if e["kind"] == "SPEED_BUMP"]
        if bumps:
            (bx, by), bang = self.at((bumps[0]["at"] + bumps[-1]["at"]) / 2)
            nx, ny = -math.sin(bang), math.cos(bang)
            if ny < 0:
                nx, ny = -nx, -ny
            bump_tag = (bx + nx * 70, by + ny * 70)
            taken.append((bump_tag[0] - 130, bump_tag[1] - 34, bump_tag[0] + 130, bump_tag[1] + 34))
        # 요금소는 경로에 있을 때만 이름표를 답니다.
        toll = next((e for e in self.events if e["kind"] == "TOLLGATE"), None)
        if toll:
            tp = self.at(toll["at"])[0]
            toll_tag = (tp[0] + 20, tp[1] + 52)
            taken.append((toll_tag[0] - 110, toll_tag[1] - 34, toll_tag[0] + 110, toll_tag[1] + 34))
        for lb in self.data["labels"] if self.labels else []:
            if lb["c"] not in ("village", "town", "peak"):
                continue
            x, y = self.px(*lb["p"])
            if any(a <= x <= c and b <= y <= e for a, b, c, e in taken) or not (20 < x < self.w - 20 and 20 < y < self.h - 20):
                continue
            if lb["c"] == "peak":
                d.polygon([(x, y - 7), (x - 6, y + 4), (x + 6, y + 4)], fill=(70, 96, 80))
                d.text((x + 9, y - 2), lb["name"], font=self.f_small, fill=(110, 140, 120), anchor="lm")
            else:
                d.text((x, y), lb["name"], font=self.f_body, fill=MUTED, anchor="mm")
        # 경로: 터널 구간은 점선으로, 나머지는 실선으로
        end = self.route[-1][2]
        if tun:
            d.line(self.piece(0, t0), fill=ACCENT, width=7, joint="curve")
            d.line(self.piece(t1, end), fill=ACCENT, width=7, joint="curve")
            self._dashed(d, self.piece(t0, t1), TUNNEL, 7)
            d.line((mx, my, tun_tag[0], tun_tag[1]), fill=TUNNEL, width=2)
            self._tag(d, tun_tag, f"{tun['name']}  {tun['length'] / 1000:.1f}km", TUNNEL)
        else:
            d.line(self.piece(0, end), fill=ACCENT, width=7, joint="curve")
        if bumps:
            mid = self.at((bumps[0]["at"] + bumps[-1]["at"]) / 2)[0]
            d.line((mid[0], mid[1], bump_tag[0], bump_tag[1]), fill=TUNNEL, width=2)
            for b in bumps:
                x, y = self.at(b["at"])[0]
                d.ellipse((x - 9, y - 9, x + 9, y + 9), fill=TUNNEL, outline=(10, 16, 18), width=3)
            self._tag(d, bump_tag, f"과속방지턱 {len(bumps)}개", TUNNEL)
        if toll:
            d.rounded_rectangle((tp[0] - 9, tp[1] - 9, tp[0] + 9, tp[1] + 9), 4, fill=FG)
            d.line((tp[0], tp[1], toll_tag[0], toll_tag[1]), fill=FG, width=2)
            self._tag(d, toll_tag, toll["name"], FG)
        # 방향과 길 이름. 경로 자료에 있을 때만 씁니다.
        (ex, ey), eang = self.at(end)
        s_beg, sang = self.at(0)
        if self.data.get("end_label"):
            # 경로 끝에서 진행 방향으로 더 나간 곳에 둬서, 끝에 선 차 표시와 겹치지 않게 합니다.
            d.text((ex + 70 * math.cos(eang), ey + 70 * math.sin(eang)), self.data["end_label"], font=self.f_bold, fill=FG, anchor="rm")
        if self.data.get("start_label"):
            d.text((s_beg[0] + 14, s_beg[1] - 4), self.data["start_label"], font=self.f_small, fill=MUTED, anchor="lm")
        if self.data.get("road_label"):
            # 출발점 위쪽에 길 이름을 씁니다.
            d.text((s_beg[0] + 10, s_beg[1] - 26), self.data["road_label"], font=self.f_bold, fill=FG, anchor="ls")
        if self.credit:
            d.text((self.w - 12, self.h - 10), "지도 자료 © OpenStreetMap contributors", font=self.f_small, fill=MUTED, anchor="rd")
        return im

    def _dashed(self, d, pts, col, width, dash=14, gap=10):
        carry, on = 0.0, True
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            seg = math.hypot(x2 - x1, y2 - y1)
            pos = 0.0
            while pos < seg:
                step = min((dash if on else gap) - carry, seg - pos)
                if on:
                    a, b = pos / seg, (pos + step) / seg
                    d.line((x1 + a * (x2 - x1), y1 + a * (y2 - y1), x1 + b * (x2 - x1), y1 + b * (y2 - y1)), fill=col, width=width)
                pos += step
                carry += step
                if carry >= (dash if on else gap) - 1e-6:
                    carry, on = 0.0, not on

    def _tag(self, d, xy, text, col):
        tw = d.textlength(text, font=self.f_bold)
        x, y = xy
        d.rounded_rectangle((x - tw / 2 - 12, y - 20, x + tw / 2 + 12, y + 20), 10, fill=(10, 16, 18), outline=col, width=2)
        d.text((x, y), text, font=self.f_bold, fill=col, anchor="mm")

    def status(self, s):
        """다음 사건과 남은 거리를 두 줄 글로 돌려줍니다."""
        ahead = [e for e in self.events if e["kind"] != "TUNNEL_EXIT" and e["at"] > s]
        inside = [e for e in self.events if e["kind"] == "TUNNEL" and e["at"] <= s < e["at"] + e["length"]]
        if inside:
            e = inside[0]
            return "터널 안", f"출구까지 {round(e['at'] + e['length'] - s):,}m"
        if ahead:
            e = ahead[0]
            return f"다음  {e['name']}", f"{round(e['at'] - s):,}m"
        # 경로 끝에 닿아 차가 선 뒤에만 멈춤이라고 씁니다.
        return "앞에 알릴 자극 없음", "멈춤" if s >= self.route[-1][2] - 1 else ""

    def frame(self, s, info=True):
        """출발부터 s미터 간 순간의 지도입니다. info가 거짓이면 다음 사건 상자를 그리지 않습니다."""
        im = self.base.copy()
        d = ImageDraw.Draw(im)
        if s > 0:
            d.line(self.piece(0, s), fill=DONE, width=7, joint="curve")
        (x, y), ang = self.at(s)
        # 차 표시: 흰 원 안에 진행 방향 삼각형
        def pt(rad, turn):
            return (x + rad * math.cos(ang + turn), y + rad * math.sin(ang + turn))

        d.ellipse((x - 21, y - 21, x + 21, y + 21), fill=(255, 255, 255), outline=(20, 110, 230), width=3)
        d.polygon([pt(15, 0), pt(13, 2.55), pt(5, math.pi), pt(13, -2.55)], fill=(20, 110, 230))
        if not info:
            return im
        # 다음 사건과 남은 거리
        line1, line2 = self.status(s)
        d.rounded_rectangle((14, 14, 330, 104), 12, fill=(10, 16, 18), outline=(44, 60, 66), width=2)
        d.text((30, 26), line1, font=self.f_bold, fill=FG)
        d.text((30, 62), line2, font=self.f_bold, fill=ACCENT)
        return im
