# Iris Shader Integration for Voxy NeoForge 1.21.1 — Design

**Date:** 2026-06-08
**Status:** Approved (design phase)
**Target:** Voxy NeoForge port, Minecraft 1.21.1, NeoForge 21.1.x

## Goal

Make Voxy's LOD rendering work correctly when the **Iris** shader mod is active,
targeting the exact Iris version the user runs: **`iris-neoforge-1.8.1+mc1.21.1`**.

"Working" = the built mod compiles, all Iris mixin targets are verified against the
real Iris 1.8.1 jar, the game does not crash with or without Iris present, and Voxy
LODs render under a shaderpack. Final in-game visual confirmation is performed by the
user in their `Dos Crias` Prism instance (which already runs Iris 1.8.1, Sodium
0.6.13, Forgified Fabric API — the matching stack).

## Background / Current State (evidence)

The Iris integration is **already ported as source but deliberately disabled**:

- All integration source exists, mojmapped:
  - `me/cortex/voxy/client/iris/**` (8 files)
  - `me/cortex/voxy/client/mixin/iris/**` (11 files)
  - `me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java`
  - `me/cortex/voxy/client/core/util/IrisUtil.java`
- `build.gradle` lines 20–24 **exclude** all of the above from compilation.
- The Iris dependency is **commented out** (`build.gradle:200`) and the version string
  there (`1.10.4+1.21.11-neoforge`) is **bogus** — no such build exists for 1.21.1.
- The iris mixins are **not registered** in `client.voxy.mixins.json`.

Verified facts:

- Correct dependency: **`maven.modrinth:iris:1.8.1+1.21.1-neoforge`** (matches the
  user's instance). Reference jar downloaded to `.reference/iris/iris-1.8.1.jar`.
- All 10 distinct mixin target classes (`Iris`, `IrisRenderingPipeline`,
  `IrisSamplers`, `ProgramSet`, `PackRenderTargetDirectives`, `ShaderPackSourceNames`,
  `StandardMacros`, `CommonUniforms`, `CustomUniforms`, `WorldRenderingSettings`)
  **exist** in the 1.8.1 jar — package structure transfers cleanly.
- Upstream `MCRcortex/voxy` currently targets **Iris `1.10.9+26.1`** (newer MC/Iris),
  so the port's iris files were copied from a newer-Iris lineage. The central risk is
  **method-signature drift** between what the mixins expect and what 1.8.1 exposes.
- Upstream ships the iris mixins directly in the main `client.voxy.mixins.json` with
  no mixin-config plugin — relying on Mixin skipping a mixin whose `@Mixin(target)`
  class is absent. This keeps Iris optional.

## Strategy: A + B mix

- **A (base):** Reuse the port's existing iris source — the mojmap pass is already done.
- **B (corrective reference):** Where a file's signature is wrong for Iris 1.8.1, fix it
  using upstream `MCRcortex/voxy` (cloned to `.reference/voxy-upstream`) as the second
  reference, then re-validate against the 1.8.1 jar.

Every change is backed by `javap` evidence from `.reference/iris/iris-1.8.1.jar`
(reference-first rule, CLAUDE.md). No guessing.

## Components / Changes

### 1. Dependency wiring — `build.gradle`
- Replace the bogus commented line (200) with:
  `compileOnly("maven.modrinth:iris:1.8.1+1.21.1-neoforge")`
- Optionally add a matching `runtimeOnly` only if needed for dev-instance testing;
  not required since the user tests in their own Prism instance.

### 2. Declare optional dependency — `src/main/resources/META-INF/neoforge.mods.toml`
- Add an `iris` dependency entry with `type = "optional"` so load order is correct
  when Iris is present, and Voxy still loads when it is not.

### 3. Re-enable compilation — `build.gradle` sourceSets
- Remove the four iris `exclude` lines (`iris/**`, `IrisVoxyRenderPipeline.java`,
  `core/util/IrisUtil.java`, `mixin/iris/**`).

### 4. Register mixins — `src/main/resources/client.voxy.mixins.json`
- Add the `iris.*` entries for the 11 mixin files present in the port. Reconcile the
  port's actual file set against the upstream list (e.g. port has
  `MixinPackRenderTargetDirectives`; ensure each registered entry maps to an existing
  file and vice versa).

### 5. Signature-drift fixes (the core work)
For each of the 11 iris mixins + `IrisVoxyRenderPipeline` + `IrisUtil`:
- Validate **every** `@Inject` / `@Redirect` / `@WrapOperation` / `@Accessor` /
  `@Shadow` target method descriptor and every referenced Iris field/type against
  `iris-1.8.1.jar` (`javap -p -c` / `unzip` + `javap`).
- Fix any mismatch; where the port is stale, port the correct shape from
  `.reference/voxy-upstream`, then re-verify against 1.8.1.
- Record evidence (class + descriptor) in commit messages / inline comments.

### 6. Optional-presence gating
- Confirm the NeoForge Mixin runtime skips an iris mixin when its target class is
  absent (no Iris). If it does **not** skip cleanly, add a mixin-config plugin
  (`IMixinConfigPlugin.shouldApplyMixin`) returning `ModList.get().isLoaded("iris")`
  for `me.cortex.voxy.client.mixin.iris.*`.
- Guard the runtime pipeline construction (the code path that builds
  `IrisVoxyRenderPipeline`) behind an Iris-loaded / Iris-pipeline-active check so the
  non-shader path is unaffected.

## Data Flow (unchanged from upstream design)

When a shaderpack is active, Iris owns the render pipeline. Voxy's iris mixins hook
Iris pipeline construction to: inject Voxy's samplers/uniforms, register Voxy render
targets, patch program sources/macros, and drive `IrisVoxyRenderPipeline` (which renders
Voxy LODs into Iris's gbuffer framebuffers, including optional deferred translucency).
When no shaderpack is active, none of this runs and Voxy uses its normal pipeline.

## Error Handling

- **Iris absent:** mixins skipped (or gated by plugin); pipeline construction not
  reached. Voxy behaves exactly as today.
- **Shader load problems:** existing `ShaderLoadError` / `IrisShaderPatch` paths are
  preserved; not redesigned here.

## Testing / Verification

- `./gradlew build` must be green with the integration enabled.
- Each registered iris mixin target verified present + matching descriptor in
  `iris-1.8.1.jar` via `javap` (evidence captured).
- Sanity: build still succeeds and is loadable without Iris (gating correct).
- **User-performed final check:** install the built jar into the `Dos Crias` instance
  and confirm Voxy LODs render correctly with a shaderpack enabled.

## Scope Guardrails (YAGNI)

- **Iris only.** No Nvidium, Vivecraft, or Flashback work in this effort.
- No refactor of unrelated rendering code.
- No attempt to support Iris versions other than 1.8.1 (the user's target). If it
  happens to work on nearby 1.8.x builds, that is incidental, not a goal.

## Open Questions / Risks

- **Signature drift depth:** unknown until each mixin is validated; bounded by the 13
  files in scope and fully resolvable against the 1.8.1 jar.
- **Mixin auto-skip on NeoForge:** to be confirmed during implementation; fallback is
  the mixin-config plugin described in §6.
