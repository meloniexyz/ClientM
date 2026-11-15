const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('launcher', {
  state: () => ipcRenderer.invoke('state'),
  settings: (patch) => ipcRenderer.invoke('settings', patch),
  login: () => ipcRenderer.invoke('login'),
  cancelLogin: () => ipcRenderer.invoke('login-cancel'),
  logout: () => ipcRenderer.invoke('logout'),
  play: (opts) => ipcRenderer.invoke('play', opts),
  openFolder: () => ipcRenderer.invoke('open-folder'),
  openUrl: (url) => ipcRenderer.invoke('open-url', url),
  updateState: () => ipcRenderer.invoke('update-state'),
  installUpdate: () => ipcRenderer.invoke('update-install'),
  on: (channel, fn) => {
    if (['login-code', 'progress', 'game-exit', 'update'].includes(channel)) ipcRenderer.on(channel, (e, data) => fn(data));
  },
});
