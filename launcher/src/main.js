const { app, BrowserWindow, ipcMain, shell, safeStorage, nativeImage } = require('electron');
const fs = require('fs');
const path = require('path');
const auth = require('./auth');
const { install } = require('./install');
const { launch } = require('./launch');
const manifest = require('../manifest.json');
const { autoUpdater } = require('electron-updater');

// Developer aid: CLIENTM_USERDATA points the launcher at a separate data folder for tests.
if (process.env.CLIENTM_USERDATA) app.setPath('userData', process.env.CLIENTM_USERDATA);
const userData = app.getPath('userData'); // %APPDATA%\ClientM
const root = path.join(userData, 'minecraft');
const settingsFile = path.join(userData, 'settings.json');
const accountFile = path.join(userData, 'account.bin'); // one account, before 0.2.7
const accountsFile = path.join(userData, 'accounts.bin');
const facesDir = path.join(userData, 'faces');
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
  return { heapGb: manifest.defaultHeapGb, azureClientId: '', renderer: 'vulkan', ...s };
}

function writeSettings(s) {
  fs.mkdirSync(userData, { recursive: true });
  fs.writeFileSync(settingsFile, JSON.stringify(s, null, 2));
}

/**
 * The saved accounts: { active: uuid, list: [{ name, uuid, refreshToken }] }. The file is encrypted with the Windows
 * user's key. A single account saved by an older launcher is taken over the first time.
 */
function readAccounts() {
  try {
    const d = JSON.parse(safeStorage.decryptString(fs.readFileSync(accountsFile)));
    if (d && Array.isArray(d.list)) return d;
  } catch (e) {
    // none yet
  }
  try {
    const old = JSON.parse(safeStorage.decryptString(fs.readFileSync(accountFile)));
    if (old && old.uuid) {
      const d = { active: old.uuid, list: [old] };
      // The old file stays, so an older launcher on this PC still finds its account.
      writeAccounts(d);
      return d;
    }
  } catch (e) {
    // no old account either
  }
  return { active: null, list: [] };
}

function writeAccounts(d) {
  fs.mkdirSync(userData, { recursive: true });
  if (!d.list.find((a) => a.uuid === d.active)) d.active = d.list.length ? d.list[0].uuid : null;
  fs.writeFileSync(accountsFile, safeStorage.encryptString(JSON.stringify(d)));
}

/** Adds or updates one account. With makeActive, it also becomes the account Play uses. */
function saveAccount(a, makeActive) {
  const d = readAccounts();
  const i = d.list.findIndex((x) => x.uuid === a.uuid);
  if (i >= 0) d.list[i] = { ...d.list[i], ...a };
  else d.list.push(a);
  if (makeActive) d.active = a.uuid;
  writeAccounts(d);
}

const facePath = (uuid) => path.join(facesDir, uuid + '.png');

/**
 * Saves the account's face as an 8x8 picture: the face square of the skin with the hat layer on top, as Minecraft
 * draws a head. Failures only mean the face stays the old one or the default.
 */
async function saveFace(uuid, skinUrl) {
  if (!skinUrl) return;
  try {
    const res = await fetch(skinUrl);
    if (!res.ok) return;
    const skin = nativeImage.createFromBuffer(Buffer.from(await res.arrayBuffer()));
    const { width } = skin.getSize();
    if (width < 64) return;
    const scale = width / 64;
    const square = (x, y) =>
      skin
        .crop({ x: x * scale, y: y * scale, width: 8 * scale, height: 8 * scale })
        .resize({ width: 8, height: 8, quality: 'good' })
        .toBitmap();
    const face = square(8, 8);
    const hat = square(40, 8);
    // Like Minecraft: a hat layer with no see-through pixel at all is an old skin's filler and is left off.
    let seeThrough = false;
    for (let i = 3; i < hat.length; i += 4) if (hat[i] < 128) seeThrough = true;
    if (!seeThrough) hat.fill(0);
    for (let i = 0; i < face.length; i += 4) {
      const a = hat[i + 3] / 255; // BGRA
      for (let c = 0; c < 3; c++) face[i + c] = Math.round(hat[i + c] * a + face[i + c] * (1 - a));
      face[i + 3] = 255;
    }
    fs.mkdirSync(facesDir, { recursive: true });
    fs.writeFileSync(facePath(uuid), nativeImage.createFromBitmap(face, { width: 8, height: 8 }).toPNG());
  } catch (e) {
    // keep whatever face there was
  }
}

/**
 * Fetches every saved account's current skin from Mojang's public profile service (no sign-in needed) and refreshes
 * its face, so faces show and stay up to date without pressing Play.
 */
async function refreshFaces() {
  for (const a of readAccounts().list) {
    try {
      const res = await fetch('https://sessionserver.mojang.com/session/minecraft/profile/' + a.uuid);
      if (!res.ok) continue;
      const prop = ((await res.json()).properties || []).find((p) => p.name === 'textures');
      if (!prop) continue;
      const tex = JSON.parse(Buffer.from(prop.value, 'base64').toString('utf8'));
      if (tex.textures && tex.textures.SKIN) await saveFace(a.uuid, tex.textures.SKIN.url);
    } catch (e) {
      // the old face stays
    }
  }
  send('accounts', state());
}

function faceData(uuid) {
  try {
    return 'data:image/png;base64,' + fs.readFileSync(facePath(uuid)).toString('base64');
  } catch (e) {
    return null;
  }
}

function state() {
  const s = readSettings();
  const d = readAccounts();
  const active = d.list.find((a) => a.uuid === d.active);
  return {
    account: active ? { name: active.name, uuid: active.uuid, face: faceData(active.uuid) } : null,
    accounts: d.list.map((a) => ({ name: a.name, uuid: a.uuid, face: faceData(a.uuid), active: a.uuid === d.active })),
    clientIdSet: !!s.azureClientId,
    azureClientId: s.azureClientId,
    heapGb: s.heapGb,
    renderer: s.renderer,
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
  if (patch.renderer === 'vulkan' || patch.renderer === 'opengl') {
    s.renderer = patch.renderer;
    // Picking Vulkan again clears the note the game leaves after a Vulkan session that did not close properly.
    if (patch.renderer === 'vulkan') {
      fs.rmSync(path.join(root, 'game', 'config', 'clientm-vulkan-crashed.txt'), { force: true });
    }
  }
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
  saveAccount({ name: session.name, uuid: session.uuid, refreshToken: ms.refresh_token }, true);
  await saveFace(session.uuid, session.skinUrl);
  return state();
});

ipcMain.handle('login-cancel', () => {
  loginCancelled = true;
});

ipcMain.handle('switch-account', (e, uuid) => {
  const d = readAccounts();
  if (d.list.find((a) => a.uuid === uuid)) {
    d.active = uuid;
    writeAccounts(d);
  }
  return state();
});

/** Removes the account from this launcher only; the Microsoft account itself is not touched. */
ipcMain.handle('remove-account', (e, uuid) => {
  const d = readAccounts();
  d.list = d.list.filter((a) => a.uuid !== uuid);
  writeAccounts(d);
  fs.rmSync(facePath(uuid), { force: true });
  return state();
});

// Test session: no account, so only singleplayer and servers that do not check accounts will work.
const TEST_SESSION = { name: 'Player', uuid: '00000000000000000000000000000000', accessToken: '0' };

ipcMain.handle('play', async (e, opts) => {
  if (game) throw new Error('The game is already running.');
  const s = readSettings();
  let session = TEST_SESSION;
  let env = {};
  if (!(opts && opts.test)) {
    const d = readAccounts();
    if (!d.list.find((a) => a.uuid === d.active)) throw new Error('Sign in first.');
    send('progress', { text: 'Signing in', fraction: 0 });
    // Every saved account is signed in now, so the game can switch between them on its main menu. Only the
    // active one has to work; the others are left out of the game's list if they fail.
    const sessions = await Promise.all(
      d.list.map(async (saved) => {
        try {
          const ms = await auth.refreshMicrosoft(s.azureClientId, saved.refreshToken);
          const got = await auth.minecraftSession(ms.access_token);
          saveAccount({ name: got.name, uuid: got.uuid, refreshToken: ms.refresh_token || saved.refreshToken }, false);
          await saveFace(got.uuid, got.skinUrl);
          return got;
        } catch (err) {
          if (saved.uuid === d.active) throw err;
          return null;
        }
      }),
    );
    session = sessions.find((x) => x && x.uuid === d.active);
    env = { CLIENTM_ACCOUNTS: writeGameAccounts(sessions.filter(Boolean), session.uuid) };
  }

  const inst = await install(root, manifest, resourcesDir, (text, fraction) => send('progress', { text, fraction }));
  // The game reads its renderer from this file at start-up (Vulkan when it is missing).
  fs.mkdirSync(path.join(inst.gameDir, 'config'), { recursive: true });
  fs.writeFileSync(path.join(inst.gameDir, 'config', 'clientm-renderer.txt'), s.renderer + '\n');
  send('progress', { text: 'Starting Minecraft', fraction: 1 });
  game = launch(inst, session, { heapGb: s.heapGb, jvmFlags: manifest.jvmFlags, env });
  game.on('exit', (code) => {
    game = null;
    if (env.CLIENTM_ACCOUNTS) fs.rmSync(env.CLIENTM_ACCOUNTS, { force: true });
    send('game-exit', { code });
  });
  game.on('error', (err) => {
    game = null;
    send('game-exit', { code: -1, error: err.message });
  });
  return state();
});

/**
 * Hands the signed-in accounts to the game for its account switcher: names, ids, Minecraft session tokens (valid for
 * about a day) and face pictures. Microsoft refresh tokens never leave the launcher. The game reads this file once
 * at start and deletes it; the launcher deletes it too when the game exits.
 */
function writeGameAccounts(sessions, activeUuid) {
  const file = path.join(userData, 'game-accounts-' + process.pid + '-' + Date.now() + '.json');
  const list = sessions.map((x) => ({
    name: x.name,
    uuid: x.uuid,
    token: x.accessToken,
    face: fs.existsSync(facePath(x.uuid)) ? facePath(x.uuid) : null,
  }));
  fs.writeFileSync(file, JSON.stringify({ active: activeUuid, list }));
  return file;
}

ipcMain.handle('open-folder', () => {
  fs.mkdirSync(path.join(root, 'game'), { recursive: true });
  return shell.openPath(path.join(root, 'game'));
});

// Only Microsoft's own sign-in pages may be opened from the window.
ipcMain.handle('open-url', (e, url) => {
  const host = new URL(url).hostname;
  if (/(^|\.)microsoft\.com$|(^|\.)microsoftonline\.com$|(^|\.)live\.com$/.test(host)) return shell.openExternal(url);
});

// ---------------------------------------------------------------- updates

// New versions come from the GitHub releases of meloniexyz/ClientM. The installer is downloaded in the
// background; the window then offers a restart, and it is also applied the next time the launcher closes.
let updateReady = null;

function startUpdater() {
  if (!app.isPackaged) return;
  const logFile = path.join(userData, 'updater.log');
  const write = (level) => (msg) => {
    try {
      fs.appendFileSync(logFile, `${new Date().toISOString()} ${level} ${msg && msg.stack ? msg.stack : msg}
`);
    } catch (e) {
      // logging must never break the launcher
    }
  };
  autoUpdater.logger = { info: write('info'), warn: write('warn'), error: write('error'), debug: () => {} };
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('update-available', (info) => send('update', { stage: 'downloading', version: info.version }));
  autoUpdater.on('update-downloaded', (info) => {
    updateReady = info.version;
    send('update', { stage: 'ready', version: info.version });
  });
  autoUpdater.on('error', () => {}); // already written to updater.log; never bother the user with it
  autoUpdater.checkForUpdates().catch(() => {});
}

ipcMain.handle('update-state', () => (updateReady ? { stage: 'ready', version: updateReady } : null));

ipcMain.handle('update-install', () => {
  if (updateReady && !game) autoUpdater.quitAndInstall(true, true);
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
  win.webContents.once('did-finish-load', startUpdater);
  win.webContents.once('did-finish-load', refreshFaces);

  // Developer aid: CLIENTM_LAUNCHER_SHOT=<file.png> saves a picture of the window and quits.
  const shot = process.env.CLIENTM_LAUNCHER_SHOT;
  if (shot) {
    win.webContents.once('did-finish-load', () => {
      setTimeout(async () => {
        // CLIENTM_LAUNCHER_CLICK=<element id> clicks one button first, to picture a dialog.
        if (process.env.CLIENTM_LAUNCHER_CLICK) {
          const r = await win.webContents.executeJavaScript(
            'document.getElementById(' + JSON.stringify(process.env.CLIENTM_LAUNCHER_CLICK) + ').click(); document.querySelector(".overlay.show") ? "open" : "closed"',
          );
          console.log('click result', r);
          await new Promise((done) => setTimeout(done, 300));
        }
        fs.writeFileSync(shot, (await win.webContents.capturePage()).toPNG());
        app.quit();
      }, 1200);
    });
  }
});

app.on('window-all-closed', () => app.quit());
