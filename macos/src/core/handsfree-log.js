'use strict';
// Port of HandsFreeLog.java.
//
// The hands-free log (Settings → Hands-free → Recent events): each event is appended as a line,
// so a line once written stays. The file is only rewritten to drop old lines, from what was read
// in full, into a temporary file that then takes its place; a read that fails leaves it as it is.
// Node runs these synchronous file calls one at a time, so they need no lock (the Java's one lock
// for the whole app): a late event of an earlier call writes through the same functions.

const fs = require('fs');

/** The lines kept across calls. */
const KEEP = 1000;

/** Past this size the oldest lines are dropped: down to KEEP, and to three quarters of it. */
const TRIM_BYTES = 512 * 1024;

/** Lines appended since the app started: every hundred, the line count is checked. */
let appended = 0;

/** Appends one line (a line break in it is kept on the same line). */
function append(file, line, keep = KEEP, trimBytes = TRIM_BYTES) {
  const bytes = Buffer.from(String(line).replace(/\n/g, ' ') + '\n', 'utf8');
  try {
    fs.appendFileSync(file, bytes);
    if (fs.statSync(file).size > trimBytes || (++appended % 100 === 0 && lineCount(file) > keep)) trim(file, keep, trimBytes);
  } catch {
    /* The log is a help, not a must. */
  }
}

/**
 * Keeps the last keep lines, in at most three quarters of trimBytes: written to a temporary file
 * that replaces the log.
 */
function trim(file, keep, trimBytes) {
  const lines = readLines(file); // Throws: kept as it is.
  let from = Math.max(0, lines.length - keep);
  let size = 0;
  for (let i = from; i < lines.length; i++) size += Buffer.byteLength(lines[i], 'utf8') + 1;
  while (from < lines.length - 1 && size > (trimBytes * 3) / 4) size -= Buffer.byteLength(lines[from++], 'utf8') + 1;
  const temp = file + '.tmp';
  fs.writeFileSync(temp, lines.slice(from).join('\n') + '\n');
  fs.renameSync(temp, file);
}

/** The file's lines, as Files.readAllLines reads them (no empty last line for the final break). */
function readLines(file) {
  const text = fs.readFileSync(file, 'utf8');
  if (text === '') return [];
  const lines = text.split(/\r?\n/);
  if (lines[lines.length - 1] === '') lines.pop();
  return lines;
}

/** The whole log, "" if there's none. */
function read(file) {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    return '';
  }
}

const CALL_STARTED = '── call started ';

/** The line that starts a call's events: "── call started 14:05 ──". */
function callStarted(time) {
  return CALL_STARTED + time + ' ──';
}

/**
 * The last calls of a log, newest included: from the line that started the oldest of them to
 * the end, so no call is cut. The whole log if it holds fewer calls ("Copy last call", "last 3").
 */
function lastCalls(log, calls) {
  let from = log.length;
  for (let i = 0; i < calls; i++) {
    const at = from === 0 ? -1 : log.lastIndexOf(CALL_STARTED, from - 1);
    if (at < 0) return log;
    from = at;
  }
  return log.substring(from);
}

function lineCount(file) {
  return readLines(file).length;
}

module.exports = { KEEP, TRIM_BYTES, append, read, callStarted, lastCalls };
