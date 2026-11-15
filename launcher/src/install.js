// Downloads and lays out everything the game needs: Minecraft 1.7.10 from Mojang, the Forge + LWJGL3 libraries
// listed in lwjgl3ify's version file, a Java 21 runtime, and the mods. Safe to run on every launch: files that
// are already there with the right size are skipped.
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const { pipeline } = require('stream/promises');
const { Readable } = require('stream');
const AdmZip = require('adm-zip');

const VERSION_ID = 'clientm';

async function hashFile(file, algo) {
  const h = crypto.createHash(algo);
  await pipeline(fs.createReadStream(file), h);
  return h.digest('hex');
}

/** Downloads url to dest unless dest already exists with the expected size. Verifies size and hash. */
async function fetchTo(url, dest, check = {}) {
  try {
    const st = await fsp.stat(dest);
    if (check.size == null || st.size === check.size) return false;
  } catch (e) {
    // not there yet
  }
  await fsp.mkdir(path.dirname(dest), { recursive: true });
  const tmp = dest + '.part';
  let lastError;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
      const st = await fsp.stat(tmp);
      if (check.size != null && st.size !== check.size) throw new Error('wrong size');
      if (check.sha1 && (await hashFile(tmp, 'sha1')) !== check.sha1) throw new Error('wrong SHA-1');
      if (check.sha256 && (await hashFile(tmp, 'sha256')) !== check.sha256) throw new Error('wrong SHA-256');
      await fsp.rename(tmp, dest);
      return true;
    } catch (e) {
      lastError = e;
      await fsp.rm(tmp, { force: true });
    }
  }
  throw new Error('Could not download ' + path.basename(dest) + ': ' + lastError.message);
}

async function pool(items, limit, worker) {
  let next = 0;
  const run = async () => {
    while (next < items.length) await worker(items[next++]);
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
}

/** Mojang-style rules, evaluated for 64-bit Windows. */
function allowed(rules) {
  if (!rules) return true;
  let allow = false;
  for (const r of rules) {
    if (r.features) continue;
    const os = r.os;
    const match = !os || ((!os.name || os.name === 'windows') && (!os.arch || os.arch === 'x64' || os.arch === 'x86_64'));
    if (match) allow = r.action === 'allow';
  }
  return allow;
}

function mavenPath(name) {
  const [group, artifact, version, classifier] = name.split(':');
  const file = artifact + '-' + version + (classifier ? '-' + classifier : '') + '.jar';
  return [...group.split('.'), artifact, version, file].join('/');
}

async function installJava(root, major, progress) {
  const dir = path.join(root, 'runtime', 'java' + major);
  const marker = path.join(dir, '.installed');
  const javaw = async () => {
    for (const d of await fsp.readdir(dir)) {
      const exe = path.join(dir, d, 'bin', 'javaw.exe');
      if (fs.existsSync(exe)) return exe;
    }
    return null;
  };
  if (fs.existsSync(marker)) {
    const exe = await javaw();
    if (exe) return exe;
  }
  progress('Downloading Java ' + major, 0);
  const api = `https://api.adoptium.net/v3/assets/latest/${major}/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse`;
  const res = await fetch(api);
  if (!res.ok) throw new Error('Could not look up Java ' + major + ' (HTTP ' + res.status + ')');
  const pkg = (await res.json())[0].binary.package;
  const zip = path.join(root, 'runtime', pkg.name);
  await fsp.rm(dir, { recursive: true, force: true });
  await fetchTo(pkg.link, zip, { size: pkg.size, sha256: pkg.checksum });
  progress('Unpacking Java ' + major, 0);
  new AdmZip(zip).extractAllTo(dir, true);
  await fsp.rm(zip, { force: true });
  await fsp.writeFile(marker, pkg.name);
  const exe = await javaw();
  if (!exe) throw new Error('Java was unpacked but javaw.exe was not found');
  return exe;
}

async function installMods(root, manifest, resourcesDir, progress) {
  const mods = path.join(root, 'game', 'mods');
  await fsp.mkdir(mods, { recursive: true });
  // Remove mod files an older launcher version put there; anything the user added is left alone.
  const listFile = path.join(mods, '.clientm-managed.json');
  const wanted = [...manifest.mods.map((m) => m.file), 'ClientM.jar'];
  let previous = [];
  try {
    previous = JSON.parse(await fsp.readFile(listFile, 'utf8'));
  } catch (e) {
    // first run
  }
  for (const old of previous) if (!wanted.includes(old)) await fsp.rm(path.join(mods, old), { force: true });

  let done = 0;
  for (const m of manifest.mods) {
    progress('Downloading mods', done++ / manifest.mods.length);
    await fetchTo(m.url, path.join(mods, m.file), { size: m.size, sha256: m.sha256 });
  }
  await fsp.copyFile(path.join(resourcesDir, 'mods', 'ClientM.jar'), path.join(mods, 'ClientM.jar'));
  await fsp.writeFile(listFile, JSON.stringify(wanted));
}

/**
 * Hodgepodge's "direct tile entity array serialization" changes the format of one network packet. It only works
 * when the server runs Hodgepodge too; on any other server the client misreads the packet and is disconnected.
 * ClientM is for playing on ordinary servers, so it is always switched off here.
 */
async function fixServerCompatibility(gameDir) {
  const file = path.join(gameDir, 'config', 'hodgepodge.cfg');
  const key = 'B:directTileEntityArraySerialization=';
  let text = null;
  try {
    text = await fsp.readFile(file, 'utf8');
  } catch (e) {
    // first launch: Hodgepodge fills in every other setting with its default when it loads this file
  }
  if (text === null) {
    await fsp.mkdir(path.dirname(file), { recursive: true });
    await fsp.writeFile(file, ['# Configuration file', '', 'speedups {', '    ' + key + 'false', '}', ''].join('\n'));
  } else if (text.includes(key + 'true')) {
    await fsp.writeFile(file, text.replace(key + 'true', key + 'false'));
  }
}

/**
 * @param root          data folder (…\AppData\Roaming\ClientM\minecraft)
 * @param manifest      launcher/manifest.json
 * @param resourcesDir  folder holding mods/ClientM.jar
 * @param progress      (text, fraction 0..1) => void
 */
async function install(root, manifest, resourcesDir, progress) {
  const versionDir = path.join(root, 'versions', VERSION_ID);
  const versionFile = path.join(versionDir, 'version.json');
  progress('Checking game files', 0);
  await fetchTo(manifest.versionJson.url, versionFile, manifest.versionJson);
  const version = JSON.parse(await fsp.readFile(versionFile, 'utf8'));

  const clientJar = path.join(versionDir, 'client.jar');
  const c = version.downloads.client;
  await fetchTo(c.url, clientJar, { size: c.size, sha1: c.sha1 });

  // Libraries (and the two old-style native bundles, which are unpacked)
  const nativesDir = path.join(root, 'natives');
  const classpath = [];
  const jobs = [];
  for (const lib of version.libraries) {
    if (!allowed(lib.rules)) continue;
    const d = lib.downloads || {};
    if (d.artifact) {
      const dest = path.join(root, 'libraries', d.artifact.path || mavenPath(lib.name));
      classpath.push(dest);
      jobs.push({ url: d.artifact.url, dest, check: d.artifact });
    }
    const key = lib.natives && lib.natives.windows && lib.natives.windows.replace('${arch}', '64');
    if (key && d.classifiers && d.classifiers[key]) {
      const n = d.classifiers[key];
      jobs.push({ url: n.url, dest: path.join(root, 'libraries', n.path), check: n, unpack: true });
    }
  }
  let done = 0;
  await pool(jobs, 8, async (j) => {
    const fresh = await fetchTo(j.url, j.dest, { size: j.check.size, sha1: j.check.sha1 });
    if (j.unpack && (fresh || !fs.existsSync(nativesDir))) {
      const zip = new AdmZip(j.dest);
      for (const e of zip.getEntries()) {
        if (!e.isDirectory && !e.entryName.startsWith('META-INF')) zip.extractEntryTo(e, nativesDir, false, true);
      }
    }
    progress('Downloading libraries', ++done / jobs.length);
  });
  await fsp.mkdir(nativesDir, { recursive: true });
  classpath.push(clientJar);

  // Assets (sounds, language files)
  const assetsDir = path.join(root, 'assets');
  const ai = version.assetIndex;
  const indexFile = path.join(assetsDir, 'indexes', ai.id + '.json');
  await fetchTo(ai.url, indexFile, { size: ai.size, sha1: ai.sha1 });
  const objects = Object.values(JSON.parse(await fsp.readFile(indexFile, 'utf8')).objects);
  done = 0;
  await pool(objects, 12, async (o) => {
    const sub = o.hash.slice(0, 2) + '/' + o.hash;
    await fetchTo('https://resources.download.minecraft.net/' + sub, path.join(assetsDir, 'objects', sub), { size: o.size });
    if (++done % 20 === 0 || done === objects.length) progress('Downloading sounds and assets', done / objects.length);
  });

  let logConfig = null;
  const lf = version.logging && version.logging.client;
  if (lf) {
    logConfig = path.join(assetsDir, 'log_configs', lf.file.id);
    await fetchTo(lf.file.url, logConfig, { size: lf.file.size, sha1: lf.file.sha1 });
  }

  const java = await installJava(root, manifest.javaMajor, progress);
  await installMods(root, manifest, resourcesDir, progress);
  await fixServerCompatibility(path.join(root, 'game'));
  progress('Ready', 1);

  return { version, classpath, nativesDir, assetsDir, java, logConfig, logArgument: lf && lf.argument, gameDir: path.join(root, 'game') };
}

module.exports = { install, allowed, VERSION_ID };
