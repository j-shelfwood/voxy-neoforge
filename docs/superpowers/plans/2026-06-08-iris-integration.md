# Iris 1.8.1 Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Voxy's existing (currently-disabled) Iris integration so LOD rendering works under a shaderpack, targeting `iris-neoforge-1.8.1+mc1.21.1`.

**Architecture:** Re-enable the already-ported iris source (`client/iris/**`, `mixin/iris/**`, `IrisVoxyRenderPipeline`, `IrisUtil`), add Iris as a compileOnly + optional dependency, register the iris mixins, then validate every mixin target against the real Iris 1.8.1 jar and fix signature drift (using upstream `MCRcortex/voxy` as a corrective reference where the port is stale). Iris stays optional — mixins must be skipped when Iris is absent.

**Tech Stack:** NeoForge 21.1.x, Minecraft 1.21.1, Mixin, Iris 1.8.1 (Modrinth maven), Gradle (`net.neoforged.moddev`).

**Reference assets (already in place, git-ignored under `.reference/`):**
- `.reference/iris/iris-1.8.1.jar` — the validation source of truth.
- `.reference/voxy-upstream/` — upstream `MCRcortex/voxy` clone (corrective reference; note it targets Iris 1.10.9, so never copy blindly — always re-verify against the 1.8.1 jar).

**Validation helper (used throughout):** to inspect a class's methods/fields in the 1.8.1 jar:
```bash
cd /home/jrflga/voxy-neoforge
CP=.reference/iris/iris-1.8.1.jar
javap -p -classpath "$CP" net.irisshaders.iris.pipeline.IrisRenderingPipeline | grep -i <method>
```
For a method's exact bytecode/descriptor (to confirm `@At INVOKE target` descriptors):
```bash
javap -p -c -classpath "$CP" net.irisshaders.iris.pipeline.IrisRenderingPipeline | less
```

---

## Task 1: Add Iris dependency and declare it optional

**Files:**
- Modify: `build.gradle` (the commented dependency near line 200)
- Modify: `src/main/resources/META-INF/neoforge.mods.toml`

- [ ] **Step 1: Replace the bogus commented Iris dependency**

In `build.gradle`, find:
```gradle
    //compileOnly("maven.modrinth:iris:1.10.4+1.21.11-neoforge")
```
Replace with:
```gradle
    compileOnly("maven.modrinth:iris:1.8.1+1.21.1-neoforge")
```

- [ ] **Step 2: Verify the dependency resolves**

Run: `./gradlew dependencies --configuration compileClasspath | grep -i iris`
Expected: a line resolving `maven.modrinth:iris:1.8.1+1.21.1-neoforge` (no "FAILED").

- [ ] **Step 3: Declare Iris as an optional dependency in mods.toml**

First read the existing dependency blocks to copy the exact format:
Run: `grep -n "dependencies\|modId\|type =\|ordering\|side" src/main/resources/META-INF/neoforge.mods.toml`

Then add (matching the file's existing `[[dependencies.voxy]]` style — adjust the mod id `voxy` to the actual root id used in the file):
```toml
[[dependencies.voxy]]
    modId = "iris"
    type = "optional"
    versionRange = "[1.8.1,)"
    ordering = "AFTER"
    side = "CLIENT"
```

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/main/resources/META-INF/neoforge.mods.toml
git commit -m "feat(iris): add Iris 1.8.1 as compileOnly + optional dependency"
```

---

## Task 2: Validate iris mixin targets against the 1.8.1 jar (no build yet)

This task fixes source statically BEFORE re-enabling compilation, so the first build has the best chance of succeeding. Work file-by-file. For each mixin, list its targets, confirm each against the jar, fix mismatches.

**Files (validate + fix as needed):**
- `src/main/java/me/cortex/voxy/client/mixin/iris/MixinIris.java`
- `.../mixin/iris/MixinIrisRenderingPipeline.java`
- `.../mixin/iris/MixinIrisSamplers.java`
- `.../mixin/iris/MixinLevelRenderer.java`
- `.../mixin/iris/MixinMatrixUniforms.java`
- `.../mixin/iris/MixinPackRenderTargetDirectives.java`
- `.../mixin/iris/MixinProgramSet.java`
- `.../mixin/iris/MixinShaderPackSourceNames.java`
- `.../mixin/iris/MixinStandardMacros.java`
- `.../mixin/iris/IrisRenderingPipelineAccessor.java`
- `.../mixin/iris/CustomUniformsAccessor.java`

- [ ] **Step 1: Dump every Iris target referenced by the mixins**

Run:
```bash
cd /home/jrflga/voxy-neoforge
grep -rno 'method = "[^"]*"\|target = "[^"]*"\|@Accessor("[^"]*")\|@Shadow' \
  src/main/java/me/cortex/voxy/client/mixin/iris/
```
Expected: a list of every `method=`, `target=`, `@Accessor`, `@Shadow` to validate.

- [ ] **Step 2: For each mixin, validate its `method=` targets exist on the `@Mixin` class**

For each mixin file, identify its `@Mixin(X.class)` target, then for each `method = "name"` confirm a matching method exists in the jar:
```bash
CP=.reference/iris/iris-1.8.1.jar
# example for MixinIrisRenderingPipeline (@Mixin IrisRenderingPipeline):
javap -p -classpath "$CP" net.irisshaders.iris.pipeline.IrisRenderingPipeline \
  | grep -iE 'addNonDynamicUniforms|addRenderTargetSamplers|createSetupComputes|<init>'
```
Expected: each injected method name appears. If a name is ABSENT, find the renamed equivalent in the jar (`javap` full dump) and update the mixin's `method=`. If still unclear, compare with `.reference/voxy-upstream` then re-verify against the jar.

- [ ] **Step 3: Validate every `@At(target = "L...;name(...)...")` invoke descriptor**

For each `@At(value="INVOKE", target=...)`, confirm the referenced owner+name+descriptor exists. Example (the `createSetupComputes` redirect/inject target):
```bash
CP=.reference/iris/iris-1.8.1.jar
javap -p -c -classpath "$CP" net.irisshaders.iris.pipeline.IrisRenderingPipeline \
  | grep -i createSetupComputes
```
Confirm the parameter/return descriptor in the mixin's `target=` string matches the jar. Fix any descriptor that differs (param types, return type, owner package). Targets with `remap = false` (Iris-internal) must match the jar exactly; MC-targeting injects (e.g. `LevelRenderer`) are validated in Task 3.

- [ ] **Step 4: Validate `@Accessor` / `@Shadow` members**

For `IrisRenderingPipelineAccessor` and `CustomUniformsAccessor`, confirm each shadowed/accessed field or method exists with the expected type:
```bash
CP=.reference/iris/iris-1.8.1.jar
javap -p -classpath "$CP" net.irisshaders.iris.pipeline.IrisRenderingPipeline
javap -p -classpath "$CP" net.irisshaders.iris.uniforms.custom.CustomUniforms
```
Expected: each `@Accessor("field")` maps to a real field; each `@Shadow` member exists. Fix names/types that drifted.

- [ ] **Step 5: Record findings inline**

For any change made, add a brief comment above the annotation noting the evidence, e.g.:
```java
// Iris 1.8.1: IrisRenderingPipeline.addNonDynamicUniforms(UniformHolder) — verified via javap
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/mixin/iris/
git commit -m "fix(iris): align iris mixin targets with Iris 1.8.1 (javap-verified)"
```

---

## Task 3: Validate the MC-targeting iris mixins for 1.21.1

`MixinLevelRenderer` (iris package) and any iris mixin touching Minecraft classes target mojmap MC names, not Iris. These already carry port-specific edits (DIFF vs upstream).

**Files:**
- `src/main/java/me/cortex/voxy/client/mixin/iris/MixinLevelRenderer.java`
- Reference: `.reference/minecraft/1.21.1/decompiled/` (if present) or the project's existing `minecraft.MixinLevelRenderer` for the correct mojmap signatures.

- [ ] **Step 1: List MC targets in the iris MixinLevelRenderer**

Run: `grep -nE '@Mixin|method =|target =|@At' src/main/java/me/cortex/voxy/client/mixin/iris/MixinLevelRenderer.java`
Expected: the injected MC method(s) and injection points.

- [ ] **Step 2: Confirm each target against MC 1.21.1 mojmap**

Cross-check method names against the existing working `src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinLevelRenderer.java` (already compiles for 1.21.1) and/or `.reference/minecraft/1.21.1/decompiled/`. Fix any non-mojmap or removed method names.
Expected: every `method=`/`target=` resolves to a real MC 1.21.1 member.

- [ ] **Step 3: Commit (only if changes were needed)**

```bash
git add src/main/java/me/cortex/voxy/client/mixin/iris/MixinLevelRenderer.java
git commit -m "fix(iris): align iris MixinLevelRenderer with MC 1.21.1 mojmap"
```

---

## Task 4: Re-enable compilation of the iris source

**Files:**
- Modify: `build.gradle` (sourceSets exclude block, lines ~20-24)

- [ ] **Step 1: Remove the four iris excludes**

In `build.gradle`, delete these lines from the `sourceSets.main.java` block:
```gradle
            // Iris shader integration
            exclude 'me/cortex/voxy/client/iris/**'
            exclude 'me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java'
            exclude 'me/cortex/voxy/client/core/util/IrisUtil.java'
            exclude 'me/cortex/voxy/client/mixin/iris/**'
```

- [ ] **Step 2: Attempt compilation (expect errors — this maps the remaining drift)**

Run: `./gradlew compileJava 2>&1 | tee /tmp/iris_compile.log | tail -60`
Expected: either BUILD SUCCESSFUL, or a finite list of compile errors in `client/iris/**`, `IrisVoxyRenderPipeline`, `IrisUtil`, or the mixins. These are the API drifts to fix in Task 5.

- [ ] **Step 3: Do NOT commit yet** — proceed to Task 5 to resolve compile errors. (If Step 2 was already SUCCESSFUL, skip to Task 6.)

---

## Task 5: Fix iris integration-class compile errors against Iris 1.8.1

Resolve every error from Task 4 Step 2. These are in non-mixin integration code that calls Iris APIs directly.

**Files (as flagged by the compiler):**
- `src/main/java/me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java`
- `src/main/java/me/cortex/voxy/client/core/util/IrisUtil.java`
- `src/main/java/me/cortex/voxy/client/iris/IrisVoxyRenderPipelineData.java`
- `src/main/java/me/cortex/voxy/client/iris/VoxySamplers.java`
- `src/main/java/me/cortex/voxy/client/iris/VoxyUniforms.java`
- `src/main/java/me/cortex/voxy/client/iris/IrisShaderPatch.java`
- other `client/iris/*` files if flagged

- [ ] **Step 1: For each compile error, find the real Iris 1.8.1 API**

For a "cannot find symbol" / "method does not exist" error on an Iris type, dump that type and locate the correct member:
```bash
CP=.reference/iris/iris-1.8.1.jar
javap -p -classpath "$CP" <fully.qualified.IrisType>
```
Replace the call with the verified 1.8.1 signature. Cross-reference `.reference/voxy-upstream` for intent, but the jar is authoritative.

- [ ] **Step 2: Re-compile after each file's fixes**

Run: `./gradlew compileJava 2>&1 | tail -40`
Expected: error count strictly decreases. Repeat Step 1 until BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/
git commit -m "fix(iris): align iris integration classes with Iris 1.8.1 API (javap-verified)"
```

---

## Task 6: Register the iris mixins

**Files:**
- Modify: `src/main/resources/client.voxy.mixins.json`

- [ ] **Step 1: Add the iris mixin entries**

Add these to the `"client"` array (one per file that exists in `mixin/iris/`), keeping the array alphabetically grouped as the file already is:
```json
    "iris.CustomUniformsAccessor",
    "iris.IrisRenderingPipelineAccessor",
    "iris.MixinIris",
    "iris.MixinIrisRenderingPipeline",
    "iris.MixinIrisSamplers",
    "iris.MixinLevelRenderer",
    "iris.MixinMatrixUniforms",
    "iris.MixinPackRenderTargetDirectives",
    "iris.MixinProgramSet",
    "iris.MixinShaderPackSourceNames",
    "iris.MixinStandardMacros",
```

- [ ] **Step 2: Validate JSON**

Run: `python3 -c "import json; json.load(open('src/main/resources/client.voxy.mixins.json')); print('valid')"`
Expected: `valid`

- [ ] **Step 3: Full build (mixin validation runs here)**

Run: `./gradlew build 2>&1 | tee /tmp/iris_build.log | tail -60`
Expected: BUILD SUCCESSFUL. If the mixin processor reports an unresolved target (`@Inject`/`@Redirect` target not found, or `defaultRequire` not met), the named target still drifted — return to Task 2/3 for that specific mixin, fix against the jar, rebuild.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/client.voxy.mixins.json
git commit -m "feat(iris): register iris mixins in client.voxy.mixins.json"
```

---

## Task 7: Ensure Iris stays optional (no crash when Iris is absent)

Iris is an optional dependency. The build above compiles against Iris, but at runtime Iris may be missing. Confirm the iris mixins are skipped and the integration code is never reached without Iris.

**Files:**
- Possibly create: `src/main/java/me/cortex/voxy/client/mixin/VoxyMixinPlugin.java`
- Possibly modify: `src/main/resources/client.voxy.mixins.json` (add `"plugin"` key)
- Inspect: the runtime code path that constructs `IrisVoxyRenderPipeline` (find with grep below)

- [ ] **Step 1: Confirm how the integration is gated at runtime**

Run:
```bash
grep -rn "new IrisVoxyRenderPipeline\|isLoaded(\"iris\"\|IrisApi\|irisLoaded\|ModList" \
  src/main/java/me/cortex/voxy/
```
Expected: locate where Voxy decides to use the Iris pipeline. Confirm it is guarded by an Iris-present check (NeoForge: `ModList.get().isLoaded("iris")`, or an Iris API call wrapped in a try/availability check). If the construction is NOT guarded, add a `ModList.get().isLoaded("iris")` guard around it.

- [ ] **Step 2: Decide whether a mixin-config plugin is needed**

Default assumption: Mixin skips a `@Mixin(IrisClass.class)` mixin when `IrisClass` is absent. To be safe for the `remap=false` Iris mixins, add a plugin that explicitly disables `me.cortex.voxy.client.mixin.iris.*` when Iris is not loaded.

Create `src/main/java/me/cortex/voxy/client/mixin/VoxyMixinPlugin.java`:
```java
package me.cortex.voxy.client.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class VoxyMixinPlugin implements IMixinConfigPlugin {
    private static final boolean IRIS_LOADED =
            LoadingModList.get().getModFileById("iris") != null;

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("me.cortex.voxy.client.mixin.iris.")) {
            return IRIS_LOADED;
        }
        return true;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode n, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode n, String m, IMixinInfo i) {}
}
```
> Note: `LoadingModList.get()` is the mixin-time-safe way to detect a mod on NeoForge (mixins run before `ModList` is fully built). Verify `LoadingModList`/`getModFileById` exist in NeoForge 21.1 via `javap`/decompiled refs; if the API differs, use the verified equivalent.

- [ ] **Step 3: Wire the plugin into the mixin config**

In `src/main/resources/client.voxy.mixins.json`, add at the top level:
```json
  "plugin": "me.cortex.voxy.client.mixin.VoxyMixinPlugin",
```

- [ ] **Step 4: Rebuild**

Run: `./gradlew build 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/mixin/VoxyMixinPlugin.java src/main/resources/client.voxy.mixins.json
git commit -m "feat(iris): gate iris mixins behind Iris-loaded check (optional integration)"
```

---

## Task 8: Final build verification + docs

**Files:**
- Modify: `README.md` (the "not yet ported" line)
- Modify: `build.gradle` (only if a comment about the iris exclusion remains)

- [ ] **Step 1: Clean build from scratch**

Run: `./gradlew clean build 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. Note the produced jar path under `build/libs/`.

- [ ] **Step 2: Confirm the jar contains the iris classes and registered mixins**

Run:
```bash
JAR=$(ls -t build/libs/*.jar | head -1)
unzip -l "$JAR" | grep -iE 'mixin/iris|client/iris|IrisVoxyRenderPipeline' | head
```
Expected: iris mixin + integration classes are present in the jar.

- [ ] **Step 3: Update README**

In `README.md`, change the line:
```
- Some optional integrations not yet ported (Iris, Nvidium, Vivecraft)
```
to:
```
- Optional integrations not yet ported: Nvidium, Vivecraft (Iris shader support is implemented, targeting Iris 1.8.1 for 1.21.1)
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: mark Iris integration as implemented (Iris 1.8.1)"
```

- [ ] **Step 5: Hand off for in-game verification**

Provide the built jar path. The user installs it into the `Dos Crias` Prism instance (Iris 1.8.1, Sodium 0.6.13, FFAPI already present) and confirms Voxy LODs render with a shaderpack enabled, and that the game still launches normally with Iris removed.

---

## Notes for the executor

- **Reference-first is mandatory (CLAUDE.md):** never change a signature without `javap` evidence from `.reference/iris/iris-1.8.1.jar`. Upstream is a hint, not authority — it targets a newer Iris.
- **`remap = false`** on Iris mixins is correct (Iris classes aren't obfuscated/mojmapped); do not remove it.
- If a mixin's target genuinely no longer exists in 1.8.1 (feature absent in that version), prefer making that single injector non-required (`require = 0`) with a comment over deleting the mixin — but only after confirming the feature's absence in the jar. Flag any such case to the user.
- Build command for quick iteration: `./gradlew compileJava`; full mixin validation: `./gradlew build`.
