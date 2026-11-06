const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs'); const os = require('os'); const path = require('path');
const { RemoteStore, resolveUrl } = require('../src/remote');

// Minimal fake Nextcloud WebDAV: ETags, If-Match/If-None-Match, optional locks.
function fakeServer(text) {
  const s = { text, ver: 1, lockFor: 0, puts: 0, interleave: null };
  s.etag = () => `"v${s.ver}"`;
  s.fetch = async (url, { method, headers, body }) => {
    const res = (status, b = '', h = {}) => ({
      status, ok: status >= 200 && status < 300,
      headers: { get: (k) => h[k.toLowerCase()] ?? null }, text: async () => b,
    });
    if (s.lockFor > 0) { s.lockFor--; return res(423); }
    if (method === 'GET' || method === 'HEAD') {
      if (headers['If-None-Match'] === s.etag()) return res(304);
      return res(200, s.text, { etag: 'W/' + s.etag(), 'oc-etag': s.etag() });
    }
    if (method === 'PUT') {
      if (s.interleave) { const f = s.interleave; s.interleave = null; f(); }
      if (headers['If-Match'] && headers['If-Match'] !== s.etag()) return res(412);
      s.text = body; s.ver++; s.puts++;
      return res(201, '', { etag: 'W/' + s.etag(), 'oc-etag': s.etag() });
    }
  };
  return s;
}

const tmp = () => path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'ot-')), 'state.json');
const creds = { url: 'https://nc.example/remote.php/dav/files/admin', user: 'admin', password: 'x', remotePath: "JP's Obsidian vault/To do.md" };
const NOTE = '- [ ] Rajma\n- [ ] Add sysadmin to agent\n#### DONE\n- [x] Bread\n';

test('url encoding', () => {
  assert.equal(resolveUrl(creds.url, 'admin', creds.remotePath),
    "https://nc.example/remote.php/dav/files/admin/JP's%20Obsidian%20vault/To%20do.md");
  assert.equal(resolveUrl('https://nc.example/', 'a b', '/x.md'), 'https://nc.example/remote.php/dav/files/a%20b/x.md');
});

test('toggle uploads with If-Match and clears the queue', async () => {
  const srv = fakeServer(NOTE);
  const st = new RemoteStore(tmp(), creds, srv.fetch);
  await st.sync();
  assert.ok(st.queue('toggle', 0, '- [ ] Rajma').ok);
  await st.sync();
  assert.equal(srv.text, '- [ ] Add sysadmin to agent\n#### DONE\n- [x] Rajma\n- [x] Bread\n');
  assert.equal(st.state.ops.length, 0);
  assert.equal(st.state.error, null);
});

test('edit made elsewhere is kept: 412 -> re-read -> re-apply', async () => {
  const srv = fakeServer(NOTE);
  const st = new RemoteStore(tmp(), creds, srv.fetch);
  await st.sync();
  st.queue('add', -1, null, { title: 'Milk' });
  // Phone saves a new task between our GET and PUT.
  srv.interleave = () => { srv.text = '- [ ] Eggs\n' + srv.text; srv.ver++; };
  await st.sync();
  assert.equal(srv.text, '- [ ] Milk\n- [ ] Eggs\n- [ ] Rajma\n- [ ] Add sysadmin to agent\n#### DONE\n- [x] Bread\n');
  assert.equal(st.state.ops.length, 0);
});

test('423 Locked is retried', async () => {
  const srv = fakeServer(NOTE);
  const st = new RemoteStore(tmp(), creds, srv.fetch);
  await st.sync();
  st.queue('delete', 1, '- [ ] Add sysadmin to agent');
  srv.lockFor = 2;
  await st.sync();
  assert.ok(!srv.text.includes('sysadmin'));
  assert.equal(st.state.error, null);
});

test('offline: change stays queued and shows in view, uploads later', async () => {
  const srv = fakeServer(NOTE);
  const st = new RemoteStore(tmp(), creds, srv.fetch);
  await st.sync();
  st.fetch = async () => { throw new TypeError('fetch failed', { cause: { code: 'ENOTFOUND' } }); };
  st.queue('rename', 0, '- [ ] Rajma', { newTitle: 'Rajma chawal' });
  await st.sync();
  assert.match(st.state.error, /ENOTFOUND/);
  assert.equal(st.view()[0], '- [ ] Rajma chawal');
  assert.equal(srv.puts, 0);
  st.fetch = srv.fetch;
  await st.sync();
  assert.ok(srv.text.startsWith('- [ ] Rajma chawal\n'));
});

test('toggle of a task already ticked elsewhere is dropped, nothing uploaded', async () => {
  const srv = fakeServer(NOTE);
  const st = new RemoteStore(tmp(), creds, srv.fetch);
  await st.sync();
  st.queue('toggle', 0, '- [ ] Rajma');
  srv.text = '- [ ] Add sysadmin to agent\n#### DONE\n- [x] Rajma\n- [x] Bread\n'; srv.ver++;
  await st.sync();
  assert.equal(srv.puts, 0);
  assert.equal(st.state.ops.length, 0);
});

test('nothing changed -> 304, no upload', async () => {
  const srv = fakeServer(NOTE);
  const st = new RemoteStore(tmp(), creds, srv.fetch);
  await st.sync(); await st.sync();
  assert.equal(srv.puts, 0);
  assert.equal(st.state.base, NOTE);
});
