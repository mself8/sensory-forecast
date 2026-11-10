#!/usr/bin/env python3
"""OpenStreetMap에서 국내 도로 터널을 받아 앱이 쓰는 터널 입구 목록을 만듭니다.

    python3 tools/osm_tunnels.py                      # Overpass에서 받아서 만들기
    python3 tools/osm_tunnels.py --input kr.json      # 받아 둔 Overpass 결과로 만들기

결과는 app/src/main/assets/tunnels_kr.tsv 입니다. 한 줄이 한 방향 입구입니다.
    위도  경도  들어갈 때 방향(도)  길이(m)  이름

자료 출처: © OpenStreetMap contributors, ODbL 1.0. 앱 화면과 이 파일 머리말에 출처를 적습니다.

OSM은 한 터널을 여러 조각으로 나눠 그리는 일이 많습니다. 끝과 시작이 같은 점에서 한 갈래로만 이어지는 조각은 이어 붙여 한 터널로 셉니다.
일방통행이 아니면 양쪽 끝이 모두 입구라 두 줄을 씁니다. 고속도로는 따로 표시가 없어도 일방통행으로 봅니다.
"""
import argparse
import json
import math
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

QUERY = """[out:json][timeout:600];
area["ISO3166-1"="KR"][admin_level=2]->.kr;
way(area.kr)["tunnel"="yes"]["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"];
out tags geom;"""

MIN_LENGTH_M = 30.0
BEARING_FROM_M = 20.0  # 입구 방향은 입구에서 이만큼 들어간 점까지의 방향으로 잽니다. 첫 점 두 개가 너무 붙어 있는 경우를 피합니다.


def fetch(out: Path):
    subprocess.run(["curl", "-sS", "--max-time", "700", "-A", "sensory-forecast-dev/0.1",
                    "--data-urlencode", f"data={QUERY}", "https://overpass-api.de/api/interpreter",
                    "-o", str(out)], check=True)


def meters(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def bearing(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    y = math.sin(lo2 - lo1) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(lo2 - lo1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def directed_pieces(ways):
    """조각마다 차가 지나갈 수 있는 방향으로 점 목록을 만듭니다."""
    pieces = []
    for w in ways:
        tags = w.get("tags", {})
        pts = [(p["lat"], p["lon"]) for p in w["geometry"]]
        oneway = tags.get("oneway")
        name = tags.get("tunnel:name") or tags.get("name") or ""
        if oneway in ("yes", "1", "true") or (oneway is None and tags["highway"].startswith("motorway")):
            pieces.append((w["id"], pts, name))
        elif oneway == "-1":
            pieces.append((w["id"], pts[::-1], name))
        else:
            pieces.append((w["id"], pts, name))
            pieces.append((w["id"], pts[::-1], name))
    return pieces


def chains(pieces):
    """끝과 시작이 한 갈래로만 이어지는 조각을 이어 붙입니다."""
    key = lambda p: (round(p[0], 7), round(p[1], 7))
    starts, ends = defaultdict(list), defaultdict(list)
    for i, (_, pts, _) in enumerate(pieces):
        starts[key(pts[0])].append(i)
        ends[key(pts[-1])].append(i)

    def after(i):
        wid = pieces[i][0]
        nxt = [j for j in starts[key(pieces[i][1][-1])] if pieces[j][0] != wid]
        return nxt[0] if len(nxt) == 1 else None

    def before(i):
        wid = pieces[i][0]
        prv = [j for j in ends[key(pieces[i][1][0])] if pieces[j][0] != wid]
        return prv[0] if len(prv) == 1 else None

    used = set()
    out = []
    for i in range(len(pieces)):
        if i in used:
            continue
        head = i
        seen = {i}
        while (b := before(head)) is not None and after(b) == head and b not in seen:
            head = b
            seen.add(b)
        line, names, cur = [], [], head
        while cur is not None and cur not in used:
            used.add(cur)
            pts = pieces[cur][1]
            line += pts if not line else pts[1:]
            names.append(pieces[cur][2])
            nxt = after(cur)
            cur = nxt if nxt is not None and before(nxt) == cur else None
        out.append((line, next((n for n in names if n), "")))
    return out


def entrance(line):
    total = sum(meters(a, b) for a, b in zip(line, line[1:]))
    walked, far = 0.0, line[-1]
    for a, b in zip(line, line[1:]):
        walked += meters(a, b)
        if walked >= BEARING_FROM_M:
            far = b
            break
    return line[0], bearing(line[0], far), total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", type=Path, help="받아 둔 Overpass JSON")
    ap.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/main/assets/tunnels_kr.tsv")
    args = ap.parse_args()

    src = args.input
    if src is None:
        src = Path("/tmp/osm_tunnels_kr.json")
        fetch(src)
    data = json.loads(src.read_text())
    ways = [e for e in data["elements"] if e["type"] == "way" and len(e.get("geometry", [])) >= 2]

    rows = []
    for line, name in chains(directed_pieces(ways)):
        start, deg, length = entrance(line)
        if length >= MIN_LENGTH_M:
            rows.append(f"{start[0]:.5f}\t{start[1]:.5f}\t{deg:.0f}\t{length:.0f}\t{name.replace(chr(9), ' ')}")
    rows.sort()

    stamp = data.get("osm3s", {}).get("timestamp_osm_base", "")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        f"# 국내 도로 터널 입구. © OpenStreetMap contributors, ODbL 1.0. 기준 {stamp}\n"
        "# 위도\t경도\t방향(도)\t길이(m)\t이름\n" + "\n".join(rows) + "\n",
        encoding="utf-8",
    )
    print(f"조각 {len(ways)}개 → 입구 {len(rows)}개, {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
