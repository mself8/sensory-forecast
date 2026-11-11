const fs = require('fs');
// puppeteer 위치는 PUPPETEER 환경 변수로 바꿀 수 있습니다.
const puppeteer = require(process.env.PUPPETEER || 'puppeteer');
const seconds = Number(process.argv[2] || 120);
(async () => {
  const b = await puppeteer.launch({headless: true, args: ['--no-sandbox', '--no-zygote']});
  const p = await b.newPage();
  await p.setViewport({width: 1920, height: 1080});
  await p.goto('http://127.0.0.1:8088/', {waitUntil: 'domcontentloaded', timeout: 30000});
  await new Promise(r => setTimeout(r, 3000));
  const {execSync} = require('child_process');
  const B = 'http://127.0.0.1:8088';
  execSync(`curl -s -m 5 -X POST "${B}/browser/session?client=claude-demo"`);
  execSync(`curl -s -m 5 -X PUT "${B}/vehicle/body?window_rr=50&ambient_color=%2300e5ff"`);
  execSync(`curl -s -m 5 -X PUT "${B}/vehicle/hvac?recirc=false&fan_speed=3"`);
  await new Promise(r => setTimeout(r, 3000));
  fs.writeFileSync('capture_ready.txt', String(Date.now()));
  const end = Date.now() + seconds * 1000;
  let n = 0;
  while (Date.now() < end) {
    const t = Date.now();
    await p.screenshot({path: `frames/c_${t}.jpg`, type: 'jpeg', quality: 88, clip: {x: 0, y: 0, width: 1920, height: 1080}});
    n++;
    const wait = 200 - (Date.now() - t);
    if (wait > 0) await new Promise(r => setTimeout(r, wait));
  }
  console.log('frames', n);
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
