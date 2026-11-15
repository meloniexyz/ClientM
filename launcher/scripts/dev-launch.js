// Developer test, not part of the launcher: installs everything, then starts the game with a placeholder
// session (the same kind the Gradle dev launch uses) so the download and launch path can be checked without
// signing in. Usage: node scripts/dev-launch.js [extra env like CLIENTM_TOUR=bench]
const path = require('path');
const { install } = require('../src/install');
const { launch } = require('../src/launch');
const manifest = require('../manifest.json');

(async () => {
  const root = path.join(process.env.APPDATA, 'ClientM', 'minecraft');
  let last = '';
  const inst = await install(root, manifest, path.join(__dirname, '..', 'resources'), (text, f) => {
    const line = text + ' ' + Math.round(f * 100) + '%';
    if (line !== last && (f === 0 || f === 1 || Math.round(f * 100) % 25 === 0)) console.log((last = line));
  });
  console.log('java:', inst.java, '| classpath entries:', inst.classpath.length);
  const env = {};
  for (const a of process.argv.slice(2)) env[a.split('=')[0]] = a.split('=').slice(1).join('=');
  const child = launch(
    inst,
    { name: 'Dev', uuid: '00000000000000000000000000000000', accessToken: '0' },
    { heapGb: manifest.defaultHeapGb, jvmFlags: manifest.jvmFlags, env },
  );
  child.on('exit', (code) => console.log('game exited with code', code));
})().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
