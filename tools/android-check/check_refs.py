#!/usr/bin/env python3
"""Cross-checks the AndroidManifest, the resource folder and the Kotlin sources.

Catches the boring breakages that stop an APK from installing or launching:
  * a class named in the manifest that does not exist;
  * an @drawable/@string/@xml/@style reference with no resource behind it;
  * an R.* reference in code with no resource behind it.
"""
import re, sys, pathlib, xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
app = root / "app/src/main"
manifest = app / "AndroidManifest.xml"
res = app / "res"
src = app / "java"
ANDROID = "{http://schemas.android.com/apk/res/android}"

problems = 0

# ---- 1. classes referenced by the manifest --------------------------------
tree = ET.parse(manifest)
manifest_classes = []
for el in tree.iter():
    name = el.get(ANDROID + "name")
    if name and el.tag.split("}")[-1] in ("activity", "service", "receiver", "provider", "application"):
        manifest_classes.append((el.tag.split("}")[-1], name))

kotlin_classes = set()
for f in src.rglob("*.kt"):
    txt = f.read_text()
    pkg = re.search(r"^package (\S+)", txt, re.M)
    pkg = pkg.group(1) if pkg else ""
    for m in re.finditer(r"^(?:internal |private |public |open |abstract )?(?:class|object|interface) (\w+)", txt, re.M):
        kotlin_classes.add(f"{pkg}.{m.group(1)}")

# AGP takes the namespace from build.gradle.kts; manifests reference classes relative to it
gradle = (root / "app/build.gradle.kts").read_text()
ns = re.search(r'namespace\s*=\s*"([^"]+)"', gradle)
NAMESPACE = ns.group(1) if ns else ""

for kind, name in manifest_classes:
    if name.startswith("androidx.") or name.startswith("android."):
        continue                      # framework / library classes are not ours
    fq = name
    if fq.startswith("."):
        fq = NAMESPACE + fq
    elif "." not in fq:
        fq = f"{NAMESPACE}.{fq}"
    if fq not in kotlin_classes:
        print(f"MANIFEST: <{kind}> {name} does not resolve to a class in the sources")
        problems += 1

# ---- 2. resources referenced by the manifest ------------------------------
VALUE_KINDS = {"string", "color", "dimen", "style", "integer", "bool", "array", "plurals"}


def res_exists(kind, name):
    if kind in VALUE_KINDS:
        for f in (res / "values").glob("*.xml"):
            if re.search(rf'<{kind}\b[^>]*name="{re.escape(name)}"', f.read_text()):
                return True
        return False
    for d in res.iterdir():
        if d.name.split("-")[0] != kind:
            continue
        for f in d.iterdir():
            stem = f.name.split(".")[0]
            if stem == name:
                return True
    return False

for el in tree.iter():
    for attr, val in el.attrib.items():
        if not isinstance(val, str) or not val.startswith("@"):
            continue
        ref = val[1:]
        if ref.startswith("android:") or ref.startswith("+"):
            continue
        if "/" in ref:
            kind, name = ref.split("/", 1)
            found = res_exists(kind, name)
        else:
            continue
        if not found:
            print(f"RESOURCE: manifest references @{ref} but it does not exist")
            problems += 1

# ---- 3. R.* references in code -------------------------------------------
for f in src.rglob("*.kt"):
    for m in re.finditer(r"\bR\.(\w+)\.(\w+)", f.read_text()):
        kind, name = m.group(1), m.group(2)
        if kind == "style":
            ok = any(f'name="{name}"' in p.read_text() for p in (res / "values").glob("*.xml"))
        else:
            ok = res_exists(kind, name)
        if not ok:
            print(f"R-REF: {f.relative_to(root)} uses R.{kind}.{name} with no resource")
            problems += 1

print(f"check_refs: {problems} problem(s)")
sys.exit(1 if problems else 0)
