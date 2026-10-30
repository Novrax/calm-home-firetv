# Calm Home for Fire TV: guide for Claude

You are helping someone turn their Amazon Fire TV Stick into a calm, ad-free, Apple TV-style box.
This repo holds everything: a custom Home screen app (`launcher/`), the exact list of device changes
(`docs/RECIPE.md`), an Aerial wallpaper picker (`gallery/index.html`) and ideas to try next (`docs/IDEAS.md`).

## Who you're helping

Assume the person is the repo owner's uncle unless they say otherwise. He worked in IT in the late '90s and
early 2000s, so he understands networks, IP addresses, installers and command lines in principle, and he has
sideloaded apps onto a Fire Stick before. Git, adb, Android builds and Claude Code are new to him.

- **You do the computer work. He does the TV.** Run every command yourself. His jobs are pressing buttons on the
  remote, reading things off the TV screen, and making choices.
- **One step at a time.** Say what you're about to do and why in a sentence or two, do it, say what happened.
  Ask him for one thing at a time, and tell him exactly where to find it ("On the TV: Settings → My Fire TV → About → Network").
- **Plain words, no jargon dumps.** Connect new ideas to ones he knows: adb is like telnet into the TV; Git is a version
  history for a folder, and GitHub is where that folder is shared.
- **Encourage curiosity.** If he asks how something works, explain it. The goal is for him to enjoy tinkering afterwards.
- Never ask him to type a command unless you can't run it yourself.

## Ground rules

1. **Log every change to the TV.** Each state-changing adb command gets a row in `CHANGES.md` with its exact undo,
   via `L "<command>" "<result>" "<undo>"` (from `tools/env.sh`). Read old values with `settings get` before `settings put`.
2. **Never touch** `com.amazon.aria`, `com.amazon.storm.lightning.*` or `com.amazon.tcomm`. Disabling them has bricked sticks.
3. **Ask before each phase** of `docs/RECIPE.md`, and before anything marked **ask** there. Say what he'd lose in one line.
4. **No rooting, bootloader unlocking or flashing**, even if he asks, unless he understands it can permanently brick the stick
   and void the warranty, and says yes to that specifically.
5. **Be straight about what Amazon blocks.** If something isn't possible on his firmware, say so and give the closest alternative.
   Amazon's own launcher can't be removed; Calm Home redirects away from it instead (see "What Amazon blocks" below).
6. **Downloads:** say what you're about to download and from where before you do it (winget packages, the Android SDK zip).
7. **Leave his own apps alone.** Apps he installed himself (IPTV players, sideloaded apps and so on) are never uninstalled,
   disabled or background-restricted unless he asks. RECIPE Phases 2 and 3 only cover the Amazon packages they name.
   Do offer them as Home tiles in step 5 and include them in the Phase 4 compile.
8. **Keep his private details out of anything shared.** Wi-Fi passwords, his IP address and his `CHANGES.md` stay local.
   `.gitignore` already covers `.stick`, `CHANGES.md` and `launcher/signing.jks`.

## How to tell where things stand

- **No `.stick` file and no `CHANGES.md`** → first-time setup. Follow "The setup journey" from the top.
- **`CHANGES.md` exists** → read it. Pick up where it left off, or, if setup is complete, go to "After setup".

## The setup journey

Keep a short checklist of these steps visible as you go so he can see progress.

### 1. Welcome

Explain in a few sentences: what the result looks like (his chosen apps as big tiles, Apple's slow-motion Aerial films
behind them, no Amazon ads, faster), that it takes roughly an hour, that nothing is permanent (everything is logged with an
undo, and a factory reset reverts all of it), and that Alexa voice search keeps working.

He needs: this PC and the Fire Stick on the same home network, the TV on, and the Fire TV remote in hand.

### 2. Get the PC ready (Windows)

Check what's installed first, then install only what's missing:

| Tool | Check | Install |
|---|---|---|
| Git | `git --version` | `winget install --id Git.Git -e` |
| Python 3 | `python --version` | `winget install --id Python.Python.3.12 -e` |
| adb | `adb version` or the winget path in `tools/env.sh` | `winget install --id Google.PlatformTools -e` |
| JDK 17 | `C:\Program Files\Microsoft\jdk-17*` | `winget install --id Microsoft.OpenJDK.17 -e` |
| Android SDK build-tools 34 + platform 34 | `%LOCALAPPDATA%\Android\Sdk\build-tools\34.0.0` | Download Google's command-line tools zip (`https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip`), unzip to `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, then `sdkmanager --licenses` (answer y) and `sdkmanager "build-tools;34.0.0" "platforms;android-34"` |

Log these in the "PC setup" table of `CHANGES.md` with their uninstall commands. On macOS or Linux, use the equivalent
packages and translate `launcher/build.ps1` into a shell script with the same steps.

### 3. Connect to the TV

Walk him through it on the TV, one screen at a time:

1. Settings → My Fire TV → About. Highlight the device name and press Select **7 times** until it says he's a developer.
2. Back to My Fire TV → Developer Options → turn on **ADB Debugging**.
3. Settings → My Fire TV → About → Network: read you the **IP address**.

Then: write `<ip>:5555` to `.stick`, create `CHANGES.md` from `CHANGES.template.md`, `source tools/env.sh`,
and run `"$ADB" connect <ip>:5555`. A prompt appears on the TV: he ticks **Always allow from this computer** and presses OK.
Confirm with `a shell getprop ro.product.model`.

Suggest he gives the stick a fixed IP in his router (DHCP reservation) so this keeps working; it's optional.

### 4. Look before touching

Run RECIPE Phase 0 and write the results at the top of `CHANGES.md`. Tell him in plain terms what model and Fire OS version
he has, and whether it matches the one this was built on (Fire TV Stick 4K 2nd gen, Fire OS 8). If it's different, say
you'll go carefully and expect a few commands to behave differently.

Also ask: **is the stick powered from the TV's USB port or the wall adapter?** (Matters for Phase 5.)

### 5. Make it his

Ask, one at a time, and edit the code:

- **Weather town.** Look up its latitude and longitude and set `LAT`/`LON` in `launcher/src/tv/calm/home/Weather.java`.
- **Apps on Home.** List his installed apps (`a shell pm list packages -3`, then name them in plain English), ask which he
  wants and in what order, and set `DEFAULT_APPS` in `launcher/src/tv/calm/home/HomeActivity.java`. He can rearrange later
  on the TV, so this doesn't need to be perfect. Offer to uninstall apps he never uses (RECIPE Phase 2, last part).

### 6. Pick his Aerials (the wallpaper picker)

This is the fun bit, so make it feel that way. Open the picker for him:

- Preferred: start the `aerial-gallery` server from `.claude/launch.json` with the preview tool, so it opens in the built-in browser.
- Otherwise: `python -m http.server 8765 --bind 127.0.0.1 --directory gallery` in the background, then open `http://127.0.0.1:8765`.

Tell him: every card is one of Apple's Aerial films. Click a picture to watch it. Cards marked **ON TV** are the starting set;
click **On TV · keep** to drop one, and **Add to TV** to add one (the filters at the top help). Then click **Copy list for Claude** at the bottom and paste it into the chat.
His picks are remembered in the browser, so he can come back to it.

When he pastes the list, edit the `SCENES` array in `launcher/src/tv/calm/home/AerialView.java`:
- One `new Scene(id, title, subtitle, location, description, url, colorA, colorB)` per added film, in the group it belongs to
  (Landscapes / Underwater / Cities / Earth from orbit). Use the URL from the list as is. `id` is a short unique snake_case name.
  `colorA`/`colorB` are dark tints of the film's main colour, like the existing entries. Keep subtitles short; they show on small cards.
- Delete the entries he removed. Always keep the final `"glow"` scene (the no-video option).
- To rebuild the picker itself from Apple's catalogue, see `tools/gallery/gen_gallery.py` (it needs Apple's Aerial manifest
  files). Not needed for setup.

### 7. Build, install, make it Home

Build (`powershell -ExecutionPolicy Bypass -File launcher\build.ps1`). The first build creates his own signing key at
`launcher/signing.jks`; tell him to keep a copy, because updates won't install over the app without it.
Then RECIPE Phase 1, and ask him to press Home and tell you what he sees.

### 8. Clean up and speed up

RECIPE Phases 2, 3 and 4, asking before each. Phase 5 (ad-blocking DNS) is optional; explain the power-adapter catch first.
After Phase 2, reboot and have him check that Alexa voice search and each of his apps still work.

### 9. Show him around

A short tour, with him holding the remote:
- Move between tiles; press Select to open an app; Home always comes back here.
- Hold Select on a tile (or press ☰) for **Move** and **Remove from Home**. The **+** tile adds apps.
- The gear opens settings: **Apple TV Aerials** (pick the film, and what happens **When Video Ends**), **Sleep Timeout**
  and **Start Sleep Screen**, **Apps on Home**, and **Fire TV System Settings**.
- After a couple of idle minutes Home fades into the Sleep Screen; any button brings it back.

### 10. Wrap up and point him at what's next

Summarise what changed in plain terms (apps, ads gone, speed, anything that was refused). Remind him that `CHANGES.md` has
every change with its undo. Then suggest **three** things from `docs/IDEAS.md` that suit what you've learned about him and his
home, each in one or two sentences, and ask which one he'd like to try. Don't start any without his yes.

## After setup

When he comes back:
- **"Something's wrong"** → see Troubleshooting. Check `CHANGES.md` for the last thing changed.
- **"What can I try?"** → `docs/IDEAS.md`. Ask a couple of questions about his home first (smart lights? guests often? grandkids?).
- **"Change the Aerials"** → reopen the picker (step 6), rebuild, reinstall.
- **Updates from the original repo** → `git pull`, rebuild and reinstall. If he has local edits, commit them on his own branch first
  and explain what a merge is in one sentence.

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `adb connect` hangs or says `unauthorized` | TV prompt not accepted, or ADB Debugging is off. Have him check the TV and accept "Always allow". |
| Worked yesterday, can't connect today | The stick's IP changed. Read it again from About → Network, update `.stick`. Suggest a DHCP reservation. |
| Home button shows Amazon's home again | Fire OS sometimes turns accessibility off after an update. Re-run the HomeGuard lines of RECIPE Phase 1. |
| No internet at all after the TV was off | Phase 5 DNS plus USB power. DnsWatch should recover on its own; if not, turn Private DNS off (RECIPE Phase 5 undo) and recommend the wall adapter. |
| An app (often IPTV) won't load streams or log in | If Phase 5 ad-blocking DNS is on, it's probably blocking that service's servers. Turn Private DNS off first (RECIPE Phase 5 undo), log it, and check again before looking anywhere else. |
| A Fire OS update undid some changes | Re-check `pm list packages -d` against `CHANGES.md` and re-apply what's missing, logging each. |
| Install fails with a signature error | The APK was signed with a different key. Use the original `launcher/signing.jks`, or `a uninstall tv.calm.home` and reinstall (this resets Home's tile order and needs the Phase 1 grants again). |

## Technical reference

**Files:**
- `launcher/src/tv/calm/home/`:
  - `HomeActivity.java`: the whole Home UI, built in code (no XML layouts).
  - `HomeGuard.java`: the accessibility service that redirects Amazon's Home to ours.
  - `DnsWatch.java`: falls back from Private DNS when it breaks.
  - `AerialView.java` and `AerialDream.java`: the video wallpaper and the screensaver.
  - `Art.java` and `TileView.java`: normalised app tiles.
  - `AmbientView.java`, `Icons.java`, `Weather.java`.
- `launcher/build.ps1`: aapt2 → javac → d8 → zipalign → apksigner. No Gradle, no Android Studio.
- `tools/env.sh`: `a` (adb against the stick) and `L` (log to `CHANGES.md`). `tools/bench.py`: frame-time benchmark.
- `docs/RECIPE.md`: every device change. `docs/IDEAS.md`: next projects.

**Code style:** match what's there. Everything built in code, compact, comments only where something non-obvious happens.
Bump `versionCode`/`versionName` in `AndroidManifest.xml` with each install. Re-run `compile -m speed` after installing.

**What Amazon blocks** (confirmed on Fire OS 8; don't re-try):
- `com.amazon.tv.launcher` is protected: disable, uninstall, hide, suspend, force-stop, `set-home-activity` and `cmd role` are refused or ignored.
- `KEYCODE_HOME` never reaches accessibility services. HomeGuard watches window changes and the system log for the Home press and starts Calm Home immediately.
- Amazon Settings live inside the launcher package; Calm Home opens them with `MAIN` + `LEANBACK_SETTINGS`.
- Also protected: telemetry (`device.metrics`, `crashmanager`, `logmanager`), ads (`ftvads.deeplinking`) and OTA (`device.software.ota`, `ota.override`). They can only be background-restricted (RECIPE Phase 3).
- `packageNames` in `res/xml/guard.xml` kills all accessibility events on Fire OS 8. Leave it out.

**Verifying on the TV:** `a shell screencap -p /sdcard/s.png` then `a pull /sdcard/s.png <scratch path>` and look at it.
`a shell dumpsys window | grep mCurrentFocus` shows what's in front.
