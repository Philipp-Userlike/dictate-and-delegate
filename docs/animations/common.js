// Timeline helpers. Each scene sets window.DURATION and window.render(t);
// render.mjs calls render(t) for every frame, so everything here is a pure
// function of t (no CSS animations, no timers).

const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const ramp = (t, a, b) => clamp((t - a) / (b - a));
const eio = x => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2);
const eout = x => 1 - Math.pow(1 - x, 3);
const back = x => { const c = 1.70158; return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); };
const lerp = (a, b, p) => a + (b - a) * p;
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];

function css(el, styles) {
  if (typeof el === 'string') el = $(el);
  if (!el) return;
  Object.assign(el.style, styles);
}

// Fade + slide/scale in at t in [a, b]; out at [c, d] if given.
function appear(el, t, a, b, { dy = 0, dx = 0, scale = 1, c = Infinity, d = Infinity, ease = eout } = {}) {
  const pin = ease(ramp(t, a, b));
  const pout = d === Infinity ? 0 : eio(ramp(t, c, d));
  const p = pin * (1 - pout);
  const s = scale === 1 ? 1 : lerp(scale, 1, pin === 1 ? 1 : back(ramp(t, a, b)));
  css(el, {
    opacity: p,
    transform: `translate(${(1 - pin) * dx}px, ${(1 - pin) * dy}px) scale(${s})`,
    visibility: p <= 0.001 ? 'hidden' : 'visible',
  });
  return p;
}

// Type text into an element, character by character.
function typeText(el, text, p) {
  if (typeof el === 'string') el = $(el);
  const n = Math.round(text.length * clamp(p));
  const out = text.slice(0, n);
  if (el.textContent !== out) el.textContent = out;
  return n;
}

// Speech balloon: words are laid out once and revealed one by one, so the
// balloon doesn't jump around while "listening".
function setupWords(el, text, names = []) {
  if (typeof el === 'string') el = $(el);
  el.innerHTML = '';
  for (const word of text.split(' ')) {
    const span = document.createElement('span');
    span.className = 'w' + (names.some(n => word.startsWith(n)) ? ' name' : '');
    span.textContent = word + ' ';
    el.appendChild(span);
  }
  return el;
}
function revealWords(el, t, a, b) {
  if (typeof el === 'string') el = $(el);
  const words = el.querySelectorAll('.w');
  const per = (b - a) / words.length;
  words.forEach((w, i) => { w.style.opacity = ramp(t, a + i * per, a + i * per + 0.15); });
}

// The floating panel, as FloatingPanel.java draws it (sizes in dp; scale it
// with a wrapper). While recording: [● 0:04 ■] [✨] [🧑‍💼] [✕] [📎].
// Afterwards: [✕] [📥] [✨] [🧑‍💼], plus [🧑‍💼 📥] when the chief sent text back,
// and the status line.
//   p = { rec: true, elapsed, insert: 'wait'|'ready', refine, chief, reply, status, press }
//   refine/chief: 'idle'|'queued'|'running'|'done'
function panel(sel, p) {
  const pill = (text, cls = '', id = '') => `<div class="tp-pill ${cls}" data-id="${id}">${text}</div>`;
  const refine = { idle: '✨', queued: '✨ ✓', running: '✨ …', done: '✨ 📥' }[p.refine || 'idle'];
  const refineCls = { queued: 'out', running: 'out', done: 'teal' }[p.refine] || '';
  const chief = { idle: '🧑‍💼', queued: '🧑‍💼 ✓', running: '🧑‍💼 …', done: '🧑‍💼 ↗' }[p.chief || 'idle'];
  const chiefCls = { queued: 'out', running: 'out' }[p.chief] || '';
  const secs = Math.max(0, Math.floor(p.elapsed || 0));
  let row;
  if (p.rec) {
    row = pill(`● 0:${String(secs).padStart(2, '0')}&nbsp;&nbsp;■`, 'timer', 'stop') + pill(refine, refineCls, 'refine')
      + pill(chief, chiefCls, 'chief') + pill('✕', '', 'close') + pill('📎', '', 'attach');
  } else {
    const ready = p.insert === 'ready';
    row = pill('✕', '', 'close') + pill(ready ? '📥' : '…', ready ? 'teal' : 'dim', 'insert')
      + pill(refine, refineCls, 'refine') + pill(chief, chiefCls, 'chief');
  }
  let html = `<div class="tp-bar"><div class="tp-row">${row}</div>`;
  if (p.reply) html += `<div class="tp-row" style="margin-top:6px">${pill('🧑‍💼 📥', 'teal', 'fromChief')}</div>`;
  if (p.status) html += `<div class="tp-status">${p.status}</div>`;
  html += '</div>';
  const root = $(sel);
  if (root.dataset.html !== html) { root.innerHTML = html; root.dataset.html = html; }
  root.querySelectorAll('.tp-pill').forEach(el => el.classList.toggle('pressed', el.dataset.id === p.press));
}

// Where a panel button is on the stage (for taps).
function pillAt(sel, id) {
  const r = $(sel).querySelector(`[data-id="${id}"]`).getBoundingClientRect();
  const s = $('#stage').getBoundingClientRect();
  return [r.left - s.left + r.width / 2, r.top - s.top + r.height / 2];
}

// Volume key held between a and b.
function volumeKey(t, a, b) {
  const held = t >= a && t < b;
  css('.vkey', { background: held ? 'var(--red)' : '#3A3F4C', transform: held ? 'translateX(-2px)' : 'none' });
  const pulse = held ? 0.55 + 0.45 * Math.sin((t - a) * 6) : 0;
  css('.vkey-ring', { opacity: held ? pulse : 0 });
  css('.vkey-label', { opacity: held ? 1 : 0 });
  return held;
}

// Finger tap: circle appears, shrinks a little, fades.
function tap(el, t, at, x, y) {
  if (typeof el === 'string') el = $(el);
  const p = ramp(t, at - 0.15, at + 0.45);
  const o = p <= 0 || p >= 1 ? 0 : (p < 0.3 ? p / 0.3 : 1 - (p - 0.3) / 0.7);
  css(el, { left: `${x}px`, top: `${y}px`, opacity: o, transform: `scale(${1.2 - 0.3 * clamp(p * 2)})` });
}

// Loop seam: fade from and back to the background colour.
function loopFade(t, dur, len = 0.35) {
  const o = Math.max(1 - ramp(t, 0, len), ramp(t, dur - len, dur));
  css('.fade', { opacity: o });
}
