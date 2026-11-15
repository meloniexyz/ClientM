# ClientM

A performance-focused PvP client for Minecraft 1.7.10, built as a Forge mod on top of
[Angelica](https://github.com/GTNewHorizons/Angelica), with its own launcher.

## What is in here

| Folder | What it is |
|---|---|
| `src/` | The ClientM mod: HUD mods (FPS, CPS, coordinates, keystrokes, potion and armour status), toggle sprint, enchant glint and hit colour customisers, video presets from Potato to Fancy, and the Right Shift menu |
| `launcher/` | The ClientM launcher (Electron). It downloads Minecraft 1.7.10, Forge, Java 21, Angelica and the other mods, then starts the game with the best-measured settings |
| `mockups/` | Menu design mockups |

## Default setup

The launcher and the `runClient21` Gradle task both start the game on Java 21 with LWJGL 3 (through lwjgl3ify), on
OpenGL, with a fixed 3 GB heap and tuned G1 flags. In benchmarks on a Ryzen 5 3600 / GTX 1660 SUPER this matched
Java 8's average FPS and gave clearly higher 1% lows. Angelica's experimental Vulkan renderer measured slower and is
left off.

## Building

Mod (needs a JDK; Gradle fetches the rest):

```
./gradlew build
./gradlew runClient21
```

Launcher and installer (needs Node.js; build the mod first so its jar can be bundled):

```
cd launcher
npm install
npm run dist
```

The installer is written to `launcher/dist/ClientM-Setup-<version>.exe`.

## Prism Launcher pack

Each release also has `ClientM-Prism-<version>.zip`, a ready-made instance for Prism Launcher (or MultiMC), which
handles the Microsoft sign-in itself. In Prism: Add Instance, Import, pick the zip. Then choose a Java 21 for the
instance under Settings > Java. The pack does not update itself; import the new zip, or replace `ClientM.jar` in the
instance's mods folder.

Build it with `npm run prism` in `launcher/` (output in `launcher/dist/`).

## Signing in

The launcher signs in with Microsoft through the device-code flow, so the password is only ever typed on Microsoft's
own page. It needs an Azure application (client) ID that has been approved by Mojang for the Minecraft API; enter it
under Settings in the launcher.

## Credits

The mod project is based on the GTNewHorizons ExampleMod1.7.10 template (MIT, see `LICENSE`). ClientM depends at run
time on Angelica, Hodgepodge, GTNHLib, GTNHExtLib, UniMixins and lwjgl3ify, which the launcher downloads from their
own GitHub releases. Minecraft itself is downloaded from Mojang's servers and is not included.
