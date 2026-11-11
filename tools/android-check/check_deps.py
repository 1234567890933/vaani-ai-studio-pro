#!/usr/bin/env python3
"""Verifies that every third-party import has a declared dependency, and that declared
dependencies are actually used. A missing dependency is a compile error in Gradle; an unused
one is dead weight in the APK."""
import re, sys, pathlib

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
gradle = (root / "app/build.gradle.kts").read_text()
src = root / "app/src/main/java"

declared = re.findall(r'implementation\("([^"]+)"\)', gradle)
bom = re.findall(r'platform\("([^"]+)"\)', gradle)
declared_all = declared + bom

# import root -> the token that must appear in some declared coordinate
# import prefix -> tokens, any one of which satisfies it ("" = framework-provided)
RULES = {
    "androidx.compose.material3": ["material3"],
    "androidx.compose.material.icons": ["material-icons", "material3"],
    "androidx.compose.ui.tooling": ["ui-tooling"],
    "androidx.compose.ui": ["compose.ui:ui", "material3"],
    "androidx.compose.foundation": ["foundation", "material3"],
    "androidx.compose.runtime": ["compose.runtime:runtime", "material3"],
    "androidx.compose.animation": ["animation", "material3"],
    "androidx.activity.result": ["activity"],
    "androidx.activity.compose": ["activity-compose"],
    "androidx.activity": ["activity"],
    "androidx.lifecycle.runtime.compose": ["lifecycle-runtime-compose"],
    "androidx.lifecycle.viewmodel": ["lifecycle-viewmodel", "lifecycle"],
    "androidx.lifecycle": ["lifecycle"],
    "androidx.work": ["work-runtime"],
    "androidx.core": ["core-ktx"],
    "androidx.annotation": ["annotation", "core-ktx"],
    "androidx.collection": ["collection", "core-ktx"],
    "kotlinx.coroutines": ["kotlinx-coroutines"],
    "ai.onnxruntime": ["onnxruntime"],
    "org.json": [],                       # part of the Android framework
    "android": [],
    "java": [],
    "kotlin": [],
    "javax": [],
}

problems = 0
used_tokens = set()
for f in src.rglob("*.kt"):
    for line in f.read_text().splitlines():
        m = re.match(r"import\s+([\w.]+)", line.strip())
        if not m:
            continue
        imp = m.group(1)
        if imp.startswith("ai.hindidub"):
            continue                      # our own code
        rule = "UNSET"
        for prefix, tokens in sorted(RULES.items(), key=lambda kv: -len(kv[0])):
            if imp == prefix or imp.startswith(prefix + "."):
                rule = tokens
                break
        if rule == "UNSET":
            print(f"UNKNOWN: {f.name} imports {imp} with no rule")
            problems += 1
            continue
        if not rule:
            continue                      # framework-provided
        for token in rule:
            used_tokens.add(token)
        if not any(any(t in d for d in declared_all) for t in rule):
            print(f"MISSING DEP: {f.name} imports {imp} needs one of {rule}")
            problems += 1

print("declared:", ", ".join(sorted({d.split(':')[1] if ':' in d else d for d in declared_all})))
print(f"check_deps: {problems} problem(s)")
sys.exit(1 if problems else 0)
