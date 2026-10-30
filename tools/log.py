# Appends a "| Dn | command | result | undo |" row to ../CHANGES.md.
# Usage: python tools/log.py "<command>" "<result>" "<undo>"
import os, re, sys
p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "CHANGES.md")
cmd, res, undo = sys.argv[1:4]
s = open(p, encoding="utf-8").read().rstrip("\n")
n = len(re.findall(r"^\| D\d+ ", s, re.M)) + 1
esc = lambda t: t.replace("|", r"\|")
s += f"\n| D{n} | `{esc(cmd)}` | {esc(res)} | `{esc(undo)}` |\n"
open(p, "w", encoding="utf-8").write(s)
print(f"logged D{n}")
