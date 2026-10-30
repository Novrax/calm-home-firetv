# The recipe: every change, in order

This is the full list of changes that turned a stock Fire TV Stick into Calm Home, proven on a
**Fire TV Stick 4K (2nd gen, AFTKM), Fire OS 8.1.8.2**. Other models and firmware are close but not
identical, so treat each command as *try, check, log*: a refused command is normal and harmless.

Conventions:
- `a …` means `adb -s <ip>:5555 …` (see `tools/env.sh`). Run from Git Bash after `source tools/env.sh`.
- Log **every** state-changing command with `L "<command>" "<result>" "<undo>"`. It appends a row to `CHANGES.md`.
- Before each phase, tell the person in one or two plain sentences what it does and what they'd lose.
  Phases marked **ask** need a yes first.

## Never touch

Disabling any of these has bricked sticks in this family. Don't disable, uninstall or restrict them, even if asked to "remove everything":

- `com.amazon.aria`
- `com.amazon.storm.lightning.*`
- `com.amazon.tcomm`

Also leave alone on purpose: Wi-Fi, Bluetooth, input, remote and HDMI-CEC packages, Settings (`com.amazon.tv.settings.v2`),
setup (`oobe`), the Amazon account (`com.amazon.dcp`, `identity`), the Appstore (`com.amazon.venezia`, it keeps apps updated),
Alexa (`com.amazon.vizzini` and the speech packages), and the IME (`com.amazon.tv.ime`).

## Phase 0. Look before touching (read-only)

```bash
a shell getprop ro.product.model          # AFTKM = 4K 2nd gen, AFTKRT = 4K Max 2nd gen, etc.
a shell getprop ro.product.device         # codename (karat, mantra, ...)
a shell getprop ro.build.display.id       # firmware build, e.g. RS8182.3811N
a shell getprop ro.build.version.sdk      # 28 = Fire OS 7, 30 = Fire OS 8
a shell df -h /data
a shell pm list packages -3               # apps the person installed
a shell pm list packages -d               # anything already disabled
```

Write the answers at the top of `CHANGES.md`. If the SDK is below 26, stop: the launcher needs Android 8 or newer.

## Phase 1. Install Calm Home and make it the Home screen

Build first (`powershell -ExecutionPolicy Bypass -File launcher\build.ps1`). Then:

| What | Command | Undo |
|---|---|---|
| Install | `a install -r launcher/out/calm-home.apk` | `a uninstall tv.calm.home` |
| Turn on HomeGuard (sends the Home button to Calm Home) | `a shell settings put secure enabled_accessibility_services tv.calm.home/tv.calm.home.HomeGuard` | `a shell settings put secure enabled_accessibility_services ""` (or the old value) |
| | `a shell settings put secure accessibility_enabled 1` | `… accessibility_enabled 0` |
| Instant Home button (reads system log for the Home press) | `a shell pm grant tv.calm.home android.permission.READ_LOGS` | `a shell pm revoke tv.calm.home android.permission.READ_LOGS` |
| Lets DnsWatch switch DNS (only matters if Phase 5 is used) | `a shell pm grant tv.calm.home android.permission.WRITE_SECURE_SETTINGS` | `… pm revoke …` |
| Keep it from being killed | `a shell cmd deviceidle whitelist +tv.calm.home` | `a shell cmd deviceidle whitelist -tv.calm.home` |
| Aerials as the screensaver | `a shell settings put secure screensaver_components tv.calm.home/.AerialDream` and `a shell settings put secure screensaver_default_component tv.calm.home/.AerialDream` | read the old values first with `settings get`, put them back |

Read the old value of every `settings put` with `settings get` first and use it in the undo.

**Check:** press Home on the remote. Calm Home should appear within half a second. Reboot (`a reboot`), reconnect, and check again.

Amazon's own launcher (`com.amazon.tv.launcher`) is protected: it can't be disabled, uninstalled, hidden or replaced as
the default Home, and `set-home-activity` / `cmd role` are silently ignored. HomeGuard redirecting it is the workaround. Don't spend time retrying.

## Phase 2. Remove Amazon bloat, ads and tracking (ask)

`a shell pm disable-user --user 0 <pkg>` for each. Undo: `a shell pm enable <pkg>`. Skip any that don't exist on this stick.
Some will be refused as protected. Log that and move on.

| Package | What it is |
|---|---|
| `com.amazon.gamehub` | Games storefront |
| `com.amazon.tv.releasenotes` | Release notes |
| `com.amazon.tv.easyupgrade` | Upgrade promos |
| `com.amazon.shoptv.client`, `com.amazon.shoptv.firetv.client` | Shopping |
| `com.amazon.kso.blackbird` | Special-offers ad framework |
| `com.amazon.wirelessmetrics.service` | Wi-Fi metrics |
| `com.amazon.smarthomemapviewapp` | Smart-home camera viewer (**ask** if they own Ring/Blink cameras) |
| `com.amazon.tv.acr` | Automatic content recognition (tracks what's on screen) |
| `com.amazon.tahoe` | Amazon Kids (**ask** if grandchildren use it) |
| `com.amazon.tv.ftvambient` | Ambient Experience (replaced by Aerials) |
| `com.amazon.dp.logger`, `com.amazon.perfcollection`, `com.amazon.perfc` | Loggers |
| `com.amazon.sneakpeek` | Autoplaying hero previews |
| `com.amazon.minitv.android.app` | Amazon miniTV |
| `com.amazon.hybridadidservice` | Advertising-ID service |
| `com.amazon.bueller.photos` | Amazon Photos (**ask**) |
| `com.amazon.bueller.music` | Amazon Music (**ask**) |
| `com.amazon.device.telemetry.emitter` | Telemetry |
| `com.amazon.cloud9` | Silk browser (**ask**) |
| `com.amazon.media.recommendations` | Recommendation rows |
| `com.amazon.neo.minerva`, `com.amazon.neo.minerva.logcat` | Metrics daemon |
| `com.amazon.tv.livetv` | Live TV tab |
| `com.amazon.tv.earlyaccess` | Early-access programme |
| `com.amazon.logan` | Log analytics |
| `com.amazon.hedwig` | Fire TV Channels (ad-supported) |
| `com.amazon.aca` | Content aggregation |
| `com.amazon.ceviche` | Remote config; also causes Home to flicker |
| `com.amazon.client.metrics.api` | Metrics API |
| `com.amazon.device_remote_config_sync_service` | Remote config sync |
| `com.amazon.connectivitydiag` | Connectivity diagnostics |
| `com.amazon.tv.developer.dataservice` | Data puller |
| `com.amazon.tv.support` | Support / one-click purchase prompts |
| `com.amazon.firetv.troubleshooting` | Troubleshooting app |
| `com.amazon.tv.turnstile` | Interstitial screens |
| `com.amazon.tv.website_launcher` | Website launcher |
| `com.amazon.ftv.screensaver` | Stock screensaver (replaced by Aerials) |
| `com.amazon.tmm.tutorial` | Tutorial |
| `com.amazon.tv.ffsprovisioneeclient` | Frustration-free setup |
| `com.amazon.tv.matter`, `com.amazon.tv.mattercohost` | Matter smart-home (**ask**: keep if they want smart-home control from the TV; see IDEAS.md) |
| `com.amazon.ods.kindleconnect` | Kindle connect |
| `com.amazon.spiderpork` | Preloads and cross-app deeplinks |
| `com.amazon.tigris` | Upgrade restore service |
| `com.amazon.d3` | Provisioning telemetry |
| `com.amazon.whad`, `com.amazon.whasettings`, `com.amazon.audiohome` | Whole Home Audio with Echo speakers (**ask** if they group Echos with the TV) |
| `com.amazon.avls.experience` | Audio calibration |
| `com.amazon.whisperjoin.middleware.v2.np`, `com.amazon.whisperplay.service.install` | Zero-touch setup, remote app install |
| `com.android.traceur` | System tracing |
| `com.amazon.tv.forcedotaupdater.v2` | Forced system updates |

Then reboot and confirm Home, Alexa voice search and every app still open.

Unwanted *user* apps (games, channels they never watch): ask, then `a shell pm uninstall <pkg>`. Undo: reinstall from the Appstore.

## Phase 3. Quiet the protected Amazon packages

These can't be disabled, but they can be stopped from running in the background. For each package:

```bash
a shell 'for op in RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do cmd appops set <pkg> $op ignore; done'
```

Undo: the same loop with `default` instead of `ignore`.

`com.amazon.tv.launcher`, `com.amazon.diode`, `com.amazon.adep`, `com.fireos.usagestats.proxy`, `com.fireos.arcus.proxy`,
`com.amazon.tv.csapp`, `com.amazon.neopactservice`, `com.amazon.systemnotices`, `com.amazon.tiramisubootsupport`,
`com.amazon.ftvads.deeplinking`, `com.amazon.device.software.ota`, `com.amazon.device.software.ota.override`,
`com.amazon.device.metrics`, `com.amazon.device.crashmanager`, `com.amazon.device.logmanager`, `com.amazon.webview.chromium`,
and (**ask**, it's their app) `com.amazon.firebat` (Prime Video still works when opened; it just stops sitting in memory).

Also:

| Command | Undo |
|---|---|
| `a shell cmd appops set com.amazon.tv.launcher TAKE_AUDIO_FOCUS ignore` | `… default` |
| `a shell settings put secure non_amz_limit_ad_tracking 1` | `… 0` |
| `a shell settings put secure pvde_playback_api_enabled_for_launcher 0` | `… 1` |
| `a shell settings put secure smart_home_presence_launcher 0` | `… 1` |
| `a shell settings put secure st_show_live_card 0` | `… 1` |
| `a shell settings put secure st_show_device_control_card 0` | `… 1` |
| `a shell cmd deviceidle sys-whitelist -<pkg>` for `com.amazon.device.crashmanager`, `com.amazon.device.logmanager`, `com.amazon.whisperlink.core.android`, `com.amazon.awvflingreceiver`, `com.amazon.uxnotification` | `… sys-whitelist +<pkg>` |

## Phase 4. Speed

| What | Command | Undo |
|---|---|---|
| Instant window animations | `a shell settings put global window_animation_scale 0.0` and `… transition_animation_scale 0.0` | set both back to `1.0` |
| Longer CPU boost on app start | `a shell settings put global perfboost_appstart_duration 2000` | put back the old value (`settings get` first; it was 200) |
| Fewer cached background apps (stops memory thrashing) | `a shell settings put global activity_manager_constants max_cached_processes=8,background_settle_time=10000` | `a shell settings delete global activity_manager_constants` |
| Fully compile each Home app, Calm Home, `com.android.systemui` and `com.amazon.tv.settings.v2` | `a shell cmd package compile -m speed -f <pkg>` | `a shell cmd package compile -m speed-profile -f <pkg>` |

Check free space before compiling; each app can take a minute and tens of MB. Re-run the compile for Calm Home after every reinstall.

To measure, with Calm Home on screen: `python tools/bench.py before` / `after`. Below 16.7 ms per frame is smooth 60 fps.

## Phase 5. Optional: ad-blocking DNS (ask)

```bash
a shell settings put global private_dns_specifier dns.adguard-dns.com
a shell settings put global private_dns_mode hostname
```

Undo: `a shell settings put global private_dns_mode off` and `a shell settings delete global private_dns_specifier`.

Warning, learned the hard way: if the stick is powered from the **TV's USB port**, it cold-boots every time the TV turns off,
its clock starts wrong, AdGuard's certificate looks invalid and *all* internet breaks. Calm Home's DnsWatch detects that and falls
back to the router's DNS automatically, but the real fix is powering the stick from the wall adapter. Ask how it's powered first.

## After every launcher change

```bash
powershell -ExecutionPolicy Bypass -File launcher\build.ps1
a install -r launcher/out/calm-home.apk
a shell cmd package compile -m speed -f tv.calm.home
```

Bump `versionCode` and `versionName` in `launcher/AndroidManifest.xml`, then log the install with what changed.
The `pm grant`s and settings survive a reinstall; an uninstall wipes the grants.

## Undo everything

Settings → My Fire TV → Reset to Factory Defaults reverts all of it. For a gentler undo, walk `CHANGES.md` from the bottom up.
