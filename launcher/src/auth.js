// Microsoft sign-in for Minecraft, using the "device code" flow: the launcher shows a short code, the user types
// it at microsoft.com/link in their own browser, and the launcher never sees the password.
//
// Chain: Microsoft account -> Xbox Live -> XSTS -> Minecraft services -> game profile.
// Requires an Azure app (client ID) that Mojang has approved for the Minecraft API.
const TENANT = 'https://login.microsoftonline.com/consumers/oauth2/v2.0';
const SCOPE = 'XboxLive.signin offline_access';

async function postForm(url, params) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  });
  return { ok: res.ok, status: res.status, body: await res.json().catch(() => ({})) };
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  });
  return { ok: res.ok, status: res.status, body: await res.json().catch(() => ({})) };
}

/** Step 1: ask Microsoft for a code to show the user. */
async function startDeviceCode(clientId) {
  const r = await postForm(TENANT + '/devicecode', { client_id: clientId, scope: SCOPE });
  if (!r.ok) throw new Error('Microsoft rejected the sign-in request: ' + (r.body.error_description || r.status));
  return r.body; // { user_code, device_code, verification_uri, interval, expires_in }
}

/** Step 2: wait until the user has entered the code. */
async function waitForDeviceCode(clientId, code, isCancelled) {
  let interval = (code.interval || 5) * 1000;
  const deadline = Date.now() + (code.expires_in || 900) * 1000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, interval));
    if (isCancelled && isCancelled()) throw new Error('Sign-in cancelled');
    const r = await postForm(TENANT + '/token', {
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      client_id: clientId,
      device_code: code.device_code,
    });
    if (r.ok) return r.body; // { access_token, refresh_token }
    if (r.body.error === 'authorization_pending') continue;
    if (r.body.error === 'slow_down') {
      interval += 5000;
      continue;
    }
    throw new Error('Sign-in failed: ' + (r.body.error_description || r.body.error || r.status));
  }
  throw new Error('The sign-in code expired');
}

async function refreshMicrosoft(clientId, refreshToken) {
  const r = await postForm(TENANT + '/token', {
    grant_type: 'refresh_token',
    client_id: clientId,
    refresh_token: refreshToken,
    scope: SCOPE,
  });
  if (!r.ok) throw new Error('Your sign-in has expired, please sign in again');
  return r.body;
}

const XSTS_ERRORS = {
  2148916233: 'This Microsoft account has no Xbox profile. Create one at xbox.com first.',
  2148916235: 'Xbox Live is not available in this account\'s country.',
  2148916236: 'This account needs adult verification on the Xbox website.',
  2148916237: 'This account needs adult verification on the Xbox website.',
  2148916238: 'This is a child account and must be added to a family by an adult.',
};

/** Step 3: turn a Microsoft token into a Minecraft session. */
async function minecraftSession(msAccessToken) {
  const xbl = await postJson('https://user.auth.xboxlive.com/user/authenticate', {
    Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: 'd=' + msAccessToken },
    RelyingParty: 'http://auth.xboxlive.com',
    TokenType: 'JWT',
  });
  if (!xbl.ok) throw new Error('Xbox Live sign-in failed (' + xbl.status + ')');

  const xsts = await postJson('https://xsts.auth.xboxlive.com/xsts/authorize', {
    Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.body.Token] },
    RelyingParty: 'rp://api.minecraftservices.com/',
    TokenType: 'JWT',
  });
  if (!xsts.ok) throw new Error(XSTS_ERRORS[xsts.body.XErr] || 'Xbox authorisation failed (' + xsts.status + ')');

  const uhs = xsts.body.DisplayClaims.xui[0].uhs;
  const mc = await postJson('https://api.minecraftservices.com/authentication/login_with_xbox', {
    identityToken: `XBL3.0 x=${uhs};${xsts.body.Token}`,
  });
  if (!mc.ok) {
    throw new Error(
      mc.status === 403
        ? 'Minecraft refused this launcher. Its Azure app has not been approved by Mojang yet.'
        : 'Minecraft sign-in failed (' + mc.status + ')',
    );
  }

  const res = await fetch('https://api.minecraftservices.com/minecraft/profile', {
    headers: { Authorization: 'Bearer ' + mc.body.access_token },
  });
  if (res.status === 404) throw new Error('This account does not own Minecraft: Java Edition.');
  if (!res.ok) throw new Error('Could not read the Minecraft profile (' + res.status + ')');
  const profile = await res.json();
  const skin = (profile.skins || []).find((k) => k.state === 'ACTIVE') || (profile.skins || [])[0];
  return { name: profile.name, uuid: profile.id, accessToken: mc.body.access_token, skinUrl: skin ? skin.url : null };
}

module.exports = { startDeviceCode, waitForDeviceCode, refreshMicrosoft, minecraftSession };
