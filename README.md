# Calm Home for Fire TV

Turns an Amazon Fire TV Stick into a calm, Apple TV-style box:

- **Your apps only**, as big tiles in the order you choose
- **Apple's Aerial films** playing behind them, picked from a gallery on your PC
- **No Amazon ads**, sponsored rows, autoplaying previews or most of the tracking
- **Faster**: smooth 60 fps Home screen, quicker app launches
- **Alexa voice search still works**

Nothing is permanent. Every change to the TV is logged with its undo, and a factory reset reverts all of it.
No rooting, no unlocking.

## Setting it up (the easy way)

You don't need to know Git, Android or adb. [Claude Code](https://claude.com/claude-code) does the technical work and
walks you through the rest. You'll need:

- a Windows PC on the same home network as the Fire Stick
- the TV on and the Fire TV remote in your hand
- about an hour

1. Install the Claude desktop app from [claude.com/download](https://claude.com/download), sign in, and open the **Code** tab.
2. Pick your **Documents**, and create a folder to work in.
3. Paste this message:

   > Please download https://github.com/Novrax/calm-home-firetv into my Documents folder, then read the CLAUDE.md
   > inside it and guide me through setting up my Fire TV Stick, one step at a time.

Claude installs the tools it needs, connects to the TV, lets you pick your apps and Aerial wallpapers, then sets
everything up, asking before each change. At the end it suggests things to try next, like a guest Wi-Fi QR code on the
TV or lights that dim when a film starts.

## What's inside

| | |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | The step-by-step guide Claude follows |
| [`docs/RECIPE.md`](docs/RECIPE.md) | Every change made to the TV, with what it does and how to undo it |
| [`docs/IDEAS.md`](docs/IDEAS.md) | Projects to try once it's running |
| [`launcher/`](launcher) | The Calm Home app (Java, built without Android Studio) |
| [`gallery/index.html`](gallery/index.html) | The Aerial wallpaper picker |
| [`tools/`](tools) | Helper scripts: adb shortcuts, change logging, a speed benchmark |

Built and tested on a Fire TV Stick 4K (2nd gen) with Fire OS 8. Other recent Fire TV models should work with small
differences; Claude checks your model first.

Aerial videos are streamed from Apple's servers and belong to Apple. App names and logos belong to their owners.
This project isn't affiliated with Amazon or Apple.
