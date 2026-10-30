# Home-screen frame timing + memory for tv.calm.home (read-only; changes nothing on the stick).
# Usage: python tools/bench.py [label]
# Presses the standard D-pad sequence on Home, then reports per-stage frame times from
# `dumpsys gfxinfo framestats` (ms) and the launcher's PSS / GL memory (MB).
import os, subprocess, sys, time, statistics as st

ADB = os.environ.get("ADB") or os.path.expandvars(
    r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe")
_stick_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".stick")
STICK = os.environ.get("STICK") or open(_stick_file).read().strip()
PKG = "tv.calm.home"

def sh(cmd):
    return subprocess.run([ADB, "-s", STICK, "shell", cmd], capture_output=True, text=True).stdout.replace("\r", "")

def front():
    return next((l.strip() for l in sh("dumpsys window").splitlines() if "mCurrentFocus" in l), "?")

label = sys.argv[1] if len(sys.argv) > 1 else "run"
print(f"[{label}] front before: {front()}")
sh(f"dumpsys gfxinfo {PKG} reset")
time.sleep(0.5)
for k in [22, 22, 22, 22, 20, 21, 21, 21, 21, 19]:
    sh(f"input keyevent {k}")
    time.sleep(0.35)
time.sleep(1.0)
out = sh(f"dumpsys gfxinfo {PKG} framestats")
print(f"[{label}] front after:  {front()}")

rows, cols = [], None
for line in out.splitlines():
    if line.startswith("Flags,"):
        cols = line.strip().rstrip(",").split(",")
    elif cols and line and line[0].isdigit() and "," in line:
        v = line.strip().rstrip(",").split(",")
        if len(v) >= len(cols):
            d = dict(zip(cols, map(int, v[:len(cols)])))
            if d["Flags"] == 0:
                rows.append(d)
    elif cols and line.startswith("---PROFILEDATA---") and rows:
        break

def stat(name, f):
    xs = [f(r) / 1e6 for r in rows if f(r) > 0]
    if xs:
        xs.sort()
        print(f"  {name:<10} median {st.median(xs):6.1f}  p90 {xs[int(len(xs) * 0.9) - 1 if len(xs) > 1 else 0]:6.1f}  max {xs[-1]:6.1f}")

print(f"[{label}] frames: {len(rows)}")
stat("ui", lambda r: r["SyncStart"] - r["IntendedVsync"] if r["SyncStart"] > r["IntendedVsync"] else 0)
stat("render", lambda r: r["SwapBuffers"] - r["SyncStart"])
stat("gpu", lambda r: r["GpuCompleted"] - r["SwapBuffers"] if r.get("GpuCompleted", 0) else 0)
stat("total", lambda r: max(r["FrameCompleted"], r.get("GpuCompleted", 0)) - r["IntendedVsync"])

mem = sh(f"dumpsys meminfo {PKG}")
for line in mem.splitlines():
    s = line.strip()
    if s.startswith(("TOTAL PSS:", "Native Heap:", "Graphics:")):
        parts = s.split()
        name = " ".join(parts[:2]) if parts[0] == "TOTAL" else parts[0] + " " + parts[1]
        print(f"  {s.split(':')[0]:<12} {int(s.split(':')[1].split()[0]) / 1024:6.1f} MB")
