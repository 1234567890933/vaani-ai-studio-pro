# Verification harness

Three independent checks. The first two need only Python; the third needs a JDK and a Kotlin
compiler.

```bash
bash tools/android-check/verify.sh          # refs + deps + self-test (if JDK/Kotlin set)
```

## 1. `check_refs.py` — manifest and resources

Cross-checks `AndroidManifest.xml`, `res/` and the Kotlin sources:

* every `<activity>`, `<service>`, `<provider>` name resolves to a real class;
* every `@drawable/…`, `@string/…`, `@xml/…`, `@style/…` in the manifest exists;
* every `R.type.name` in code exists in `res/`.

Catches the breakages that make an APK install but crash on launch.

## 2. `check_deps.py` — dependencies vs imports

Every `import` of a third-party package must be covered by a declared Gradle dependency
(framework packages such as `android.*`, `java.*`, `org.json` are exempt). A missing
dependency is a Gradle build failure; this catches it before Gradle runs.

## 3. `compile.sh` + `resolve.py` + `mkcp.py` — real type-check

Type-checks the actual sources with plain `kotlinc`, no Gradle:

```bash
python3 tools/android-check/resolve.py     # resolves Maven POMs → AARs/JARs into libs/
python3 tools/android-check/mkcp.py        # one version per artifact, KMP-aware
bash    tools/android-check/compile.sh     # kotlinc against android.jar + that classpath
```

Needs `android.jar` from platform 35 at `../android-35/android.jar` (set `ANDROID_JAR`), a
JDK 17 and a `kotlinc` distribution. The classpath file is written to `classpath.txt`.

Known limitation: bytecode generation for Compose files crashes in the Kotlin 2.0.21 IR
inliner when the libraries come from AAR-extracted jars (`Couldn't inline method call …
CompositionLocal.current`). Frontend analysis of those same files is clean, and Gradle/AGP
builds them normally.

## 4. `jvmtest/` — engine logic self-test

Compiles the pure-logic classes (VAD, PCM store, mixer, cache, resampler, language and model
catalogues, JSON parser, diagnostics) and runs 1,502 assertions per round.

```bash
JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlinc bash tools/jvmtest/run_selftest.sh
SEED=7 java -jar build/selftest/selftest.jar      # different random data
```
