// Copies the newest built ClientM mod jar (not the -dev or -sources one) into resources/mods/ClientM.jar,
// so the installer ships it. Run `gradlew build` in the parent folder first.
const fs = require('fs');
const path = require('path');

const libs = path.join(__dirname, '..', '..', 'build', 'libs');
const jars = fs
  .readdirSync(libs)
  .filter((f) => /^clientm-.*\.jar$/.test(f) && !/-(dev|sources)\.jar$/.test(f))
  .map((f) => ({ f, t: fs.statSync(path.join(libs, f)).mtimeMs }))
  .sort((a, b) => b.t - a.t);
if (!jars.length) throw new Error('No ClientM jar in ' + libs + ' - run gradlew build first');
const out = path.join(__dirname, '..', 'resources', 'mods');
fs.mkdirSync(out, { recursive: true });
fs.copyFileSync(path.join(libs, jars[0].f), path.join(out, 'ClientM.jar'));
console.log('Bundled', jars[0].f);
