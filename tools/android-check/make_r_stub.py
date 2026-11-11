#!/usr/bin/env python3
"""Generates a stub R.kt from res/ so the sources can be type-checked without AAPT."""
import re, sys, pathlib

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
res = root / "app/src/main/res"
ns = re.search(r'namespace\s*=\s*"([^"]+)"', (root / "app/build.gradle.kts").read_text()).group(1)

kinds = {}
for d in res.iterdir():
    if not d.is_dir():
        continue
    kind = d.name.split("-")[0]
    names = set()
    for f in d.iterdir():
        if f.suffix in (".xml", ".png", ".webp", ".jpg"):
            names.add(f.stem)
    if kind == "values":
        for f in d.glob("*.xml"):
            for m in re.finditer(r'<(string|color|style|dimen|integer|bool)\b[^>]*name="([^"]+)"', f.read_text()):
                kinds.setdefault(m.group(1), set()).add(m.group(2).replace(".", "_"))
    elif names:
        kinds.setdefault(kind, set()).update(names)

out = [f"package {ns}", "", "/** Generated stub of R for type-checking only. */", "object R {"]
for kind, names in sorted(kinds.items()):
    out.append(f"    object {kind} {{")
    for i, n in enumerate(sorted(names), start=1):
        out.append(f"        const val {n}: Int = {i}")
    out.append("    }")
out.append("}")
pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "R.kt").write_text("\n".join(out) + "\n")
print(f"stub with {sum(len(v) for v in kinds.values())} resources: " + ", ".join(f"{k}={len(v)}" for k, v in sorted(kinds.items())))
