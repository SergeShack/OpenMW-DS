# OpenMW-DS Phone

A phone build of **[OpenMW-DS Duo Lite](https://github.com/SergeShack/OpenMW-DS)**, itself a fork of
**[OpenMW-DS](https://github.com/Josh-Daniels/OpenMW-DS)**. OpenMW-DS plays Morrowind (through OpenMW) on one screen and
puts the character sheet, inventory, magic, journal, map and many of the game's menus on a second one. This build brings
that to a phone: with a TV or monitor as the game screen and the companion on the phone, or on the phone alone with a
touch controller.

All credit for OpenMW-DS goes to its author.

It has everything in OpenMW-DS Duo Lite (smoother shadows, rain, water and terrain, Interface size, fixes; see the
[duo-lite branch](https://github.com/SergeShack/OpenMW-DS/tree/duo-lite)), plus what is described below.

---
## Install

1. Download `openmw-ds-phone-X.Y.Z.apk` from the newest
   [Phone release](https://github.com/SergeShack/OpenMW-DS/releases?q=phone-v&expanded=true) (tags `phone-vX.Y.Z`) and
   install it.
2. On first start, allow all-files access and choose your **Morrowind folder** (the one that contains Data Files, not
   Data Files itself). You need your own copy of Morrowind.
3. Press Play Game.

Later updates are offered by the launcher itself; it downloads and installs them only if you say so. An update installs
over the previous version and keeps your saves.

It is a separate app from OpenMW-DS Duo Lite and the original OpenMW-DS, and installs next to them. Its saves and
settings are in `/storage/emulated/0/OpenMW-DS-Phone/`. The settings editor is in the launcher's Settings; change
settings with the game closed.

---
## Phone + External Screen

The default device profile (Settings > Device).

**With a TV or monitor**
- The game runs on the external screen, and the companion (inventory, magic, map, journal, stats) on the phone, with the
  touch controller at its sides.
- The external screen can be a USB-C to HDMI cable, if your phone has video out over USB-C, or a wireless cast
  (Miracast, "Cast" or "Screen mirroring" in the phone's quick settings).
- Connect the screen before pressing Play. The game resolution follows the external screen.
- While a game window is shown on the TV, the look pad on the phone moves the game's cursor there.

**Without one**
- The game runs on the phone alone, with the menus as in the original game, and the touch controller over the edges of
  the game.

---
## Touch controller

To the game it is an ordinary gamepad, so its buttons follow the controller bindings and can be rebound in the game's
controls menu. The default layout:

| Left | Right |
|---|---|
| LT: quick keys menu | ≡: menu |
| LB: journal | RB: use (attack or cast) |
| ⧉: rest | RT: quick save |
| D-pad: inventory (up), camera zoom (left/right) | A: activate, B: jump, X: ready magic, Y: ready weapon |
| Stick: move. Tap to sneak | ⚔: attack or cast. Hold and slide to aim |
| | LOOK: drag to look around. In menus it scrolls. Tap to switch view |

- The stick starts wherever your thumb lands in its area. Push it all the way to run.
- **Your own layout**: the pencil button in the top right corner opens an editor. Drag any part to move it, drag its
  corner to resize it, hide the parts you do not need, and set how see-through the controller is. Reset puts everything
  back.
- **Hide**: the eye button next to it hides the whole controller, leaving a small button to show it again.
- **Gamepad**: while a real gamepad is connected, the touch controller hides by itself.
- **Menus**: while a menu is open, the controller narrows to the buttons menus use, and its right side becomes a touchpad
  for the game's cursor: move to point, tap to click, tap and then drag to hold, two fingers to scroll. The game's windows
  are placed between the two sides.

---
## Known issues

- A wireless cast adds a noticeable delay to the picture. A cable does not, where the phone supports it.
- Turning the cast off while playing closes the game. Save first.
- To move the game to an external screen you connected while playing, quit the game and press Play again.
- If the phone gets hot, a frame rate limit (`framerate limit` in the Video section of the settings editor, for example
  30 or 45) keeps it cooler.

---
## Reporting a problem

Open an [issue](https://github.com/SergeShack/OpenMW-DS/issues) and say it is about the Phone app, your phone model and
how the screens were connected (cable, cast, phone alone). If you can, add a bug report: in the launcher, Settings >
Report a Problem > Create bug report saves a zip file with your logs, config files and screen details. If GitHub does not
let you attach it (this happens from some phones), attach it from a computer, or upload it to a cloud drive and post the
link.

Please open a separate issue for each problem or request.

---
## Building and credits

[OpenMW-DS](https://github.com/Josh-Daniels/OpenMW-DS) is by Josh Daniels; you can support its author on
[Ko-fi](https://ko-fi.com/joshd0). See the [duo-lite branch](https://github.com/SergeShack/OpenMW-DS/tree/duo-lite)
for building and the full credits. The phone
code lives in `app/src/main/java/org/openmw/phone`, and the engine patches only this build uses are
`app/src/main/cpp/openmw/patches/openmw/phone-*.patch`.

Licensed under GPLv3, see [LICENSE.txt](../LICENSE.txt) and [3rdparty-licenses.txt](../3rdparty-licenses.txt).
