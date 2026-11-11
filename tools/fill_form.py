"""접수양식 docx의 "02 앱 프로젝트 소개" 1~8번 칸을 기획서 초안으로 채운 사본을 만듭니다.

    python3 tools/fill_form.py <양식 압축 푼 폴더> <draft.md>

- 양식은 연락처와 서명이 들어가므로 레포에 올리지 않습니다. 압축을 푼 폴더를 받아 word/document.xml을 고칩니다.
- 01 참가신청서, 03 동의서, 04 서명은 건드리지 않습니다. 개인 정보는 팀이 직접 씁니다.
- 공지 7에 따라 항목과 순서는 지키고, 칸 안의 글은 서식 있는 문단과 그림으로 바꿉니다.
- 칸 7 참고자료에는 별지 출처 목록을 함께 넣습니다.
"""
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape

from PIL import Image

ROOT = Path(sys.argv[1])
DRAFT = Path(sys.argv[2])
REPO = Path(__file__).resolve().parents[1]
APP_NAME = "감각 예보"

SIZE = 20          # 10pt
HEAD_COLOR = "1F4E79"
STATUS_COLORS = {"구현": "2E7D32", "최종 PT까지": "B25E00"}
CELL_EMU = 6_100_000

doc_path = ROOT / "word/document.xml"
doc = doc_path.read_text(encoding="utf-8")

# ---------- 목록 기호 정의 ----------
num_path = ROOT / "word/numbering.xml"
num = num_path.read_text(encoding="utf-8")
abs_id = max(int(i) for i in re.findall(r'w:abstractNumId="(\d+)"', num)) + 1
num_id = max(int(i) for i in re.findall(r'<w:num w:numId="(\d+)"', num)) + 1
bullet_abs = (
    f'<w:abstractNum w:abstractNumId="{abs_id}"><w:multiLevelType w:val="hybridMultilevel"/>'
    f'<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/>'
    f'<w:pPr><w:ind w:left="300" w:hanging="200"/></w:pPr><w:rPr><w:rFonts w:ascii="맑은 고딕" w:hAnsi="맑은 고딕"/></w:rPr></w:lvl>'
    f'<w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="–"/><w:lvlJc w:val="left"/>'
    f'<w:pPr><w:ind w:left="600" w:hanging="200"/></w:pPr><w:rPr><w:rFonts w:ascii="맑은 고딕" w:hAnsi="맑은 고딕"/></w:rPr></w:lvl>'
    f'</w:abstractNum>'
)
first_num = num.index("<w:num ")
num = num[:first_num] + bullet_abs + num[first_num:]
num = num.replace("</w:numbering>", f'<w:num w:numId="{num_id}"><w:abstractNumId w:val="{abs_id}"/></w:num></w:numbering>')
num_path.write_text(num, encoding="utf-8")

# ---------- 그림 ----------
ct_path = ROOT / "[Content_Types].xml"
ct = ct_path.read_text(encoding="utf-8")
if 'Extension="png"' not in ct:
    ct = ct.replace("<Default ", '<Default Extension="png" ContentType="image/png"/><Default ', 1)
    ct_path.write_text(ct, encoding="utf-8")
rels_path = ROOT / "word/_rels/document.xml.rels"
rels = rels_path.read_text(encoding="utf-8")
next_rid = max(int(i) for i in re.findall(r'Id="rId(\d+)"', rels)) + 1
pic_no = 100
images = {}


def add_image(src: Path, name: str) -> str:
    global next_rid, rels
    (ROOT / "word/media").mkdir(exist_ok=True)
    (ROOT / "word/media" / name).write_bytes(src.read_bytes())
    rid = f"rId{next_rid}"
    next_rid += 1
    rels = rels.replace("</Relationships>",
                        f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/{name}"/></Relationships>')
    w, h = Image.open(src).size
    images[name] = (rid, w, h)
    return name


def picture(name: str) -> str:
    global pic_no
    rid, w, h = images[name]
    cx = CELL_EMU
    cy = int(cx * h / w)
    pic_no += 1
    return (
        '<w:p><w:pPr><w:spacing w:before="80" w:after="40"/><w:jc w:val="center"/></w:pPr><w:r><w:drawing>'
        f'<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="{cx}" cy="{cy}"/>'
        f'<wp:docPr id="{pic_no}" name="그림 {pic_no}"/>'
        '<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
        '<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">'
        f'<pic:nvPicPr><pic:cNvPr id="{pic_no}" name="{name}"/><pic:cNvPicPr/></pic:nvPicPr>'
        f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
        f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>'
        '</wp:inline></w:drawing></w:r></w:p>'
    )


add_image(REPO / "docs/img/screen-child-card.png", "screen-child-card.png")
add_image(REPO / "docs/img/overview.png", "overview.png")

# ---------- 마크다운을 문단으로 ----------


def runs(text: str, bold=False, color=None, size=SIZE) -> str:
    out = []
    for part in re.split(r"(\*\*[^*]+\*\*|`[^`]+`)", text):
        if not part:
            continue
        b, mono, c = bold, False, color
        if part.startswith("**"):
            part, b = part[2:-2], True
        elif part.startswith("`"):
            part = part[1:-1]
            if part in STATUS_COLORS:
                # 구현 여부 표시는 코드 글꼴 대신 색 있는 굵은 글씨로 씁니다.
                b, c = True, STATUS_COLORS[part]
                part = f"[{part}]"
            else:
                mono = True
        rpr = ""
        if mono:
            rpr += '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>'
        if b:
            rpr += "<w:b/><w:bCs/>"
        if c:
            rpr += f'<w:color w:val="{c}"/>'
        rpr += f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>'
        out.append(f'<w:r><w:rPr>{rpr}</w:rPr><w:t xml:space="preserve">{escape(part)}</w:t></w:r>')
    return "".join(out)


def para(text, *, level=None, number=None, bold=False, color=None, before=0, after=40, keep_next=False, size=SIZE):
    ppr = "<w:pPr>"
    if keep_next:
        ppr += "<w:keepNext/>"
    if level is not None:
        ppr += f'<w:numPr><w:ilvl w:val="{level}"/><w:numId w:val="{num_id}"/></w:numPr>'
    ppr += f'<w:spacing w:before="{before}" w:after="{after}" w:line="288" w:lineRule="auto"/>'
    if number is not None:
        ppr += '<w:ind w:left="300" w:hanging="300"/>'
    ppr += "</w:pPr>"
    body = (runs(f"{number}. ", bold, color, size) if number is not None else "") + runs(text, bold, color, size)
    return f"<w:p>{ppr}{body}</w:p>"


def blocks(md: str) -> str:
    out = []
    for raw in md.strip().splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip() == "---":
            continue
        m_head = re.fullmatch(r"\*\*([^*]+)\*\*", line.strip())
        m_b2 = re.match(r"^  - (.*)", line)
        m_b1 = re.match(r"^- (.*)", line)
        m_num = re.match(r"^(\d+)\. (.*)", line)
        if m_head:
            out.append(para(m_head.group(1), bold=True, color=HEAD_COLOR, before=120, after=40))
        elif m_b2:
            out.append(para(m_b2.group(1), level=1))
        elif m_b1:
            out.append(para(m_b1.group(1), level=0))
        elif m_num:
            out.append(para(m_num.group(2), number=m_num.group(1)))
        else:
            out.append(para(line))
    return "".join(out)


def caption(text):
    return para(text, color="666666", size=17, after=80)


md = DRAFT.read_text(encoding="utf-8")
sections = {}
for m in re.finditer(r"^## (\d)\. [^\n]*\n(.*?)(?=^## |\Z)", md, flags=re.S | re.M):
    sections[int(m.group(1))] = m.group(2)
sources = re.search(r"^## 별지: 출처\n(.*)", md, flags=re.S | re.M).group(1)

fill = {i: blocks(sections[i]) for i in range(1, 9)}
fill[3] = fill[3].replace(
    para("새로운 경험", bold=True, color=HEAD_COLOR, before=120, after=40),
    picture("screen-child-card.png") + caption("에뮬레이터의 시작 화면. 앱 왼쪽에서 아이 카드를 정하고, 오른쪽 아래에서 지난 이동의 기록과 부모 평가를 봅니다.")
    + para("새로운 경험", bold=True, color=HEAD_COLOR, before=120, after=40), 1)
fill[5] = picture("overview.png") + caption("역할별 흐름. 부모가 아이 카드를 정하면 앱이 내비 사건을 골라 알리고 차 안을 바꾸며, 기록으로 카드를 고칩니다.") + fill[5]
fill[7] = fill[7] + para("출처", bold=True, color=HEAD_COLOR, before=160, after=40) + "".join(
    para(m.group(2), number=m.group(1), size=17, after=20) for m in re.finditer(r"^(\d+)\. (.*)$", sources, flags=re.M))

# ---------- 칸 채우기: 콘텐츠 컨트롤이 든 문단을 통째로 바꿉니다 ----------
sdts = list(re.finditer(r"<w:sdt>.*?</w:sdt>", doc, flags=re.S))
assert len(sdts) == 8, len(sdts)
for i, m in reversed(list(enumerate(sdts, start=1))):
    p_start = doc.rfind("<w:p ", 0, m.start())
    p_end = doc.index("</w:p>", m.end()) + len("</w:p>")
    # 양식의 칸은 쪽을 넘어 나뉘지 않게 돼 있어, 긴 글이 통째로 다음 쪽으로 밀립니다. 채운 칸만 나뉠 수 있게 합니다.
    tr_start = doc.rfind("<w:tr ", 0, m.start())
    head = doc[tr_start:p_start].replace("<w:cantSplit/>", "", 1)
    doc = doc[:tr_start] + head + fill[i] + doc[p_end:]

# 02 머리의 앱명 칸
marker = "앱명 / 프로젝트명: "
hits = [m.start() for m in re.finditer(re.escape(marker), doc)]
second = hits[1]
t_end = doc.index("</w:t>", second)
seg = doc[second:t_end]
doc = doc[:second] + re.sub(r"_+", APP_NAME, seg, count=1) + doc[t_end:]

doc_path.write_text(doc, encoding="utf-8")
rels_path.write_text(rels, encoding="utf-8")
print("채움: 칸 8개, 그림", len(images), "장, 출처", len(re.findall(r"^\d+\. ", sources, flags=re.M)), "건")
