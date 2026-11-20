// Builds an importable Prism Launcher / MultiMC instance of ClientM: dist/ClientM-Prism-<version>.zip.
// It is lwjgl3ify's own instance pack (Minecraft 1.7.10 + Forge + LWJGL 3) with the ClientM mods, the tuned
// Java flags and the server-compatibility setting added. Prism handles the Microsoft sign-in.
//
//   node scripts/build-prism-pack.js
//   CLIENTM_PRISM_JAVA="C:/path/to/javaw.exe" node scripts/build-prism-pack.js   (pin a Java 21 for this PC)
//
// Run `gradlew build` and `npm run copy-mod` first so resources/mods/ClientM.jar is current.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const AdmZip = require('adm-zip');
const manifest = require('../manifest.json');
const { version } = require('../package.json');

const cache = path.join(__dirname, '..', 'dist', 'prism-cache');
const out = path.join(__dirname, '..', 'dist', `ClientM-Prism-${version}.zip`);

async function download(item, name) {
  const file = path.join(cache, name);
  const ok = () =>
    fs.existsSync(file) && crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex') === item.sha256;
  if (!ok()) {
    console.log('Downloading', name);
    const res = await fetch(item.url);
    if (!res.ok) throw new Error(name + ': HTTP ' + res.status);
    fs.writeFileSync(file, Buffer.from(await res.arrayBuffer()));
    if (!ok()) throw new Error(name + ': wrong SHA-256');
  }
  return file;
}

(async () => {
  fs.mkdirSync(cache, { recursive: true });
  const zip = new AdmZip(await download(manifest.prismPack, 'lwjgl3ify-multimc.zip'));

  for (const m of manifest.mods) zip.addLocalFile(await download(m, m.file), '.minecraft/mods');
  zip.addLocalFile(path.join(__dirname, '..', 'resources', 'mods', 'ClientM.jar'), '.minecraft/mods');

  // LWJGL's Vulkan bindings for ClientM's Vulkan renderer: lwjgl3ify's LWJGL component does not list them.
  const lwjglPatch = JSON.parse(zip.readAsText('patches/org.lwjgl3.json'));
  for (const extra of manifest.extraLibraries || []) {
    const name = 'org.lwjgl:' + path.basename(extra.path).replace(/-(\d[\d.]*)\.jar$/, ':$1');
    if (lwjglPatch.libraries.some((l) => l.name === name)) continue;
    lwjglPatch.libraries.push({
      downloads: { artifact: { sha1: extra.sha1, size: extra.size, url: extra.url } },
      name,
    });
  }
  zip.updateFile('patches/org.lwjgl3.json', Buffer.from(JSON.stringify(lwjglPatch, null, 4)));

  // Same reason as fixServerCompatibility() in src/install.js: this option breaks ordinary servers.
  zip.addFile(
    '.minecraft/config/hodgepodge.cfg',
    Buffer.from(
      [
        '# Configuration file',
        '',
        'speedups {',
        '    B:directTileEntityArraySerialization=false',
        '}',
        '',
        // ClientM's Chat module does transparent and compact chat itself (see HODGEPODGE_FORCED in src/install.js)
        'tweaks {',
        '    B:transparentChat=false',
        '    B:compactChat=false',
        '}',
        '',
      ].join('\n'),
    ),
  );

  // Same reason as enableCustomSkies() in src/install.js: resource pack skies need this on before start.
  zip.addFile(
    '.minecraft/config/mcpatcherforge.cfg',
    Buffer.from(['# Configuration file', '', 'better_skies {', '    B:enabled=true', '    B:skybox=true', '}', ''].join('\n')),
  );

  const mb = manifest.defaultHeapGb * 1024;
  const java = process.env.CLIENTM_PRISM_JAVA;
  const cfg = [
    '[General]',
    'ConfigVersion=1.2',
    'InstanceType=OneSix',
    'name=ClientM',
    'iconKey=default',
    `notes=ClientM ${version}. Minecraft 1.7.10 with Forge and LWJGL 3. Needs Java 21: pick one under Settings > Java.`,
    // Minecraft 1.7.10 officially wants Java 8; this pack runs on 17 or newer, so skip that check.
    'IgnoreJavaCompatibility=true',
    'OverrideMemory=true',
    `MinMemAlloc=${mb}`,
    `MaxMemAlloc=${mb}`,
    'OverrideJavaArgs=true',
    'JvmArgs=' + manifest.jvmFlags.join(' '),
  ];
  if (java) cfg.push('OverrideJavaLocation=true', 'JavaPath=' + java.replace(/\\/g, '/'));
  zip.addFile('instance.cfg', Buffer.from(cfg.join('\n') + '\n'));

  zip.writeZip(out);
  console.log('Wrote', out, Math.round(fs.statSync(out).size / 1e6) + ' MB');
})().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
