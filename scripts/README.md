# Voxy Build Validation Scripts

This directory contains automated validation scripts for catching runtime failures at build time.

## Scripts

### validate_mixins.sh

**Purpose:** Validates that all mixins declared in JSON configs exist in the JAR artifact

**Trigger:** Automatic (runs as part of `./gradlew build`)

**What it checks:**
- Parses all `*.mixins.json` files in `src/main/resources/`
- Extracts mixin class references from `.mixins[]`, `.client[]`, `.server[]` arrays
- Converts `package.ClassName` to `path/ClassName.class`
- Verifies each class file exists in `build/libs/*.jar`
- Reports phantom references with detailed error messages

**Exit codes:**
- `0` - All mixins valid
- `1` - Phantom references found or JAR missing

**Example output:**
```
=== MIXIN CONFIGURATION VALIDATION ===

Validating JAR: voxy-0.2.9-alpha.jar

Checking client.voxy.mixins.json...
  Validating .client[]...
    ✓ minecraft.MixinClientChunkCache
    ✓ minecraft.MixinMinecraft
    ✗ chunky.MixinFabricWorld
      Expected: me/cortex/voxy/client/mixin/chunky/MixinFabricWorld.class
      Status: NOT FOUND IN JAR

=== VALIDATION SUMMARY ===
Total mixins checked: 17
✗ VALIDATION FAILED
Found 1 phantom mixin reference(s)
```

**Manual usage:**
```bash
# After building
./gradlew jar
bash scripts/validate_mixins.sh
```

**Gradle integration:**
```gradle
task validateMixins(type: Exec) {
    dependsOn jar
    commandLine 'bash', 'scripts/validate_mixins.sh'
}
build.dependsOn validateMixins
```

**Prevented failures:**
- chunky.MixinFabricWorld ClassNotFoundException
- Any mixin class excluded from compilation but still referenced in JSON

### setup_reference_sources.sh

**Purpose:** Extracts Minecraft decompiled sources from NeoForm cache to `.reference/`

**Trigger:** Manual (run after first successful build)

**What it does:**
1. Searches `~/.gradle/caches/neoform/` for Minecraft 1.21.1 sources
2. Verifies NeoForm decompilation completed
3. Copies sources to `.reference/minecraft/1.21.1/decompiled/`
4. Validates critical classes exist

**Requirements:**
- Must run `./gradlew build` first to populate NeoForm cache
- Requires ~500MB disk space for decompiled sources

**Usage:**
```bash
# First build to populate cache
./gradlew build

# Extract sources
bash scripts/setup_reference_sources.sh
```

**Example output:**
```
=== MINECRAFT REFERENCE SOURCES SETUP ===

Found NeoForm cache: neoforge-1.21.1-21.1.84-sources
Source directory: /Users/you/.gradle/caches/neoform/.../sources
Java source files: 3142
Total size: 487M

Copying sources to .reference/minecraft/1.21.1/decompiled...
✓ Sources copied successfully

Verifying critical Minecraft classes...
  ✓ net/minecraft/util/thread/BlockableEventLoop.java
  ✓ net/minecraft/client/Minecraft.java
  ✓ net/minecraft/client/renderer/LevelRenderer.java
  ✓ net/minecraft/world/level/chunk/LevelChunk.java

=== SETUP COMPLETE ===

Usage examples:
  # Search for a method:
  grep -r 'isNonRecoverable' .reference/minecraft/1.21.1/decompiled/

  # Find a class:
  find .reference/minecraft/1.21.1/decompiled -name 'BlockableEventLoop.java'
```

**Use cases:**
- Verify target method exists before creating mixin
- Check method signatures against actual Minecraft code
- Understand API changes between versions
- Prevent InvalidMixinException at runtime

**Prevented failures:**
- BlockableEventLoop.isNonRecoverable() InvalidMixinException
- Any @Shadow/@Inject targeting non-existent methods

## Validation Workflow

### Before Creating New Mixin

```bash
# 1. Search for target class
find .reference/minecraft/1.21.1/decompiled -name "TargetClass.java"

# 2. Verify method exists
grep -A 10 "targetMethod" .reference/minecraft/1.21.1/decompiled/path/to/TargetClass.java

# 3. Check Sodium's implementation
grep -r "@Mixin.*TargetClass" .reference/sodium/
```

### After Modifying Mixin Configs

```bash
# Build with automatic validation
./gradlew build

# Or run validation manually
bash scripts/validate_mixins.sh
```

### Setting Up Reference Sources

```bash
# One-time setup after first build
bash scripts/setup_reference_sources.sh

# Update after version changes
rm -rf .reference/minecraft/
./gradlew clean build
bash scripts/setup_reference_sources.sh
```

## Integration with Build System

### Gradle Tasks

```bash
# Full build (includes validation)
./gradlew build

# Just validation
./gradlew validateMixins

# Clean build with validation
./gradlew clean build
```

### CI/CD

GitHub Actions workflows automatically run:
1. Wrapper validation (`wrapper-validation@v3`)
2. Java 21 setup
3. Gradle build (includes `validateMixins`)
4. Artifact upload

See `.github/workflows/build.yml` and `.github/workflows/pull-request.yml`

## Troubleshooting

### "No JAR file found in build/libs/"

**Cause:** JAR not built yet
**Solution:**
```bash
./gradlew jar
bash scripts/validate_mixins.sh
```

### "Minecraft 1.21.1 decompiled sources not found"

**Cause:** NeoForm cache not populated
**Solution:**
```bash
./gradlew build  # Wait for createMinecraftArtifacts task
bash scripts/setup_reference_sources.sh
```

### Validation passes but runtime still fails

**Cause:** Target method doesn't exist (not caught by JAR validation)
**Solution:** Use `.reference/minecraft/` to verify methods before creating mixins

## Maintenance

### After Minecraft Version Update

```bash
# Update version
vim gradle.properties  # minecraft_version=1.21.2

# Clean old cache
rm -rf .reference/minecraft/1.21.1/

# Build new version
./gradlew clean build

# Extract new sources
bash scripts/setup_reference_sources.sh
```

### Adding New Validation

To add new validation checks:

1. Create new script in `scripts/`
2. Make executable: `chmod +x scripts/new_validation.sh`
3. Add Gradle task in `build.gradle`:
   ```gradle
   task newValidation(type: Exec) {
       dependsOn someTask
       commandLine 'bash', 'scripts/new_validation.sh'
   }
   build.dependsOn newValidation
   ```

## Performance Impact

| Task | Time | Impact |
|---|---|---|
| validateMixins | <1s | Minimal |
| setup_reference_sources | ~10s | One-time |
| Full build with validation | +0.5s | Negligible |

Total build time increase: ~1%

## References

- Main documentation: `/VALIDATION.md`
- Reference sources: `/.reference/README.md`
- CI workflows: `/.github/workflows/`
- NeoForge docs: https://docs.neoforged.net/
