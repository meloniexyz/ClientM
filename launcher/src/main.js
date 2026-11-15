const { app, BrowserWindow, ipcMain, shell, safeStorage } = require('electron');
const fs = require('fs');
const path = require('path');
const auth = require('./auth');
const { install } = require('./install');
const { launch } = require('./launch');
const manifest = require('../manifest.json');

const userData = app.getPath('userData'); // %APPDATA%\ClientM
const root = path.join(userData, 'minecraft');
const settingsFile = path.join(userData, 'settings.json');
const accountFile = path.join(userData, 'account.bin');
const resourcesDir = app.isPackaged ? process.resourcesPath : path.join(__dirname, '..', 'resources');

let win;
let game = null;
let loginCancelled = false;

// ---------------------------------------------------------------- stored state

function readSettings() {
  let s = {};
  try {
    s = JSON.parse(fs.readFileSync(settingsFile, 'utf8'));
  } catch (e) {
    // defaults
  }
  return { heapGb: manifest.defaultHeapGb, azureClientId: '', ...s };
}

function writeSettings(s) {
  fs.mkdirSync(userData, { recursive: true });
  fs.writeFileSync(settingsFile, JSON.stringify(s, null, 2));
}

/** The saved account: { name, uuid, refreshToken }. The file is encrypted with the Windows user's key. */
function readAccount() {
  try {
    return JSON.parse(safeStorage.decryptString(fs.readFileSync(accountFile)));
  } catch (e) {
    return null;
  }
}

function writeAccount(a) {
  if (!a) return fs.rmSync(accountFile, { force: true });
  fs.writeFileSync(accountFile, safeStorage.encryptString(JSON.stringify(a)));
}

function state() {
  const s = readSettings();
  const a = readAccount();
  return {
    account: a ? { name: a.name } : null,
    clientIdSet: !!s.azureClientId,
    azureClientId: s.azureClientId,
    heapGb: s.heapGb,
    running: !!game,
    version: app.getVersion(),
  };
}

const send = (channel, data) => win && !win.isDestroyed() && win.webContents.send(channel, data);

// ---------------------------------------------------------------- actions

ipcMain.handle('state', () => state());

ipcMain.handle('settings', (e, patch) => {
  const s = readSettings();
  if (typeof patch.heapGb === 'number') s.heapGb = Math.max(2, Math.min(8, Math.round(patch.heapGb)));
  if (typeof patch.azureClientId === 'string') s.azureClientId = patch.azureClientId.trim();
  writeSettings(s);
  return state();
});

ipcMain.handle('login', async () => {
  const { azureClientId } = readSettings();
  if (!azureClientId) throw new Error('Add the Azure client ID in Settings first.');
  loginCancelled = false;
  const code = await auth.startDeviceCode(azureClientId);
  send('login-code', { code: code.user_code, url: code.verification_uri });
  const ms = await auth.waitForDeviceCode(azureClientId, code, () => loginCancelled);
  const session = await auth.minecraftSession(ms.access_token);
  writeAccount({ name: session.name, uuid: session.uuid, refreshToken: ms.refresh_token });
  return state();
});

ipcMain.handle('login-cancel', () => {
  loginCancelled = true;
});

ipcMain.handle('logout', () => {
  writeAccount(null);
  return state();
});

ipcMain.handle('play', async () => {
  if (game) throw new Error('The game is already running.');
  const s = readSettings();
  const saved = readAccount();
  if (!saved) throw new Error('Sign in first.');

  send('progress', { text: 'Signing in', fraction: 0 });
  const ms = await auth.refreshMicrosoft(s.azureClientId, saved.refreshToken);
  const session = await auth.minecraftSession(ms.access_token);
  writeAccount({ name: session.name, uuid: session.uuid, refreshToken: ms.refresh_token || saved.refreshToken });

  const inst = await install(root, manifest, resourcesDir, (text, fraction) => send('progress', { text, fraction }));
  send('progress', { text: 'Starting Minecraft', fraction: 1 });
  game = launch(inst, session, { heapGb: s.heapGb, jvmFlags: manifest.jvmFlags });
  game.on('exit', (code) => {
    game = null;
    send('game-exit', { code });
  });
  game.on('error', (err) => {
    game = null;
    send('game-exit', { code: -1, error: err.message });
  });
  return state();
});

ipcMain.handle('open-folder', () => {
  fs.mkdirSync(path.join(root, 'game'), { recursive: true });
  return shell.openPath(path.join(root, 'game'));
});

// Only Microsoft's own sign-in pages may be opened from the window.
ipcMain.handle('open-url', (e, url) => {
  const host = new URL(url).hostname;
  if (/(^|\.)microsoft\.com$|(^|\.)microsoftonline\.com$|(^|\.)live\.com$/.test(host)) return shell.openExternal(url);
});

// ---------------------------------------------------------------- window

app.whenReady().then(() => {
  win = new BrowserWindow({
    width: 880,
    height: 540,
    resizable: false,
    backgroundColor: '#000000',
    autoHideMenuBar: true,
    title: 'ClientM',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));

  // Developer aid: CLIENTM_LAUNCHER_SHOT=<file.png> saves a picture of the window and quits.
  const shot = process.env.CLIENTM_LAUNCHER_SHOT;
  if (shot) {
    win.webContents.once('did-finish-load', () => {
      setTimeout(async () => {
        fs.writeFileSync(shot, (await win.webContents.capturePage()).toPNG());
        app.quit();
      }, 1200);
    });
  }
});

app.on('window-all-closed', () => app.quit());
