// Renders a scene to a GIF: calls window.render(t) per frame, screenshots
// #stage, then lets ffmpeg build one shared palette.
//
//   NODE_PATH=<dir with playwright> node render.cjs <scene.html> <out.gif> [--fps 12] [--scale 1] [--query ep=recipe]
//   ... --stills 1.5,4,7 <dir>     writes PNG stills instead (for reviewing)
//   ... <out.mp4> --poster 6.6 [--poster-query still] [--hold 0.8]
//                                  an H.264 video whose first frame (the poster GitHub shows)
//                                  is the scene at --poster, held, then the whole story;
//                                  also writes <out>-poster.png
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
const poster = opt('poster', null);
const posterQuery = opt('poster-query', '');
const hold = Number(opt('hold', 0.8));

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

  if (poster !== null) {
    const p = await browser.newPage({ deviceScaleFactor: scale, viewport: { width: 1200, height: 1000 } });
    await p.goto('file://' + path.resolve(scene) + (posterQuery ? `?${posterQuery}` : ''));
    await p.evaluate(() => document.fonts.ready);
    await p.evaluate(t => window.render(t), Number(poster));
    const posterPng = out.replace(/\.mp4$/, '-poster.png');
    await (await p.$('#stage')).screenshot({ path: posterPng });
    await browser.close();
    // The poster, held, then a short fade into the story's first frame (which fades in from the background).
    const even = 'scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p';
    execFileSync('ffmpeg', ['-loglevel', 'error', '-y',
      '-loop', '1', '-framerate', String(fps), '-t', String(hold), '-i', posterPng,
      '-framerate', String(fps), '-i', path.join(dir, 'f%04d.png'),
      '-filter_complex', `[0]${even},setsar=1[a];[1]${even},setsar=1[b];[a][b]xfade=transition=fade:duration=0.3:offset=${hold - 0.3},format=yuv420p[v]`,
      '-map', '[v]', '-c:v', 'libx264', '-profile:v', 'high', '-level', '4.0', '-preset', 'veryslow',
      '-crf', opt('crf', '26'), '-tune', 'animation', '-movflags', '+faststart', '-an', out]);
    fs.rmSync(dir, { recursive: true });
    console.log(`${out}: ${(fs.statSync(out).size / 1024).toFixed(0)} KB`);
    return;
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
