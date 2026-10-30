# Source from Git Bash:  source tools/env.sh
#   a <args>                  adb against the stick (output without \r)
#   L "<cmd>" "<result>" "<undo>"   log a change to CHANGES.md
ADB="${ADB:-$LOCALAPPDATA/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe}"
TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The stick's <ip>:5555 lives in .stick at the repo root (not committed).
STICK="${STICK:-$(tr -d '\r\n' < "$TOOLS/../.stick" 2>/dev/null)}"
a() { "$ADB" -s "$STICK" "$@" | tr -d '\r'; }
L() { python -I "$(cygpath -w "$TOOLS/log.py")" "$@"; }
export MSYS_NO_PATHCONV=1
