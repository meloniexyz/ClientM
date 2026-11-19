const $ = (id) => document.getElementById(id);
const api = window.launcher;
let st = null;
let busy = false;
let loginUrl = null;

function status(text, isError) {
  $('status').textContent = text || '';
  $('status').classList.toggle('err', !!isError);
}

// A grey 8x8 head for accounts without a saved face.
const NO_FACE =
  'data:image/svg+xml;utf8,' +
  encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="#2a2a33"/></svg>');

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text) e.textContent = text;
  return e;
}

function renderAccounts() {
  const list = $('accList');
  list.textContent = '';
  for (const a of st.accounts || []) {
    const row = el('div', 'accrow' + (a.active ? ' on' : ''));
    const img = el('img', 'face sm');
    img.src = a.face || NO_FACE;
    row.append(img, el('b', '', a.name));
    if (a.active) row.append(el('span', 'tagon', 'IN USE'));
    else {
      const use = el('button', 'use', 'Use');
      use.onclick = async () => {
        st = await api.switchAccount(a.uuid);
        render();
      };
      row.append(use);
    }
    const x = el('button', 'x', '\u00d7');
    x.title = 'Remove from this launcher';
    x.onclick = async () => {
      st = await api.removeAccount(a.uuid);
      render();
    };
    row.append(x);
    list.append(row);
  }
}

function render() {
  $('accName').textContent = st.account ? st.account.name : 'Not signed in';
  $('accFace').src = (st.account && st.account.face) || NO_FACE;
  $('accBtn').textContent = !st.account
    ? 'Sign in with Microsoft'
    : st.accounts.length > 1
      ? 'Switch account (' + st.accounts.length + ')'
      : 'Accounts';
  renderAccounts();
  $('ver').textContent = 'Launcher ' + st.version;
  $('playBtn').disabled = busy || st.running || !st.account;
  $('playBtn').textContent = st.running ? 'Playing' : 'Play';
  $('testBtn').disabled = busy || st.running;
  document.querySelectorAll('.chip[data-gb]').forEach((c) => c.classList.toggle('on', +c.dataset.gb === st.heapGb));
  document
    .querySelectorAll('.chip[data-renderer]')
    .forEach((c) => c.classList.toggle('on', c.dataset.renderer === st.renderer));
  $('rendererFact').textContent = st.renderer === 'opengl' ? 'Angelica on OpenGL' : 'ClientM Vulkan (fastest)';
  if (!busy && !st.running && !$('status').classList.contains('err')) {
    status(!st.clientIdSet ? 'Add your Azure client ID in Settings to enable sign-in.' : !st.account ? 'Sign in to play.' : '');
  }
}

async function refresh() {
  st = await api.state();
  render();
}

function message(e) {
  return String(e.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
}

$('accBtn').onclick = () => (st.account ? $('accDlg').classList.add('show') : signIn());
$('accClose').onclick = () => $('accDlg').classList.remove('show');
$('accAdd').onclick = () => {
  $('accDlg').classList.remove('show');
  signIn();
};

async function signIn() {
  if (!st.clientIdSet) return $('settingsBtn').onclick();
  $('loginCode').textContent = '········';
  loginUrl = null;
  $('loginDlg').classList.add('show');
  try {
    st = await api.login();
    status('');
  } catch (e) {
    status(message(e), true);
  }
  $('loginDlg').classList.remove('show');
  render();
}
$('loginOpen').onclick = () => loginUrl && api.openUrl(loginUrl);
$('loginCancel').onclick = () => {
  api.cancelLogin();
  $('loginDlg').classList.remove('show');
};
api.on('login-code', (d) => {
  $('loginCode').textContent = d.code;
  loginUrl = d.url;
});

$('settingsBtn').onclick = () => {
  $('clientId').value = st.azureClientId || '';
  $('settingsDlg').classList.add('show');
};
$('settingsCancel').onclick = () => $('settingsDlg').classList.remove('show');
$('settingsSave').onclick = async () => {
  st = await api.settings({ azureClientId: $('clientId').value });
  $('settingsDlg').classList.remove('show');
  status('');
  render();
};

document.querySelectorAll('.chip[data-gb]').forEach((c) => {
  c.onclick = async () => {
    st = await api.settings({ heapGb: +c.dataset.gb });
    render();
  };
});

document.querySelectorAll('.chip[data-renderer]').forEach((c) => {
  c.onclick = async () => {
    st = await api.settings({ renderer: c.dataset.renderer });
    render();
  };
});

$('folderBtn').onclick = () => api.openFolder();

async function play(test) {
  busy = true;
  status('');
  render();
  try {
    st = await api.play({ test });
    status('Minecraft is running.');
  } catch (e) {
    status(message(e), true);
    $('bar').style.width = '0';
  }
  busy = false;
  render();
}
$('playBtn').onclick = () => play(false);
$('testBtn').onclick = () => play(true);
api.on('progress', (d) => {
  status(d.text);
  $('bar').style.width = Math.round(d.fraction * 100) + '%';
});
api.on('game-exit', async (d) => {
  $('bar').style.width = '0';
  await refresh();
  status(d.error ? 'Could not start Java: ' + d.error : d.code ? 'Minecraft closed with an error (code ' + d.code + '). See launcher-game.log in the game folder.' : '', !!(d.error || d.code));
});

function showUpdate(u) {
  if (!u) return;
  const b = $('updateBtn');
  b.hidden = false;
  b.disabled = u.stage !== 'ready';
  b.textContent = u.stage === 'ready' ? 'Restart to update to ' + u.version : 'Downloading update ' + u.version + '…';
}
api.on('update', showUpdate);
api.updateState().then(showUpdate);
$('updateBtn').onclick = () => {
  if (st.running) return status('Close Minecraft first, then restart to update.', true);
  api.installUpdate();
};

api.on('accounts', (state) => {
  st = state;
  render();
});

refresh();
