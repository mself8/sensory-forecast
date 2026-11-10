#!/usr/bin/env python3
"""Pleos Connect SDK API 상세 문서의 명세를 텍스트로 읽는다.

document.pleos.ai의 API 상세 페이지는 본문이 스크립트 조각에 들어 있어서 HTML만 받으면 비어 있다.
이 스크립트는 사이트의 main·runtime 스크립트에서 페이지별 조각 파일을 찾아 받은 뒤 글자만 뽑는다.

    python3 pleos_api.py seat/move-seat-height door/register-door-position
    python3 pleos_api.py constant.control/move-control/entry

인자는 문서 경로의 끝부분이다. 받은 파일은 --cache 폴더에 둔다.
"""
import argparse
import html
import json
import re
import subprocess
from pathlib import Path

BASE = "https://document.pleos.ai"
PAGE = "/docs/pleos-only/connect-sdk-pleos/Vehicle/intro"


def get(url, out):
    if not out.exists():
        part = out.with_name(out.name + ".part")
        subprocess.run(["curl", "-sL", "--retry", "3", "--max-time", "90", "-A", "Mozilla/5.0",
                        "-o", str(part), url], check=True)
        part.rename(out)
    return out.read_text(errors="ignore")


def extract(js):
    best = ""
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', js):
        s = m.group(1)
        if "<div" in s and len(s) > len(best):
            best = s
    try:
        best = json.loads('"' + best + '"')
    except ValueError:
        pass
    best = re.sub(r"<br\s*/?>|</p>|</div>|</h\d>|</li>|</tr>", "\n", best)
    text = html.unescape(re.sub(r"<[^>]+>", " ", best))
    text = re.sub(r"[ \t]+", " ", text)
    lines = [ln.strip() for ln in text.splitlines()]
    return "\n".join(ln for ln in lines if ln and "copied to clipboard" not in ln)


def main():
    ap = argparse.ArgumentParser(description="Pleos API 상세 문서 읽기")
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--cache", type=Path, default=Path("/tmp/pleos_api_cache"))
    args = ap.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)

    page = get(BASE + PAGE, args.cache / "page.html")
    scripts = re.findall(r"src=\"?(/assets/js/[^\" >]+\.js)", page)
    main_js = next(s for s in scripts if "/main." in s)
    runtime_js = next(s for s in scripts if "runtime" in s)
    main = get(BASE + main_js, args.cache / Path(main_js).name)
    runtime = get(BASE + runtime_js, args.cache / Path(runtime_js).name)

    routes = dict(re.findall(
        r'"(/docs/pleos-only/[^"]+?)-[0-9a-f]{3}":\{"__comp":"[^"]+","content":"([0-9a-f]+)"', main))
    name_to_id = dict(re.findall(r'"([0-9a-f]{8})":"(\d+)"', runtime))
    seg = runtime[runtime.find("get javascript chunk filename"):]
    tables = re.findall(r"\{([^{}]*)\}\[chunkId\]", seg)
    names = dict(re.findall(r'"(\d+)":\s*"([0-9a-f]+)"', tables[0]))
    hashes = dict(re.findall(r'"(\d+)":\s*"([0-9a-f]+)"', tables[1]))

    for p in args.paths:
        hit = [k for k in routes if k.endswith("/" + p) or k.endswith("." + p)]
        if not hit:
            print(f"=== {p}: 문서 경로를 못 찾음\n")
            continue
        cid = name_to_id[routes[hit[0]]]
        fn = f"{names.get(cid, cid)}.{hashes[cid]}.js"
        js = get(f"{BASE}/assets/js/{fn}", args.cache / fn)
        print(f"=== {p}\n{extract(js)}\n")


if __name__ == "__main__":
    main()
