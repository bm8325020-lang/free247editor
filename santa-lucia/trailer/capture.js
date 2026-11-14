// node trailer/capture.js <url> <pasta-saida> [fps] [de] [até]   → quadros JPEG numerados
const { chromium } = require('playwright');
(async () => {
  const [url, out, fpsA, fromA, toA] = process.argv.slice(2); const fps = +(fpsA || 30);
  const fs = require('fs'); fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const p = await b.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.error('ERR', e.message));
  await p.goto(url); await p.waitForFunction(() => window.__tick); await p.addScriptTag({ path: __dirname + '/director.js' });
  await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(500);
  const dur = await p.evaluate(() => window.__duration); const n0 = Math.round((+(fromA || 0)) * fps), n1 = Math.round((toA ? +toA : dur) * fps);
  for (let i = n0; i < n1; i++) {
    await p.evaluate(([t, dt]) => window.__frame(t, dt), [i / fps, 1 / fps]);
    await p.screenshot({ path: `${out}/f${String(i).padStart(5, '0')}.jpg`, type: 'jpeg', quality: 90 });
    if (i % 60 === 0) console.log(`frame ${i}/${n1}`);
  }
  console.log('DONE', dur); await b.close();
})();
