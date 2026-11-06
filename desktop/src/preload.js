const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  state: () => ipcRenderer.invoke('state'),
  onState: (cb) => ipcRenderer.on('state', (_, s) => cb(s)),
  toggle: (line, raw) => ipcRenderer.invoke('toggle', line, raw),
  add: (title) => ipcRenderer.invoke('add', title),
  rename: (line, raw, title) => ipcRenderer.invoke('rename', line, raw, title),
  remove: (line, raw) => ipcRenderer.invoke('remove', line, raw),
  refresh: () => ipcRenderer.invoke('refresh'),
  remoteGet: () => ipcRenderer.invoke('remote-get'),
  remoteSave: (r) => ipcRenderer.invoke('remote-save', r),
  remoteImport: () => ipcRenderer.invoke('remote-import'),
  remoteTest: (r) => ipcRenderer.invoke('remote-test', r),
  chooseFile: () => ipcRenderer.invoke('choose-file'),
  setOption: (k, v) => ipcRenderer.invoke('set-option', k, v),
  openUrl: (url) => ipcRenderer.invoke('open-url', url),
  openObsidian: () => ipcRenderer.invoke('open-obsidian'),
  hide: () => ipcRenderer.invoke('hide'),
  quit: () => ipcRenderer.invoke('quit'),
});
