"""시험 주행 첫 구간(동네 길)을 OpenStreetMap 자료에서 만듭니다. 남양주 화도읍 맷돌로, 과속방지턱 세 개 구간입니다.

    python3 tools/demo_video/route/make_town.py fetch <받을 폴더>
    python3 tools/demo_video/route/make_town.py build <받은 폴더>

- fetch는 Overpass에서 도로·과속방지턱과 숲·물·마을 자료를 받아 둡니다. 원본은 레포에 넣지 않습니다.
- build는 과속방지턱 세 개가 있는 맷돌로를 동쪽으로 따라가며, 첫 과속방지턱 200m 앞에서 출발해
  마지막 과속방지턱을 60m 지나 멈추는 경로를 town.json에 씁니다.
- 출력의 거리는 앱 시험 주행(DemoRoadEventSource)의 첫 구간과 영상 지도가 같이 씁니다.
- 자료: © OpenStreetMap contributors, ODbL 1.0. 앱과 영상에 출처를 표시합니다.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

from make_route import cut, dist, geom, r6, rings, simplify

OUT = Path(__file__).with_name("town.json")
ROAD_NAME = "맷돌로"
LEAD_IN = 200                 # 첫 과속방지턱 앞에서 출발하는 거리(m). 앱이 속도를 모르는 첫 순간에는 150m 안의 자극을 바로 알려서, 그보다 멀리서 출발합니다.
TAIL = 60                     # 마지막 과속방지턱을 지나 멈추는 거리(m)
SPEED = 30 / 3.6              # 시험 주행 속도(m/s). 시속 30km입니다.

BBOX = "37.645,127.262,37.672,127.318"
QUERIES = {
    "town_roads.json": f"""[out:json][timeout:90];
(
 way["highway"~"^(trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street)$"]({BBOX});
 node["traffic_calming"]({BBOX});
);
out body geom;""",
    "town_context.json": f"""[out:json][timeout:90];
(
 way["natural"~"^(wood|water)$"]({BBOX});
 relation["natural"~"^(wood|water)$"]({BBOX});
 way["waterway"~"^(river|stream)$"]({BBOX});
 node["place"~"^(village|hamlet|neighbourhood|quarter)$"]({BBOX});
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
                time.sleep(20 * (attempt + 1))


def build(folder: Path):
    roads = json.loads((folder / "town_roads.json").read_text())["elements"]
    ctx = json.loads((folder / "town_context.json").read_text())["elements"]
    humps = {e["id"] for e in roads if e["type"] == "node" and "traffic_calming" in e.get("tags", {})}
    # 과속방지턱이 가장 많은 맷돌로 조각을 씁니다. 조각의 점 순서가 서쪽에서 동쪽입니다.
    way = max((e for e in roads if e["type"] == "way" and e.get("tags", {}).get("name") == ROAD_NAME),
              key=lambda e: sum(n in humps for n in e["nodes"]))
    pts = geom(way)
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + dist(a, b))
    at = [c for n, c in zip(way["nodes"], cum) if n in humps]
    s0, s1 = at[0] - LEAD_IN, at[-1] + TAIL
    if s0 < 0 or s1 > cum[-1]:
        raise SystemExit("맷돌로 조각이 짧습니다. 이웃 조각을 이어 붙여야 합니다.")
    route = cut(pts, cum, s0, s1)
    events = [{"kind": "SPEED_BUMP", "name": "과속방지턱", "at": round(a - s0)} for a in at]

    lats = [p[0] for p in route]
    lons = [p[1] for p in route]
    pad_lat, pad_lon = 0.0016, 0.0024
    extent = [min(lats) - pad_lat, min(lons) - pad_lon, max(lats) + pad_lat, max(lons) + pad_lon]

    def inside(g):
        return any(extent[0] - 0.005 <= a <= extent[2] + 0.005 and extent[1] - 0.005 <= b <= extent[3] + 0.005 for a, b in g)

    lines = []
    for e in roads + ctx:
        t = e.get("tags", {})
        if e["type"] != "way":
            continue
        g = geom(e)
        if "highway" in t and inside(g):
            lines.append({"c": t["highway"].replace("_link", ""), "tunnel": t.get("tunnel") == "yes", "p": r6(simplify(g, 2))})
        elif t.get("waterway") and inside(g):
            lines.append({"c": "stream", "tunnel": False, "p": r6(simplify(g, 3))})
    areas = []
    for e in ctx:
        t = e.get("tags", {})
        kind = t.get("natural")
        if kind not in ("wood", "water"):
            continue
        polys = [geom(e)] if e["type"] == "way" else rings(e)
        for g in polys:
            if len(g) > 3 and inside(g):
                areas.append({"c": kind, "p": r6(simplify(g, 4))})
    labels = [{"c": e["tags"].get("place"), "name": e["tags"]["name"], "p": [round(e["lat"], 6), round(e["lon"], 6)]}
              for e in ctx if e["type"] == "node" and e.get("tags", {}).get("name")]

    data = {"attribution": "© OpenStreetMap contributors (ODbL 1.0)",
            "about": f"남양주 화도읍 {ROAD_NAME}, 동쪽으로. 첫 과속방지턱 {LEAD_IN}m 앞에서 출발해 마지막 과속방지턱을 {TAIL}m 지나 멈춥니다.",
            "speed_mps": round(SPEED, 3), "route": route, "events": events, "extent": [round(v, 6) for v in extent],
            "lines": lines, "areas": areas, "labels": labels, "road_label": ROAD_NAME}
    OUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")))
    print(f"경로 {route[-1][2]:.0f}m, 점 {len(route)}개, {route[-1][2] / SPEED:.1f}초")
    for ev in events:
        print(f"  {ev['kind']:12s} {ev['at']:5d}m  {ev['at'] / SPEED:5.1f}초")
    print(f"도로 {len(lines)}, 면 {len(areas)}, 이름 {len(labels)}, 파일 {OUT.stat().st_size // 1024}KB")


if __name__ == "__main__":
    cmd, folder = sys.argv[1], Path(sys.argv[2])
    fetch(folder) if cmd == "fetch" else build(folder)
