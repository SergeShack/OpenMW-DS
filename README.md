# OpenMW-DS Duo Lite

A fork of **[OpenMW-DS](https://github.com/Josh-Daniels/OpenMW-DS)** tuned for the **Retroid Pocket Duo Lite**, based on
OpenMW-DS v1.4.0. OpenMW-DS plays Morrowind (through OpenMW) on the top screen and puts the character sheet, inventory,
magic, journal, map and many of the game's menus on the bottom screen.

All credit for OpenMW-DS goes to its author.

This fork keeps all of that and works on making it run smoothly on the Duo Lite, plus a few fixes and controls of its own.

---
## Two apps

| App | For | Releases |
|---|---|---|
| **OpenMW-DS Duo Lite** | Retroid Pocket Duo Lite. Other dual-screen devices can be chosen in Settings > Device, as in OpenMW-DS. | [Latest release](https://github.com/SergeShack/OpenMW-DS/releases/latest), tags `vX.Y.Z` |
| **OpenMW-DS Phone** | A phone on its own, or a phone with a TV or monitor as the game screen. See the [phone-ds branch](https://github.com/SergeShack/OpenMW-DS/tree/phone-ds). | [Phone releases](https://github.com/SergeShack/OpenMW-DS/releases?q=phone-v&expanded=true), tags `phone-vX.Y.Z` |

Each is its own app, with its own saves and settings folder (`OpenMW-DS-DuoLite`, `OpenMW-DS-Phone`). They install next
to each other and next to the original OpenMW-DS, and do not touch each other's files.

---
## Install

1. Download `openmw-ds-duolite-X.Y.Z.apk` from the [latest release](https://github.com/SergeShack/OpenMW-DS/releases/latest)
   and install it.
2. On first start, allow all-files access and choose your **Morrowind folder** (the one that contains Data Files, not
   Data Files itself). You need your own copy of Morrowind.
3. Press Play Game.

Later updates are offered by the launcher itself; it downloads and installs them only if you say so. An update installs
over the previous version and keeps your saves.

Saves and settings are in `/storage/emulated/0/OpenMW-DS-DuoLite/`. The settings editor is in the launcher's Settings;
change settings with the game closed.

**Recommended:** set the Duo Lite's performance mode to **High Performance** (quick settings tile). The settings in this
fork were tuned in that mode. In Standard mode the device lowers the GPU clock when it warms up, and the game is less
smooth, especially outdoors.

**Optional mods:** two community mods that rework Morrowind's meshes may also help:
[Morrowind Optimization Patch](https://www.nexusmods.com/morrowind/mods/45384) and
[Project Atlas](https://www.nexusmods.com/morrowind/mods/45399). They are not part of this fork, and they are neither
made nor supported here; questions about them go to their authors. In a short test on the Duo Lite, Project Atlas (on
top of the Optimization Patch) took a little load off the CPU in towns; the Optimization Patch alone made no measurable
difference. Follow each mod's own instructions (Mod Setup in the launcher's Settings shows how to add a mod folder),
and keep a copy of your saves before adding mods.

---
## What is different from OpenMW-DS

### Smoother on the Duo Lite
The game runs at a 30 fps limit, and most of the work went into frames that miss it.
- **Walking outdoors**: crossing a cell border no longer freezes the game for a moment, converted shaders are kept on disk
  between runs, and the game's scripts stay on the fast CPU cores.
- **Combat**: a hit in melee no longer freezes the game. Particles (blood, spell effects, fire, smoke, weather) are drawn
  without OpenMW's shader, as Morrowind's effects were made to be drawn.
- **Shadows are on**: characters, the player and objects cast shadows, reworked to be cheap enough for the Duo Lite (one
  shadow map, fixed in the world, fading out gradually, none at night or in bad weather when the light is too faint).
- **Rain and water**: rain ripples, the water shader and the water reflection cost roughly half of what they did.
- **Terrain** is drawn in one pass per piece of ground instead of one per texture.
- **Indoor water** follows the reflection detail setting instead of drawing the whole room twice.
- Viewing distance 4500.

### Interface
- The game's own windows use MysticCards, OpenMW's TrueType copy of Morrowind's font, at a larger size.
- **Interface size** in the launcher, next to Resolution: 100% to 130%, 125% by default.
- The bottom screen's service screens (training, spell buying, travel, repair, rest, persuasion, alchemy, enchanting,
  spellmaking, level up) share one set of text sizes, with larger text on the smallest ones.

### Controls
- Use (attack, cast) on R1 and Quick Save on R2, since the Duo Lite's triggers sit in line with the shoulder buttons.
- L2 opens the quick keys menu: move with the D-pad, R1 uses the slot, A assigns it, R3 shows its tooltip.
- An action you leave without a button stays unbound after a restart.
- A D-pad direction held in a menu keeps moving the selection, faster after a few steps, so long lists scroll by.

### Fixes
- A crash while walking, in the vibration code.
- The app no longer hangs at start when a firewall blocks its network access.
- Mod Setup reports missing Morrowind, Tribunal or Bloodmoon archives and missing Morrowind.ini values, and a startup
  error shows its message instead of closing the app.
- Started from a launcher on the bottom screen, the app opens on the top screen.

Each release lists its changes in detail, including any settings it changes on update:
[releases](https://github.com/SergeShack/OpenMW-DS/releases).

---
## Reporting a problem

Open an [issue](https://github.com/SergeShack/OpenMW-DS/issues). If you can, add a bug report: in the launcher, Settings >
Report a Problem > Create bug report saves a zip file with your logs, config files and screen details. If GitHub does not
let you attach it (this happens from some phones), attach it from a computer, or upload it to a cloud drive and post the
link.

Please open a separate issue for each problem or request.

---
## Building

The APK is built the same way as OpenMW-DS; see "Building from Source" in its
[README](https://github.com/Josh-Daniels/OpenMW-DS#building-from-source-for-developers). Changes to the OpenMW engine
are kept as patches in `app/src/main/cpp/openmw/patches/openmw/`, applied in the order listed in
`app/src/main/cpp/openmw/CMakeLists.txt`.

---
## Credits

- **[OpenMW-DS](https://github.com/Josh-Daniels/OpenMW-DS)** (Josh Daniels): the app this fork is based on. You can
  support its author on [Ko-fi](https://ko-fi.com/joshd0).
- **[OpenMW](https://gitlab.com/OpenMW/openmw)**: the open-source Morrowind engine. Licensed GPLv3.
- **[openmw-android](https://github.com/xyzz/openmw-android)** and **[Sisah2/openmw-android](https://github.com/Sisah2/openmw-android)**:
  the Android port of OpenMW. Licensed GPLv3.
- **[Alpha3](https://gitlab.com/duron27/alpha3)** (duron27): the launcher OpenMW-DS is forked from.
- **MysticCards** (OpenMW Team, derived from [Pelagiad](https://isaskar.github.io/Pelagiad/) by Isak Larborn) and the
  other bundled fonts, under the SIL Open Font License 1.1.
- **[Material Symbols](https://fonts.google.com/icons)** (Google), under the Apache License 2.0.

Licensed under GPLv3, see [LICENSE.txt](LICENSE.txt) and [3rdparty-licenses.txt](3rdparty-licenses.txt).
