// Nextcloud (WebDAV) mode.
//
// Rules that keep this from fighting Obsidian's Remotely Save plugin:
//  1. The widget only ever writes the Nextcloud copy. It never touches the
//     vault file on disk; Remotely Save brings the change down on its next run
//     as a plain "remote changed" download, with no conflict.
//  2. Every upload is conditional (If-Match: <etag we last saw>). If someone
//     else saved in between, the server answers 412, we download the new
//     version and re-apply our pending changes to it. Nothing is overwritten.
//  3. Changes are queued as operations ("tick task 'Bread'"), not file
//     snapshots, so they survive being re-applied, being offline, and
//     "423 Locked" answers while another client is mid-upload.
const fs = require('fs');
const T = require('./tasks');

function resolveUrl(base, user, remotePath) {
  const cleanBase = base.trim().replace(/\/+$/, '');
  const segs = remotePath.trim().replace(/^\/+/, '').split('/').filter(Boolean).map(encodeURIComponent).join('/');
  return cleanBase.includes('/remote.php/dav/files/')
    ? `${cleanBase}/${segs}`
    : `${cleanBase}/remote.php/dav/files/${encodeURIComponent(user)}/${segs}`;
}

// Nextcloud's own OC-ETag survives reverse proxies; the plain ETag header gets
// turned into a weak W/"…" tag by gzip, which never matches If-Match.
const tagOf = (h) => {
  const t = h.get('oc-etag') || h.get('etag');
  return t ? t.replace(/^W\//, '') : null;
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class HttpError extends Error {
  constructor(status, msg) { super(msg); this.status = status; }
}

function describe(status) {
  if (status === 401 || status === 403) return 'Nextcloud login failed. Check username and app password.';
  if (status === 423) return 'Nextcloud says the note is locked. Will retry.';
  if (status === 404) return 'Note not found on Nextcloud. Check the remote path.';
  return `Nextcloud answered HTTP ${status}.`;
}

class RemoteStore {
  // creds: { url, user, password, remotePath }
  constructor(statePath, creds, fetchImpl = fetch) {
    this.statePath = statePath;
    this.creds = creds;
    this.fetch = fetchImpl;
    this.state = { base: null, etag: null, ops: [], lastSync: null, error: null };
    this.running = null;
    this.again = false;
    try { this.state = { ...this.state, ...JSON.parse(fs.readFileSync(statePath, 'utf8')) }; } catch {}
  }

  save() {
    try { fs.writeFileSync(this.statePath, JSON.stringify(this.state)); } catch {}
  }

  get url() { return resolveUrl(this.creds.url, this.creds.user, this.creds.remotePath); }
  get auth() { return 'Basic ' + Buffer.from(`${this.creds.user}:${this.creds.password}`).toString('base64'); }

  // What the widget shows: last server copy + changes not uploaded yet.
  view() {
    if (this.state.base == null) return null;
    const file = T.splitText(this.state.base);
    return T.applyOps(file.lines, this.state.ops);
  }

  // Queue a change made on the widget; returns false if it no longer applies.
  queue(type, idx, raw, extra) {
    const lines = this.view();
    if (!lines) return { ok: false, error: 'Not synced yet.' };
    const op = type === 'add' ? { type: 'add', ...extra } : T.opFor(lines, type, idx, raw, extra);
    if (!op || !T.applyOp(lines, op)) return { ok: false, error: 'Note changed. Refreshed, try again.' };
    op.id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    this.state.ops.push(op);
    this.save();
    return { ok: true };
  }

  async get() {
    const headers = { Authorization: this.auth };
    if (this.state.base != null && this.state.etag) headers['If-None-Match'] = this.state.etag;
    const r = await this.fetch(this.url, { method: 'GET', headers, cache: 'no-store' });
    if (r.status === 304) return { text: this.state.base, etag: this.state.etag, exists: true };
    if (r.status === 404) return { text: '', etag: null, exists: false };
    if (!r.ok) throw new HttpError(r.status, describe(r.status));
    return { text: await r.text(), etag: tagOf(r.headers), exists: true };
  }

  async put(text, etag, exists) {
    const headers = { Authorization: this.auth, 'Content-Type': 'text/markdown; charset=utf-8' };
    if (exists && etag) headers['If-Match'] = etag;
    if (!exists) headers['If-None-Match'] = '*';
    const r = await this.fetch(this.url, { method: 'PUT', headers, body: text });
    return { status: r.status, etag: tagOf(r.headers) };
  }

  // One sync at a time; a request made during a sync runs one more pass after it.
  sync() {
    if (this.running) { this.again = true; return this.running; }
    this.running = (async () => {
      try {
        do { this.again = false; await this.syncOnce(); } while (this.again);
        this.state.error = null;
        this.state.lastSync = Date.now();
      } catch (e) {
        this.state.error = e instanceof HttpError ? e.message : `Can't reach Nextcloud (${e.cause?.code || e.message}).`;
      } finally {
        this.save();
        this.running = null;
      }
    })();
    return this.running;
  }

  async syncOnce() {
    const backoff = [500, 1000, 2000, 4000, 8000];
    for (let attempt = 0; attempt <= backoff.length; attempt++) {
      let remote;
      try {
        remote = await this.get();
      } catch (e) {
        if (e.status === 423 && attempt < backoff.length) { await sleep(backoff[attempt]); continue; }
        throw e;
      }
      this.state.base = remote.text;
      this.state.etag = remote.etag;

      const ops = this.state.ops.slice();
      if (!ops.length) return;

      const file = T.splitText(remote.text);
      const next = T.joinText(file, T.applyOps(file.lines, ops));
      const done = new Set(ops.map((o) => o.id));
      if (next === remote.text) {           // already applied elsewhere / no-ops
        this.state.ops = this.state.ops.filter((o) => !done.has(o.id));
        return;
      }

      const r = await this.put(next, remote.etag, remote.exists);
      if (r.status >= 200 && r.status < 300) {
        this.state.base = next;
        this.state.etag = r.etag || null;   // null forces a full GET next time
        this.state.ops = this.state.ops.filter((o) => !done.has(o.id));
        if (!this.state.ops.length) return;
        attempt = -1;                        // more ops arrived while uploading
        continue;
      }
      if (r.status === 412) continue;        // someone saved first: re-read and re-apply
      if (r.status === 423 && attempt < backoff.length) { await sleep(backoff[attempt]); continue; }
      throw new HttpError(r.status, describe(r.status));
    }
    throw new HttpError(423, "Couldn't save to Nextcloud yet (busy or locked). Your changes are kept and will retry.");
  }

  async test() {
    const r = await this.fetch(this.url, { method: 'HEAD', headers: { Authorization: this.auth } });
    if (r.ok) return { ok: true, message: 'Connected. Note found.' };
    return { ok: false, error: describe(r.status) };
  }
}

// Read the WebDAV settings Remotely Save already has, so nobody retypes them.
function importRemotelySave(notePath) {
  const path = require('path');
  let dir = path.dirname(notePath);
  while (!fs.existsSync(path.join(dir, '.obsidian'))) {
    const up = path.dirname(dir);
    if (up === dir) throw new Error('This note is not inside an Obsidian vault.');
    dir = up;
  }
  const f = path.join(dir, '.obsidian', 'plugins', 'remotely-save', 'data.json');
  if (!fs.existsSync(f)) throw new Error('Remotely Save is not set up in this vault.');
  let cfg = JSON.parse(fs.readFileSync(f, 'utf8'));
  if (typeof cfg.d === 'string') cfg = JSON.parse(Buffer.from(cfg.d.split('').reverse().join(''), 'base64').toString('utf8'));
  if (cfg.serviceType !== 'webdav' || !cfg.webdav) throw new Error('Remotely Save is not using WebDAV.');
  const w = cfg.webdav;
  const base = (w.remoteBaseDir || path.basename(dir)).replace(/^\/+|\/+$/g, '');
  const rel = path.relative(dir, notePath).split(path.sep).join('/');
  return { url: w.address, user: w.username, password: w.password, remotePath: `${base}/${rel}` };
}

module.exports = { RemoteStore, resolveUrl, importRemotelySave };
