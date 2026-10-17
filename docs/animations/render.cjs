// Renders a scene to a GIF: calls window.render(t) per frame, screenshots
// #stage, then lets ffmpeg build one shared palette.
//
//   NODE_PATH=<dir with playwright> node render.cjs <scene.html> <out.gif> [--fps 12] [--scale 1] [--query ep=recipe]
//   ... --stills 1.5,4,7 <dir>     writes PNG stills instead (for reviewing)
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf(`--${name}`); return i >= 0 ? args[i + 1] : def; };
const [scene, out] = args;
const fps = Number(opt('fps', 12));
const scale = Number(opt('scale', 1));
const query = opt('query', '');
const stills = opt('stills', null);

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: scale, viewport: { width: 1200, height: 1000 } });
  await page.goto('file://' + path.resolve(scene) + (query ? `?${query}` : ''));
  await page.evaluate(() => document.fonts.ready);
  const duration = await page.evaluate(() => window.DURATION);
  const stage = await page.$('#stage');

  if (stills) {
    fs.mkdirSync(out, { recursive: true });
    for (const t of stills.split(',').map(Number)) {
      await page.evaluate(t => window.render(t), t);
      await stage.screenshot({ path: path.join(out, `t${t.toFixed(2)}.png`) });
    }
    await browser.close();
    return;
  }

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'frames-'));
  const n = Math.round(duration * fps);
  for (let i = 0; i < n; i++) {
    await page.evaluate(t => window.render(t), i / fps);
    await stage.screenshot({ path: path.join(dir, `f${String(i).padStart(4, '0')}.png`) });
  }
  await browser.close();

  const input = ['-framerate', String(fps), '-i', path.join(dir, 'f%04d.png')];
  const palette = path.join(dir, 'palette.png');
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', ...input, '-vf', 'palettegen=max_colors=128:stats_mode=full', palette]);
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', ...input, '-i', palette, '-lavfi',
    'paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle', '-loop', '0', out]);
  fs.rmSync(dir, { recursive: true });
  console.log(`${out}: ${n} frames, ${(fs.statSync(out).size / 1024).toFixed(0)} KB`);
})();
