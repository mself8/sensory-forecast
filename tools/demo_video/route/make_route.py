"""시험 주행 경로를 OpenStreetMap 자료에서 만듭니다. 서울양양고속도로 서울 방향, 월문1터널 구간입니다.

    python3 tools/demo_video/route/make_route.py fetch <받을 폴더>
    python3 tools/demo_video/route/make_route.py build <받은 폴더>

- fetch는 Overpass에서 도로와 산·마을 자료를 받아 둡니다. 원본은 레포에 넣지 않습니다.
- build는 본선을 이어 붙여 터널 입구와 출구까지 거리를 재고, 지도 그림에 쓸 도형만 줄여서 wolmun.json에 씁니다.
- 출력의 거리는 앱 시험 주행(DemoRoadEventSource)과 영상 지도가 같이 씁니다.
- 자료: © OpenStreetMap contributors, ODbL 1.0. 앱과 영상에 출처를 표시합니다.
"""
import json
import math
import subprocess
import sys
import time
from pathlib import Path

OUT = Path(__file__).with_name("wolmun.json")
HIGHWAY_REF = "60"            # 서울양양고속도로
TUNNEL_NAME = "월문1터널"
LEAD_IN = 400                 # 터널 입구 앞에서 출발하는 거리(m)
AFTER_EXIT = 500              # 터널 출구를 지나 멈추는 거리(m)
SPEED = 25.0                  # 시험 주행 속도(m/s). 시속 90km입니다.

ROADS_BBOX = "37.575,127.19,37.650,127.34"
CTX_BBOX = "37.585,127.225,37.630,127.295"
QUERIES = {
    "roads.json": f"""[out:json][timeout:60];
(
 way["highway"~"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|tertiary)$"]({ROADS_BBOX});
);
out tags geom;""",
    "context.json": f"""[out:json][timeout:90];
(
 way["natural"~"^(wood|water)$"]({CTX_BBOX});
 relation["natural"~"^(wood|water)$"]({CTX_BBOX});
 way["highway"~"^(residential|unclassified)$"]({CTX_BBOX});
 node["place"~"^(village|town|neighbourhood)$"]({CTX_BBOX});
 node["natural"="peak"]({CTX_BBOX});
);
out tags geom;""",
}


def fetch(folder: Path):
    folder.mkdir(parents=True, exist_ok=True)
    for name, q in QUERIES.items():
        for attempt in range(5):
            r = subprocess.run(["curl", "-sS", "--max-time", "120", "-A", "sensory-forecast-dev/0.1",
                                "--data-urlencode", f"data={q}", "https://overpass-api.de/api/interpreter"],
                               capture_output=True, text=True)
            try:
                json.loads(r.stdout)
                (folder / name).write_text(r.stdout)
                print("받음", name)
                break
            except json.JSONDecodeError:
                print("다시 시도", name, attempt)
                time.sleep(20)


# ---------- 거리 ----------
LAT0, LON0 = 37.605, 127.26
KX = 111320 * math.cos(math.radians(LAT0))
KY = 110540


def xy(lat, lon):
    return ((lon - LON0) * KX, (lat - LAT0) * KY)


def dist(a, b):
    (x1, y1), (x2, y2) = xy(*a), xy(*b)
    return math.hypot(x2 - x1, y2 - y1)


def simplify(pts, tol):
    """더글라스-포이커로 점을 줄입니다. 거리는 미터입니다. 닫힌 고리는 가장 먼 점에서 둘로 나눠 줄입니다."""
    if len(pts) < 3:
        return pts
    if pts[0] == pts[-1]:
        far = max(range(len(pts)), key=lambda i: dist(pts[0], pts[i]))
        if 0 < far < len(pts) - 1:
            return simplify(pts[:far + 1], tol)[:-1] + simplify(pts[far:], tol)
    p = [xy(*q) for q in pts]
    keep = [False] * len(p)
    keep[0] = keep[-1] = True
    stack = [(0, len(p) - 1)]
    while stack:
        i, j = stack.pop()
        (x1, y1), (x2, y2) = p[i], p[j]
        dx, dy = x2 - x1, y2 - y1
        n = math.hypot(dx, dy) or 1e-9
        best, k = 0.0, -1
        for m in range(i + 1, j):
            d = abs(dy * (p[m][0] - x1) - dx * (p[m][1] - y1)) / n
            if d > best:
                best, k = d, m
        if best > tol:
            keep[k] = True
            stack += [(i, k), (k, j)]
    return [q for q, f in zip(pts, keep) if f]


def r6(pts):
    return [[round(a, 6), round(b, 6)] for a, b in pts]


def geom(e):
    return [(g["lat"], g["lon"]) for g in e["geometry"]]


# ---------- 본선 잇기 ----------
def westbound_chain(roads):
    ways = [e for e in roads if e["type"] == "way" and e.get("tags", {}).get("highway") == "motorway"
            and HIGHWAY_REF in e["tags"].get("ref", "").split(";")]
    key = lambda p: (round(p[0], 7), round(p[1], 7))
    by_start = {}
    for w in ways:
        g = geom(w)
        if g[-1][1] < g[0][1]:                      # 서쪽으로 가는 조각만
            by_start[key(g[0])] = w
    first = next(w for w in by_start.values()
                 if w["tags"].get("tunnel:name") == TUNNEL_NAME)
    # 터널 앞쪽으로 거슬러 올라가 출발 여유를 확보합니다.
    by_end = {key(geom(w)[-1]): w for w in by_start.values()}
    chain = [first]
    while sum(dist(a, b) for w in chain for a, b in zip(geom(w), geom(w)[1:])) < 3000:
        prev = by_end.get(key(geom(chain[0])[0]))
        if not prev:
            break
        chain.insert(0, prev)
    while True:
        nxt = by_start.get(key(geom(chain[-1])[-1]))
        if not nxt or nxt in chain or len(chain) > 80:
            break
        chain.append(nxt)
        if geom(nxt)[-1][1] < 127.225:
            break
    return chain


def cut(pts, cum, s0, s1):
    """누적 거리 s0~s1 사이의 선을 잘라 [위도, 경도, 출발부터 거리]로 돌려줍니다."""
    out = []

    def at(s):
        for (a, b), c0, c1 in zip(zip(pts, pts[1:]), cum, cum[1:]):
            if c0 <= s <= c1:
                t = (s - c0) / ((c1 - c0) or 1e-9)
                return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        return pts[-1]

    out.append((*at(s0), 0.0))
    for p, c in zip(pts, cum):
        if s0 < c < s1:
            out.append((p[0], p[1], c - s0))
    out.append((*at(s1), s1 - s0))
    return [[round(a, 6), round(b, 6), round(s, 1)] for a, b, s in out]


def rings(rel):
    """멀티폴리곤 관계의 바깥 고리를 이어 붙입니다."""
    parts = [[(g["lat"], g["lon"]) for g in m["geometry"]] for m in rel.get("members", [])
             if m.get("role") == "outer" and m.get("geometry")]
    out = []
    while parts:
        ring = parts.pop(0)
        grown = True
        while ring[0] != ring[-1] and grown:
            grown = False
            for i, p in enumerate(parts):
                if p[0] == ring[-1]:
                    ring += p[1:]
                elif p[-1] == ring[-1]:
                    ring += p[::-1][1:]
                else:
                    continue
                parts.pop(i)
                grown = True
                break
        out.append(ring)
    return out


def build(folder: Path):
    roads = json.loads((folder / "roads.json").read_text())["elements"]
    ctx = json.loads((folder / "context.json").read_text())["elements"]
    chain = westbound_chain(roads)
    pts = []
    for w in chain:
        g = geom(w)
        pts += g if not pts else g[1:]
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + dist(a, b))

    s = 0.0
    tunnel_in = tunnel_out = None
    for w in chain:
        g = geom(w)
        length = sum(dist(a, b) for a, b in zip(g, g[1:]))
        if w["tags"].get("tunnel:name") == TUNNEL_NAME:
            tunnel_in, tunnel_out = s, s + length
        s += length
    s0, s1 = tunnel_in - LEAD_IN, tunnel_out + AFTER_EXIT
    route = cut(pts, cum, s0, s1)
    events = [
        {"kind": "TUNNEL", "name": TUNNEL_NAME, "at": round(tunnel_in - s0), "length": round(tunnel_out - tunnel_in)},
        {"kind": "TUNNEL_EXIT", "name": TUNNEL_NAME, "at": round(tunnel_out - s0)},
    ]

    lats = [p[0] for p in route]
    lons = [p[1] for p in route]
    pad_lat, pad_lon = 0.005, 0.008
    # 서쪽 끝에 "← 서울" 글자 자리를 더 둡니다.
    extent = [min(lats) - pad_lat, min(lons) - pad_lon - 0.004, max(lats) + pad_lat, max(lons) + pad_lon]

    def inside(g):
        return any(extent[0] - 0.01 <= a <= extent[2] + 0.01 and extent[1] - 0.01 <= b <= extent[3] + 0.01 for a, b in g)

    lines = []
    for e in roads + ctx:
        t = e.get("tags", {})
        if e["type"] != "way" or "highway" not in t or t.get("barrier"):
            continue
        g = geom(e)
        if inside(g):
            lines.append({"c": t["highway"].replace("_link", ""), "tunnel": t.get("tunnel") == "yes",
                          "p": r6(simplify(g, 3))})
    areas = []
    for e in ctx:
        t = e.get("tags", {})
        kind = t.get("natural")
        if kind not in ("wood", "water"):
            continue
        polys = [geom(e)] if e["type"] == "way" else rings(e)
        for g in polys:
            if len(g) > 3 and inside(g):
                areas.append({"c": kind, "p": r6(simplify(g, 6))})
    labels = [{"c": e["tags"].get("place") or "peak", "name": e["tags"]["name"], "p": [round(e["lat"], 6), round(e["lon"], 6)]}
              for e in ctx if e["type"] == "node" and e.get("tags", {}).get("name")]

    data = {"attribution": "© OpenStreetMap contributors (ODbL 1.0)",
            "about": "서울양양고속도로 서울 방향. 월문1터널 입구 400m 앞에서 출발해 출구를 500m 지나 멈춥니다.",
            "speed_mps": SPEED, "route": route, "events": events, "extent": [round(v, 6) for v in extent],
            "lines": lines, "areas": areas, "labels": labels, "start_label": "양양 방향", "end_label": "← 서울"}
    OUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")))
    print(f"경로 {route[-1][2]:.0f}m, 점 {len(route)}개")
    for ev in events:
        print(f"  {ev['kind']:12s} {ev['at']:5d}m  {ev['at'] / SPEED:5.1f}초  {ev.get('name', '')}")
    print(f"도로 {len(lines)}, 면 {len(areas)}, 이름 {len(labels)}, 파일 {OUT.stat().st_size // 1024}KB")


if __name__ == "__main__":
    cmd, folder = sys.argv[1], Path(sys.argv[2])
    fetch(folder) if cmd == "fetch" else build(folder)
