const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('launcher', {
  state: () => ipcRenderer.invoke('state'),
  settings: (patch) => ipcRenderer.invoke('settings', patch),
  login: () => ipcRenderer.invoke('login'),
  cancelLogin: () => ipcRenderer.invoke('login-cancel'),
  switchAccount: (uuid) => ipcRenderer.invoke('switch-account', uuid),
  removeAccount: (uuid) => ipcRenderer.invoke('remove-account', uuid),
  exportAccounts: (password) => ipcRenderer.invoke('export-accounts', password),
  pickImport: () => ipcRenderer.invoke('import-accounts-pick'),
  importAccounts: (password) => ipcRenderer.invoke('import-accounts', password),
  play: (opts) => ipcRenderer.invoke('play', opts),
  openFolder: () => ipcRenderer.invoke('open-folder'),
  openUrl: (url) => ipcRenderer.invoke('open-url', url),
  updateState: () => ipcRenderer.invoke('update-state'),
  installUpdate: () => ipcRenderer.invoke('update-install'),
  on: (channel, fn) => {
    if (['login-code', 'progress', 'game-exit', 'update', 'accounts'].includes(channel)) ipcRenderer.on(channel, (e, data) => fn(data));
  },
});
