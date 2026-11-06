// Port of the Android MarkdownTaskManager: same parsing rules and the same
// "new/unchecked tasks go to the top, checked tasks go right under DONE" placement.
const fs = require('fs');

const TASK_PATTERN = /^(\s*)(?:([-*+]|\d+\.)\s+)?\[([ xX])\]\s*(.*)$/;
const DONE_HEADER = /^\s*(?:#{1,6}\s+|\*{2}|_{2})?\s*(done|completed|finished|archive|archived)\b.*$/i;
const TODO_HEADER = /^\s*(?:#{1,6}\s+|\*{2}|_{2})?\s*(to[\s-]?do|tasks|pending|active)\b.*$/i;

const isTask = (l) => TASK_PATTERN.test(l);
const isActive = (l) => { const m = l.match(TASK_PATTERN); return !!m && m[3] === ' '; };
const isChecked = (l) => { const m = l.match(TASK_PATTERN); return !!m && m[3].toLowerCase() === 'x'; };
const isBlank = (l) => l.trim() === '';

function isDoneHeader(line) {
  const t = line.trim();
  return t !== '' && !isTask(line) && DONE_HEADER.test(t);
}

function isTodoHeader(line) {
  const t = line.trim();
  return t !== '' && !isTask(line) && TODO_HEADER.test(t);
}

function isIndentedSubLine(line) {
  if (isBlank(line) || isTask(line) || line.trim().startsWith('#')) return false;
  return line.startsWith('  ') || line.startsWith('\t');
}

function detectBulletStyle(lines) {
  for (const l of lines) {
    const m = l.match(TASK_PATTERN);
    if (m && m[2]) return m[2];
  }
  return '-';
}

function firstIndex(lines, from, pred) {
  for (let i = from; i < lines.length; i++) if (pred(lines[i])) return i;
  return -1;
}

function findInsertionIndex(lines) {
  if (lines.length === 0) return 0;

  let start = 0;
  if (lines[0].trim() === '---') {
    for (let i = 1; i < lines.length; i++) {
      if (lines[i].trim() === '---') { start = i + 1; break; }
    }
  }

  const todo = firstIndex(lines, start, isTodoHeader);
  if (todo !== -1) {
    let end = lines.length;
    for (let i = todo + 1; i < lines.length; i++) {
      const t = lines[i].trim();
      if (isDoneHeader(lines[i]) || (t.startsWith('#') && !isTask(t))) { end = i; break; }
    }
    for (let i = todo + 1; i < end; i++) if (isActive(lines[i])) return i;
    let at = todo + 1;
    if (at < end && isBlank(lines[at])) at++;
    return at;
  }

  const active = firstIndex(lines, start, isActive);
  if (active !== -1) return active;

  const done = firstIndex(lines, start, isDoneHeader);
  if (done !== -1) {
    let at = done;
    while (at > start && isBlank(lines[at - 1])) at--;
    return at;
  }

  const checked = firstIndex(lines, start, isChecked);
  if (checked !== -1) return checked;

  let at = start;
  while (at < lines.length && isBlank(lines[at])) at++;
  return at;
}

function findCheckedInsertionIndex(lines) {
  if (lines.length === 0) return 0;
  const done = lines.findIndex(isDoneHeader);
  if (done !== -1) {
    let at = done + 1;
    if (at < lines.length && isBlank(lines[at])) at++;
    return at;
  }
  const checked = lines.findIndex(isChecked);
  return checked !== -1 ? checked : lines.length;
}

// ---- pure line operations (exported for tests) ----

function parseTasks(lines) {
  const tasks = [];
  lines.forEach((line, i) => {
    const m = line.match(TASK_PATTERN);
    if (!m) return;
    tasks.push({
      line: i,
      raw: line,
      title: m[4].trim(),
      done: m[3].toLowerCase() === 'x',
      indent: Math.min(Math.floor(m[1].length / 2), 4),
    });
  });
  return tasks;
}

function toggleLines(lines, idx) {
  if (idx < 0 || idx >= lines.length) return null;
  const m = lines[idx].match(TASK_PATTERN);
  if (!m) return null;
  const [, indent, bullet, status, content] = m;
  const wasDone = status.toLowerCase() === 'x';
  const prefix = bullet ? `${bullet} ` : '';
  const block = [`${indent}${prefix}[${wasDone ? ' ' : 'x'}] ${content}`];
  let s = idx + 1;
  while (s < lines.length && isIndentedSubLine(lines[s])) block.push(lines[s++]);

  lines = lines.slice();
  lines.splice(idx, block.length);
  if (idx > 0 && idx < lines.length && isBlank(lines[idx - 1]) && isBlank(lines[idx])) lines.splice(idx, 1);

  if (!wasDone) {
    const done = lines.findIndex(isDoneHeader);
    if (done !== -1 && done === lines.length - 1) {
      lines.push('', ...block);
    } else {
      lines.splice(findCheckedInsertionIndex(lines), 0, ...block);
    }
  } else {
    const at = findInsertionIndex(lines);
    lines.splice(at, 0, ...block);
    const after = at + block.length;
    if (after < lines.length && isDoneHeader(lines[after])) lines.splice(after, 0, '');
  }
  return lines;
}

function addLines(lines, title) {
  title = title.trim();
  if (!title) return null;
  lines = lines.slice();
  const at = findInsertionIndex(lines);
  lines.splice(at, 0, `${detectBulletStyle(lines)} [ ] ${title}`);
  if (at + 1 < lines.length && isDoneHeader(lines[at + 1])) lines.splice(at + 1, 0, '');
  return lines;
}

function renameLines(lines, idx, title) {
  title = title.trim();
  if (!title || idx < 0 || idx >= lines.length) return null;
  const m = lines[idx].match(TASK_PATTERN);
  if (!m) return null;
  const [, indent, bullet, status] = m;
  lines = lines.slice();
  lines[idx] = `${indent}${bullet ? `${bullet} ` : ''}[${status}] ${title}`;
  return lines;
}

function deleteLines(lines, idx) {
  if (idx < 0 || idx >= lines.length || !isTask(lines[idx])) return null;
  lines = lines.slice();
  lines.splice(idx, 1);
  return lines;
}

// ---- operations (for Nextcloud mode) ----
// A change is stored as an operation that names the task by its text, not its
// line number. It can then be re-applied to whatever version is on the server
// right now, so a tick on one device never wipes out an edit made on another.

function findTask(lines, title, done) {
  return lines.findIndex((l) => {
    const m = l.match(TASK_PATTERN);
    return m && m[4].trim() === title && (m[3].toLowerCase() === 'x') === done;
  });
}

// Returns new lines, or null when the op no longer applies (task gone or
// already in the target state) and can be dropped.
function applyOp(lines, op) {
  if (op.type === 'add') return addLines(lines, op.title);
  const i = findTask(lines, op.title, op.done);
  if (i === -1) return null;
  if (op.type === 'toggle') return toggleLines(lines, i);
  if (op.type === 'rename') return renameLines(lines, i, op.newTitle);
  if (op.type === 'delete') return deleteLines(lines, i);
  return null;
}

function applyOps(lines, ops) {
  for (const op of ops) lines = applyOp(lines, op) || lines;
  return lines;
}

// Turn "the task the user clicked" into an op, checking it is still there.
function opFor(lines, type, idx, raw, extra = {}) {
  if (lines[idx] !== raw) return null;
  const m = raw.match(TASK_PATTERN);
  if (!m) return null;
  return { type, title: m[4].trim(), done: m[3].toLowerCase() === 'x', ...extra };
}

// ---- text <-> lines (keeps line endings and trailing newline) ----

function splitText(text) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  const trailing = text.endsWith(eol);
  let lines = text.split(/\r?\n/);
  if (trailing) lines.pop();
  if (text === '') lines = [];
  return { lines, eol, trailing };
}

function joinText(file, lines) {
  let text = lines.join(file.eol);
  if (file.trailing || lines.length) text += file.eol;
  return text;
}

// ---- file I/O ----

function readFile(path) {
  const text = fs.readFileSync(path, 'utf8');
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  const trailing = text.endsWith(eol);
  let lines = text.split(/\r?\n/);
  if (trailing) lines.pop();
  if (text === '') lines = [];
  return { lines, eol, trailing };
}

function writeFile(path, file, lines) {
  let text = lines.join(file.eol);
  if (file.trailing || lines.length) text += file.eol;
  fs.writeFileSync(path, text, 'utf8');
}

// Every mutation re-reads the file and checks the task line is still what the
// UI showed, so an edit made in Obsidian meanwhile is never clobbered.
function mutate(path, expectedRaw, idx, op) {
  const file = readFile(path);
  if (expectedRaw != null && file.lines[idx] !== expectedRaw) {
    return { ok: false, error: 'Note changed on disk. Refreshed, try again.' };
  }
  const next = op(file.lines);
  if (!next) return { ok: false, error: 'Could not apply change.' };
  writeFile(path, file, next);
  return { ok: true };
}

module.exports = {
  parseTasks, toggleLines, addLines, renameLines, deleteLines,
  applyOp, applyOps, opFor, splitText, joinText,
  readFile, writeFile, mutate,
  loadTasks: (path) => parseTasks(readFile(path).lines),
  toggle: (path, idx, raw) => mutate(path, raw, idx, (l) => toggleLines(l, idx)),
  add: (path, title) => mutate(path, null, 0, (l) => addLines(l, title)),
  rename: (path, idx, raw, title) => mutate(path, raw, idx, (l) => renameLines(l, idx, title)),
  remove: (path, idx, raw) => mutate(path, raw, idx, (l) => deleteLines(l, idx)),
};
