import re, pathlib
libs = pathlib.Path("/scratch/work/tools7/libs")
best = {}
def vkey(v):
    parts = re.split(r"[.\-]", v)
    out = []
    for p in parts:
        out.append(int(p) if p.isdigit() else -1)
    return out
for f in list(libs.glob("*.classes.jar")) + list(libs.glob("*.jar")):
    name = f.name
    m = re.match(r"(.+?)-(\d[\w.\-]*)\.(classes\.)?jar$", name)
    if not m:
        continue
    art, ver = m.group(1), m.group(2)
    # Compose/KMP libraries publish the real artifact as <name>-android; treat it as <name>
    for suffix in ("-android", "-jvm", "-desktop"):
        if art.endswith(suffix):
            art = art[: -len(suffix)]
    if "kotlin-stdlib" in art or art.endswith("kotlin-test"):
        continue          # kotlinc supplies its own stdlib
    cur = best.get(art)
    if cur is None or vkey(ver) > vkey(cur[0]):
        best[art] = (ver, f)
cp = [str(v[1]) for v in sorted(best.values(), key=lambda t: str(t[1]))]
pathlib.Path("/scratch/work/tools7/classpath.txt").write_text(":".join(cp))
print(f"{len(cp)} entries")
for k in ("androidx.activity-activity", "androidx.core-core", "androidx.compose.ui-ui",
          "androidx.compose.runtime-runtime", "androidx.compose.foundation-foundation",
          "androidx.compose.material3-material3", "androidx.lifecycle-lifecycle-runtime"):
    print(" ", k, best.get(k, ("MISSING", None))[0])
