#!/usr/bin/env python3
"""Minimal Maven resolver: fetches an app's dependency tree (POMs -> jars/aars) so the
Kotlin sources can be type-checked with plain kotlinc, without Gradle."""
import os, re, sys, urllib.request, zipfile, xml.etree.ElementTree as ET
from pathlib import Path

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
OUT = Path("/scratch/work/tools7/libs")
OUT.mkdir(parents=True, exist_ok=True)
CACHE = Path("/scratch/work/tools7/pomcache")
CACHE.mkdir(parents=True, exist_ok=True)
NS = "{http://maven.apache.org/POM/4.0.0}"

props_global = {}


def fetch(url, dest):
    if dest.exists() and dest.stat().st_size > 0:
        return dest.read_bytes()
    req = urllib.request.Request(url, headers={"User-Agent": "resolver"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
    except Exception:
        return None
    dest.write_bytes(data)
    return data


def pom_urls(g, a, v):
    path = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom"
    return [f"{GOOGLE}/{path}", f"{CENTRAL}/{path}"]


def artifact_urls(g, a, v, ext):
    path = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"
    return [f"{GOOGLE}/{path}", f"{CENTRAL}/{path}"]


def text(node, tag, default=""):
    el = node.find(NS + tag) if node is not None else None
    return el.text.strip() if el is not None and el.text else default


def subst(value, props):
    for _ in range(6):
        m = re.search(r"\$\{([^}]+)\}", value or "")
        if not m:
            break
        key = m.group(1)
        rep = props.get(key) or props_global.get(key) or ""
        value = value.replace(m.group(0), rep)
    return value


class Pom:
    def __init__(self, xml):
        root = ET.fromstring(xml)
        self.group = text(root, "groupId")
        self.artifact = text(root, "artifactId")
        self.version = text(root, "version")
        self.packaging = text(root, "packaging", "jar")
        self.parent = None
        p = root.find(NS + "parent")
        if p is not None:
            self.parent = (text(p, "groupId"), text(p, "artifactId"), text(p, "version"))
        self.props = {}
        pr = root.find(NS + "properties")
        if pr is not None:
            for child in pr:
                self.props[child.tag.replace(NS, "")] = (child.text or "").strip()
        self.deps = []
        deps = root.find(NS + "dependencies")
        if deps is not None:
            for d in deps.findall(NS + "dependency"):
                self.deps.append({
                    "g": text(d, "groupId"), "a": text(d, "artifactId"),
                    "v": text(d, "version"), "scope": text(d, "scope", "compile"),
                    "type": text(d, "type", "jar"), "optional": text(d, "optional", "false"),
                })
        self.mgmt = []
        dm = root.find(NS + "dependencyManagement")
        if dm is not None:
            inner = dm.find(NS + "dependencies")
            if inner is not None:
                for d in inner.findall(NS + "dependency"):
                    self.mgmt.append({
                        "g": text(d, "groupId"), "a": text(d, "artifactId"),
                        "v": text(d, "version"), "type": text(d, "type", "jar"),
                    })


def load_pom(g, a, v):
    safe = f"{g}_{a}_{v}".replace("/", "_")
    dest = CACHE / f"{safe}.pom"
    for url in pom_urls(g, a, v):
        data = fetch(url, dest)
        if data:
            try:
                return Pom(data)
            except Exception:
                continue
    return None


def resolve(roots):
    """roots: list of (g, a, v). Returns set of (g,a,v,type) to download."""
    seen, found, queue = set(), {}, list(roots)
    while queue:
        g, a, v = queue.pop()
        key = (g, a, v)
        if key in seen:
            continue
        seen.add(key)
        pom = load_pom(g, a, v)
        if pom is None:
            continue
        # inherit parent properties (one level is enough in practice)
        props = dict(pom.props)
        if pom.parent:
            parent = load_pom(*pom.parent)
            if parent:
                for k, val in parent.props.items():
                    props.setdefault(k, val)
                props_global.update(parent.props)
        props.setdefault("project.version", pom.version)
        props.setdefault("project.groupId", pom.group)

        mgmt = {}
        for m in pom.mgmt:
            mgmt[(m["g"], m["a"])] = subst(m["v"], props)
        found[key] = pom.packaging
        for d in pom.deps:
            if d["scope"] in ("test", "provided", "system") or d["optional"] == "true":
                continue
            if d["type"] == "pom" or d["a"].endswith("-bom"):
                dv = subst(d["v"], props) or mgmt.get((d["g"], d["a"]), "")
                if dv:
                    queue.append((d["g"], d["a"], dv))
                continue
            dv = subst(d["v"], props) or mgmt.get((d["g"], d["a"]), "")
            if not dv:
                continue
            queue.append((d["g"], d["a"], dv))
    return found


def download(found):
    jars = []
    for (g, a, v), packaging in sorted(found.items()):
        ext = "aar" if packaging == "aar" else "jar"
        dest = OUT / f"{g}-{a}-{v}.{ext}"
        for url in artifact_urls(g, a, v, ext):
            data = fetch(url, dest)
            if data:
                break
        if not dest.exists():
            # some libs publish aar only; try the other extension once
            other = "jar" if ext == "aar" else "aar"
            for url in artifact_urls(g, a, v, other):
                data = fetch(url, OUT / f"{g}-{a}-{v}.{other}")
                if data:
                    dest = OUT / f"{g}-{a}-{v}.{other}"
                    break
        if dest.exists():
            jars.append(dest)
    return jars


def explode_aars(jars):
    cp = []
    for j in jars:
        if j.suffix == ".aar":
            inner = j.with_suffix(".classes.jar")
            if not inner.exists():
                try:
                    with zipfile.ZipFile(j) as z:
                        names = [n for n in z.namelist() if n == "classes.jar"]
                        if not names:
                            continue
                        inner.write_bytes(z.read("classes.jar"))
                except Exception:
                    continue
            cp.append(inner)
        else:
            cp.append(j)
    return cp


if __name__ == "__main__":
    roots = [
        ("androidx.compose", "compose-bom", "2024.12.01"),
        ("androidx.core", "core", "1.15.0"),
        ("androidx.core", "core-ktx", "1.15.0"),
        ("androidx.activity", "activity", "1.9.3"),
        ("androidx.activity", "activity-compose", "1.9.3"),
        ("androidx.activity", "activity-ktx", "1.9.3"),
        ("androidx.lifecycle", "lifecycle-runtime", "2.8.7"),
        ("androidx.lifecycle", "lifecycle-runtime-compose", "2.8.7"),
        ("androidx.lifecycle", "lifecycle-viewmodel", "2.8.7"),
        ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.7"),
        ("androidx.lifecycle", "lifecycle-viewmodel-ktx", "2.8.7"),
        ("androidx.lifecycle", "lifecycle-viewmodel-savedstate", "2.8.7"),
        ("androidx.lifecycle", "lifecycle-livedata-core", "2.8.7"),
        ("androidx.savedstate", "savedstate-ktx", "1.2.1"),
        ("androidx.compose.ui", "ui-util", "1.7.6"),
        ("androidx.compose.ui", "ui-geometry", "1.7.6"),
        ("androidx.compose.ui", "ui", "1.7.6"),
        ("androidx.compose.ui", "ui-graphics", "1.7.6"),
        ("androidx.compose.ui", "ui-text", "1.7.6"),
        ("androidx.compose.ui", "ui-unit", "1.7.6"),
        ("androidx.compose.ui", "ui-tooling-preview", "1.7.6"),
        ("androidx.compose.runtime", "runtime", "1.7.6"),
        ("androidx.compose.foundation", "foundation", "1.7.6"),
        ("androidx.compose.foundation", "foundation-layout", "1.7.6"),
        ("androidx.compose.animation", "animation", "1.7.6"),
        ("androidx.compose.material3", "material3", "1.3.1"),
        ("androidx.compose.material", "material-icons-core", "1.7.6"),
        ("androidx.savedstate", "savedstate", "1.2.1"),
        ("androidx.collection", "collection", "1.4.5"),
        ("androidx.annotation", "annotation", "1.9.1"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.9.0"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.9.0"),
        ("com.microsoft.onnxruntime", "onnxruntime-android", "1.20.0"),
    ]
    found = resolve(roots)
    print(f"resolved {len(found)} artifacts")
    jars = download(found)
    print(f"downloaded {len(jars)} files")
    cp = explode_aars(jars)
    print(f"classpath entries: {len(cp)}")
    Path("/scratch/work/tools7/classpath.txt").write_text(":".join(str(p) for p in cp))
