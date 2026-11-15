const $ = (id) => document.getElementById(id);
const api = window.launcher;
let st = null;
let busy = false;
let loginUrl = null;

function status(text, isError) {
  $('status').textContent = text || '';
  $('status').classList.toggle('err', !!isError);
}

function render() {
  $('accName').textContent = st.account ? st.account.name : 'Not signed in';
  $('accBtn').textContent = st.account ? 'Sign out' : 'Sign in with Microsoft';
  $('ver').textContent = 'Launcher ' + st.version;
  $('playBtn').disabled = busy || st.running || !st.account;
  $('playBtn').textContent = st.running ? 'Playing' : 'Play';
  document.querySelectorAll('.chip').forEach((c) => c.classList.toggle('on', +c.dataset.gb === st.heapGb));
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

$('accBtn').onclick = async () => {
  if (st.account) {
    st = await api.logout();
    return render();
  }
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
};
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

document.querySelectorAll('.chip').forEach((c) => {
  c.onclick = async () => {
    st = await api.settings({ heapGb: +c.dataset.gb });
    render();
  };
});

$('folderBtn').onclick = () => api.openFolder();

$('playBtn').onclick = async () => {
  busy = true;
  status('');
  render();
  try {
    st = await api.play();
    status('Minecraft is running.');
  } catch (e) {
    status(message(e), true);
    $('bar').style.width = '0';
  }
  busy = false;
  render();
};
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

refresh();
