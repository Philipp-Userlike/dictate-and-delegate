'use strict';
// The call bar: the call's state in short (a dot and a word or two), Send now whenever you can
// send, and the red hang-up. A tap on the state opens the full call in the main window.
const $ = (id) => document.getElementById(id);

function render(s) {
  if (!s) return;
  $('label').textContent = s.shortLabel || s.liveLabel || '';
  $('state').title = s.liveLabel || '';
  const dot = $('dot');
  dot.className = 'dot';
  const live = String(s.live || '');
  if (s.voiceError || /fail/i.test(s.shortLabel || '')) dot.classList.add('failed');
  else if (['HEARING', 'WAITING_SEND', 'WRITING'].includes(live)) dot.classList.add('you');
  else if (['PLAYING', 'REPLY_COMING', 'REPLY_WAITING'].includes(live)) dot.classList.add('chief');
  else if (['SENDING', 'WAITING_CHIEF'].includes(live)) dot.classList.add('busy');
  $('send').hidden = !s.canSend;
  $('end').disabled = !s.canEnd;
  requestAnimationFrame(() => {
    const r = $('bar').getBoundingClientRect();
    dd.send('callbar:size', Math.ceil(r.width) + 2, Math.ceil(r.height) + 2);
  });
}

$('send').addEventListener('click', () => dd.send('callbar:action', 'send'));
$('end').addEventListener('click', () => dd.send('callbar:action', 'end'));
$('state').addEventListener('click', () => dd.send('callbar:action', 'open'));
dd.on('call', render);
dd.send('callbar:ready');
