const { app, BrowserWindow, ipcMain, dialog, shell, screen, safeStorage } = require('electron');
const fs = require('fs');
const path = require('path');
const tasks = require('./tasks');
const { RemoteStore, importRemotelySave } = require('./remote');

if (!app.requestSingleInstanceLock()) app.quit();

const userFile = (n) => path.join(app.getPath('userData'), n);
let config = {
  filePath: null, bounds: null, doneExpanded: false,
  remote: { enabled: false, url: '', user: '', remotePath: '', password: '' },
};
let win = null;
let quitting = false;
let watched = null;
let store = null;       // RemoteStore when Nextcloud mode is on
let pollTimer = null;
let lastFocusSync = 0;

// ---- config (app password encrypted with Windows DPAPI via safeStorage) ----

function loadConfig() {
  try {
    const c = JSON.parse(fs.readFileSync(userFile('config.json'), 'utf8').replace(/^﻿/, ''));
    config = { ...config, ...c, remote: { ...config.remote, ...(c.remote || {}) } };
    if (config.remote.passwordEnc && safeStorage.isEncryptionAvailable()) {
      config.remote.password = safeStorage.decryptString(Buffer.from(config.remote.passwordEnc, 'base64'));
    }
  } catch {}
}

function saveConfig() {
  const out = { ...config, remote: { ...config.remote } };
  if (safeStorage.isEncryptionAvailable() && out.remote.password) {
    out.remote.passwordEnc = safeStorage.encryptString(out.remote.password).toString('base64');
    delete out.remote.password;
  }
  try { fs.writeFileSync(userFile('config.json'), JSON.stringify(out, null, 2)); } catch {}
}

const remoteOn = () => {
  const r = config.remote;
  return r.enabled && r.url && r.user && r.password && r.remotePath;
};

function setupStore() {
  clearInterval(pollTimer);
  store = null;
  if (!remoteOn()) return;
  store = new RemoteStore(userFile('nextcloud-state.json'), config.remote);
  sync();
  pollTimer = setInterval(sync, 60 * 1000);
}

let syncing = false;
async function sync() {
  if (!store) return;
  syncing = true;
  push();
  await store.sync();
  syncing = false;
  push();
}

// ---- state sent to the window ----

function state() {
  const base = {
    mode: store ? 'nextcloud' : 'local',
    filePath: config.filePath,
    remotePath: config.remote.remotePath,
    doneExpanded: config.doneExpanded,
    tasks: [],
    error: null,
    sync: null,
  };
  if (store) {
    const lines = store.view();
    base.tasks = lines ? tasks.parseTasks(lines) : [];
    base.sync = { syncing, pending: store.state.ops.length, lastSync: store.state.lastSync, error: store.state.error };
    if (!lines) base.error = store.state.error || 'Loading from Nextcloud…';
    return base;
  }
  if (!config.filePath) return base;
  try {
    base.tasks = tasks.loadTasks(config.filePath);
  } catch (e) {
    base.error = `Can't read note: ${e.code || e.message}`;
  }
  return base;
}

function push() {
  if (win && !win.isDestroyed()) win.webContents.send('state', state());
}

// Local mode only. Poll-based watch survives the delete+rename sync clients do.
function watch() {
  if (watched) fs.unwatchFile(watched);
  watched = config.filePath;
  if (watched) fs.watchFile(watched, { interval: 1000 }, () => { if (!store) push(); });
}

// ---- window / tray ----

function onScreen(b) {
  return b && screen.getAllDisplays().some(({ workArea: w }) =>
    b.x < w.x + w.width - 40 && b.x + b.width > w.x + 40 && b.y >= w.y - 10 && b.y < w.y + w.height - 40);
}

function createWindow() {
  const wa = screen.getPrimaryDisplay().workArea;
  const def = { width: 400, height: 520, x: wa.x + wa.width - 430, y: wa.y + 30 };
  const b = onScreen(config.bounds) ? config.bounds : def;

  win = new BrowserWindow({
    ...b,
    minWidth: 360,
    minHeight: 200,
    frame: false,
    transparent: true,
    resizable: true,
    show: false,
    title: 'Obsidian Tasks',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  win.loadFile(path.join(__dirname, 'index.html'));
  win.once('ready-to-show', () => win.show());

  let t;
  const remember = () => { clearTimeout(t); t = setTimeout(() => { config.bounds = win.getBounds(); saveConfig(); }, 400); };
  win.on('move', remember);
  win.on('resize', remember);
  win.on('focus', () => {
    if (store && Date.now() - lastFocusSync > 10000) { lastFocusSync = Date.now(); sync(); } else push();
  });
  // Closing quits the app. If changes are still waiting to upload, give the
  // upload a few seconds first (they are kept on disk either way and go up
  // the next time the app opens).
  win.on('close', (e) => {
    if (quitting || !store || !store.state.ops.length) return;
    e.preventDefault();
    quitting = true;
    Promise.race([sync(), new Promise((r) => setTimeout(r, 5000))]).finally(() => win.destroy());
  });
  win.webContents.setWindowOpenHandler(({ url }) => { openExternal(url); return { action: 'deny' }; });
}

function openExternal(url) {
  if (/^(https?|obsidian|mailto):/i.test(url)) shell.openExternal(url);
}

function showWindow() {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function setOption(key, value) {
  if (key === 'doneExpanded') config.doneExpanded = !!value;
  saveConfig();
  push();
}

async function chooseFile() {
  const r = await dialog.showOpenDialog(win, {
    title: 'Choose your to-do note',
    defaultPath: config.filePath || app.getPath('documents'),
    filters: [{ name: 'Markdown', extensions: ['md', 'markdown', 'txt'] }],
    properties: ['openFile'],
  });
  if (r.canceled || !r.filePaths[0]) return;
  config.filePath = r.filePaths[0];
  saveConfig();
  watch();
  push();
}

// ---- task actions: local file, or queued op + Nextcloud sync ----

function act(type, line, raw, extra) {
  if (store) {
    const r = store.queue(type, line, raw, extra);
    push();
    if (r.ok) sync();
    return r;
  }
  if (!config.filePath) return { ok: false, error: 'No note selected.' };
  try {
    const p = config.filePath;
    const r = type === 'toggle' ? tasks.toggle(p, line, raw)
      : type === 'add' ? tasks.add(p, extra.title)
      : type === 'rename' ? tasks.rename(p, line, raw, extra.newTitle)
      : tasks.remove(p, line, raw);
    push();
    return r;
  } catch (e) {
    push();
    return { ok: false, error: e.code === 'EBUSY' || e.code === 'EPERM' ? 'Note is locked, try again.' : e.message };
  }
}

ipcMain.handle('state', () => state());
ipcMain.handle('toggle', (_, line, raw) => act('toggle', line, raw));
ipcMain.handle('add', (_, title) => act('add', -1, null, { title: String(title).trim() }));
ipcMain.handle('rename', (_, line, raw, title) => act('rename', line, raw, { newTitle: String(title).trim() }));
ipcMain.handle('remove', (_, line, raw) => act('delete', line, raw));
ipcMain.handle('refresh', async () => { if (store) await sync(); return state(); });
ipcMain.handle('choose-file', chooseFile);
ipcMain.handle('set-option', (_, k, v) => setOption(k, v));
ipcMain.handle('open-url', (_, url) => openExternal(url));
ipcMain.handle('open-obsidian', () => {
  if (config.filePath) shell.openExternal(`obsidian://open?path=${encodeURIComponent(config.filePath)}`);
});
ipcMain.handle('minimize', () => win && win.minimize());
ipcMain.handle('close', () => win && win.close());

// Nextcloud settings. The password never goes back to the window.
const remoteView = () => {
  const { enabled, url, user, remotePath, password } = config.remote;
  return { enabled: !!enabled, url, user, remotePath, hasPassword: !!password };
};
ipcMain.handle('remote-get', remoteView);
ipcMain.handle('remote-save', (_, r) => {
  const prev = config.remote;
  const next = {
    enabled: !!r.enabled,
    url: (r.url || '').trim(),
    user: (r.user || '').trim(),
    remotePath: (r.remotePath || '').trim(),
    password: r.password ? r.password : prev.password,
  };
  if (next.url !== prev.url || next.user !== prev.user || next.remotePath !== prev.remotePath) {
    try { fs.unlinkSync(userFile('nextcloud-state.json')); } catch {}
  }
  config.remote = next;
  saveConfig();
  setupStore();
  push();
  return remoteView();
});
ipcMain.handle('remote-import', () => {
  if (!config.filePath) return { ok: false, error: 'Choose the note in your vault first.' };
  try {
    const r = importRemotelySave(config.filePath);
    config.remote = { ...config.remote, ...r };
    saveConfig();
    return { ok: true, ...remoteView() };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});
ipcMain.handle('remote-test', async (_, r) => {
  const creds = { ...config.remote, ...r, password: r.password || config.remote.password };
  try {
    return await new RemoteStore(userFile('nextcloud-test.json'), creds).test();
  } catch (e) {
    return { ok: false, error: `Can't reach Nextcloud (${e.cause?.code || e.message}).` };
  }
});

app.on('second-instance', showWindow);
app.whenReady().then(() => {
  // No custom AppUserModelId: Windows then groups the window with the pinned
  // exe shortcut, so it opens on the pinned taskbar button.
  loadConfig();
  // Older builds could register a start-with-Windows entry; this is a normal app now.
  if (app.getLoginItemSettings().openAtLogin) app.setLoginItemSettings({ openAtLogin: false });
  createWindow();
  watch();
  setupStore();
});
app.on('before-quit', () => { if (store) store.save(); });
app.on('window-all-closed', () => app.quit());
