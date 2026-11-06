const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs'); const os = require('os'); const path = require('path');
const T = require('../src/tasks');

const NOTE = ['- [ ] Rajma', '- [ ] Add sysadmin to agent', '#### DONE', '- [x] Bread', '- [x] Yoghurt'];

test('parses tasks', () => {
  const t = T.parseTasks(NOTE);
  assert.equal(t.filter((x) => !x.done).length, 2);
  assert.equal(t.filter((x) => x.done).length, 2);
});
test('checking moves to top of DONE', () => {
  assert.deepEqual(T.toggleLines(NOTE, 1), ['- [ ] Rajma', '#### DONE', '- [x] Add sysadmin to agent', '- [x] Bread', '- [x] Yoghurt']);
});
test('unchecking moves to top of active', () => {
  assert.deepEqual(T.toggleLines(NOTE, 4), ['- [ ] Yoghurt', '- [ ] Rajma', '- [ ] Add sysadmin to agent', '#### DONE', '- [x] Bread']);
});
test('add goes to top', () => {
  assert.deepEqual(T.addLines(NOTE, ' Milk ').slice(0, 2), ['- [ ] Milk', '- [ ] Rajma']);
});
test('add into empty active list before DONE', () => {
  assert.deepEqual(T.addLines(['#### DONE', '- [x] a'], 'b'), ['- [ ] b', '', '#### DONE', '- [x] a']);
});
test('rename and delete', () => {
  assert.equal(T.renameLines(NOTE, 0, 'Beans')[0], '- [ ] Beans');
  assert.deepEqual(T.deleteLines(NOTE, 0), NOTE.slice(1));
  assert.equal(T.deleteLines(NOTE, 2), null);
});
test('file roundtrip keeps CRLF and rejects stale edits', () => {
  const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'ot-')), 'To do.md');
  fs.writeFileSync(p, NOTE.join('\r\n') + '\r\n');
  assert.ok(T.toggle(p, 0, '- [ ] Rajma').ok);
  assert.ok(fs.readFileSync(p, 'utf8').endsWith('- [x] Rajma\r\n- [x] Bread\r\n- [x] Yoghurt\r\n'));
  assert.equal(T.toggle(p, 0, '- [ ] Rajma').ok, false);
});
