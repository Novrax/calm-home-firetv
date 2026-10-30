# Fire TV Stick: change log

Device: <model, codename, Fire OS version, build> (from RECIPE Phase 0)
Stick power: <TV USB port / wall adapter>

Every state-changing command is listed in order, with its exact undo.
`adb` commands assume `adb connect <ip>:5555` is active.

**Undo, in general:** `pm disable-user` → `adb shell pm enable <pkg>`. Uninstalled apps → reinstall from the Appstore.
Remove the launcher → `adb uninstall tv.calm.home` (Fire OS then shows Amazon's home again).
A factory reset (Settings → My Fire TV → Reset to Factory Defaults) reverts everything on the device.

## PC setup

| # | Command | Undo |
|---|---------|------|

## Device changes

| # | Command | Result | Undo |
|---|---------|--------|------|
