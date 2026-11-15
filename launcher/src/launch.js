// Builds the Java command line from the version file and starts the game.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { allowed, VERSION_ID } = require('./install');

function flatten(args) {
  const out = [];
  for (const a of args) {
    if (typeof a === 'string') out.push(a);
    else if (allowed(a.rules)) out.push(...[].concat(a.value));
  }
  return out;
}

/**
 * @param inst     result of install()
 * @param account  { name, uuid, accessToken }
 * @param opts     { heapGb, jvmFlags, env }
 * @returns the child process
 */
function launch(inst, account, opts) {
  const vars = {
    natives_directory: inst.nativesDir,
    classpath: inst.classpath.join(path.delimiter),
    launcher_name: 'ClientM',
    launcher_version: '0.1.0',
    library_directory: path.dirname(inst.nativesDir) + path.sep + 'libraries',
    classpath_separator: path.delimiter,
    auth_player_name: account.name,
    version_name: VERSION_ID,
    game_directory: inst.gameDir,
    assets_root: inst.assetsDir,
    assets_index_name: inst.version.assetIndex.id,
    auth_uuid: account.uuid,
    auth_access_token: account.accessToken,
    user_properties: '{}',
    user_type: 'msa',
    version_type: 'release',
  };
  const fill = (s) => s.replace(/\$\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));

  const heap = opts.heapGb + 'G';
  const args = [
    '-Xms' + heap,
    '-Xmx' + heap,
    ...opts.jvmFlags,
    ...flatten(inst.version.arguments.jvm).map(fill),
  ];
  if (inst.logConfig && inst.logArgument) args.push(inst.logArgument.replace('${path}', inst.logConfig));
  args.push(inst.version.mainClass, ...flatten(inst.version.arguments.game).map(fill));

  fs.mkdirSync(inst.gameDir, { recursive: true });
  const log = fs.openSync(path.join(inst.gameDir, 'launcher-game.log'), 'w');
  // java.exe (not javaw.exe) so the game's output goes to the log file.
  const java = inst.java.replace(/javaw\.exe$/i, 'java.exe');
  return spawn(java, args, {
    cwd: inst.gameDir,
    stdio: ['ignore', log, log],
    windowsHide: true,
    env: { ...process.env, ...(opts.env || {}) },
  });
}

module.exports = { launch };
