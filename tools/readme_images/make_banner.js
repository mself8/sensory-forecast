// README 배너: 아이콘 + 앱 이름 + 부제 + 한 줄 특징. GitHub 밝은·어두운 테마용 투명 PNG 두 장.
//   NODE_PATH=<puppeteer가 있는 node_modules> CHROME=<chrome 경로> node tools/readme_images/make_banner.js
//   → docs/img/banner-light.png, docs/img/banner-dark.png
// 글꼴: Paperlogy(제목), Noto Sans CJK KR(부제). 시스템에 설치돼 있어야 합니다.
const path = require('path');
const puppeteer = require('puppeteer');

const ROOT = path.resolve(__dirname, '..', '..');
const TEXT = {
  title: '감각 예보',
  sub: '자폐 아이를 태운 차를 위한 Pleos Connect 앱',
  tag: '길 위 자극 미리 알림 · 창문 · 공조 · 조명 제어 · 아이 카드',
};
const THEMES = {
  light: { g1: '#0E8C8C', g2: '#5458D6', sub: '#3F4A5C', tag: '#5E687C', body: '#0E8C8C', glass: '#FFFFFF', wheel: '#1F2A44', wave: '#5458D6', dot: '#F28C28' },
  dark: { g1: '#38D1C9', g2: '#8E91FF', sub: '#D3D9E4', tag: '#A2ACBF', body: '#38D1C9', glass: '#0D1117', wheel: '#D5DAE3', wave: '#8E91FF', dot: '#F6A44C' },
};

const logo = (t) => `<svg width="230" height="230" viewBox="0 0 230 230" xmlns="http://www.w3.org/2000/svg">
  <path d="M127 59 A30 30 0 0 1 173 59" fill="none" stroke="${t.wave}" stroke-width="9" stroke-linecap="round"/>
  <path d="M109 43 A54 54 0 0 1 191 43" fill="none" stroke="${t.wave}" stroke-width="9" stroke-linecap="round" opacity="0.55"/>
  <circle cx="150" cy="76" r="10" fill="${t.dot}"/>
  <path d="M22 168 V140 Q22 126 36 124 L62 120 L92 92 Q100 84 112 84 H160 Q172 84 180 94 L200 122 Q212 126 212 140 V168 Q212 176 204 176 H30 Q22 176 22 168 Z" fill="${t.body}"/>
  <path d="M74 122 L98 98 Q102 94 108 94 H134 V122 Z" fill="${t.glass}" opacity="0.92"/>
  <path d="M144 94 H158 Q166 94 171 100 L187 122 H144 Z" fill="${t.glass}" opacity="0.92"/>
  <circle cx="64" cy="178" r="22" fill="${t.wheel}"/><circle cx="64" cy="178" r="9" fill="${t.glass}"/>
  <circle cx="170" cy="178" r="22" fill="${t.wheel}"/><circle cx="170" cy="178" r="9" fill="${t.glass}"/>
</svg>`;

const html = (t) => `<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;background:transparent}
#b{display:flex;align-items:center;gap:40px;padding:26px 44px;width:max-content}
h1{margin:0;font:700 148px/1.05 'Paperlogy','Noto Sans CJK KR',sans-serif;letter-spacing:-3px;
   background:linear-gradient(90deg,${t.g1},${t.g2});-webkit-background-clip:text;background-clip:text;color:transparent;padding-bottom:8px}
.sub{font:700 40px/1.3 'Noto Sans CJK KR','Paperlogy',sans-serif;color:${t.sub};margin-top:8px;letter-spacing:-0.5px}
.tag{font:400 27px/1.4 'Noto Sans CJK KR','Paperlogy',sans-serif;color:${t.tag};margin-top:12px}
</style></head><body><div id="b">${logo(t)}<div><h1>${TEXT.title}</h1>
<div class="sub">${TEXT.sub}</div><div class="tag">${TEXT.tag}</div></div></div></body></html>`;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME || undefined,
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--no-zygote', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1700, height: 500, deviceScaleFactor: 2 });
  for (const [name, t] of Object.entries(THEMES)) {
    await page.setContent(html(t), { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);
    const ok = await page.evaluate(() => document.fonts.check("700 148px 'Paperlogy'"));
    if (!ok) throw new Error('Paperlogy 글꼴을 찾지 못했습니다');
    const el = await page.$('#b');
    await el.screenshot({ path: path.join(ROOT, 'docs', 'img', `banner-${name}.png`), omitBackground: true });
  }
  await browser.close();
  console.log('docs/img/banner-light.png · docs/img/banner-dark.png');
})().catch((e) => { console.error(e); process.exit(1); });
