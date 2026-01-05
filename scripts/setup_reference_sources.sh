#!/bin/bash
set -e

echo "=== MINECRAFT REFERENCE SOURCES SETUP ==="
echo ""

# Create reference directory structure
mkdir -p .reference/minecraft/1.21.1

# Find NeoForm cache directory with sources
NEOFORM_CACHE=$(find ~/.gradle/caches/neoform -type d -name "sources" 2>/dev/null | grep "1.21.1" | head -1)

if [ -z "$NEOFORM_CACHE" ]; then
    echo "ERROR: Minecraft 1.21.1 decompiled sources not found in Gradle cache"
    echo ""
    echo "Run './gradlew build' first to trigger NeoForm decompilation"
    echo "The sources will be cached in ~/.gradle/caches/neoform/"
    exit 1
fi

SOURCE_DIR=$(dirname "$NEOFORM_CACHE")/sources
PARENT_DIR=$(dirname "$SOURCE_DIR")

echo "Found NeoForm cache: $(basename $PARENT_DIR)"
echo "Source directory: $SOURCE_DIR"
echo ""

# Count source files
FILE_COUNT=$(find "$SOURCE_DIR" -name "*.java" 2>/dev/null | wc -l | tr -d ' ')
echo "Java source files: $FILE_COUNT"

# Calculate size
SIZE=$(du -sh "$SOURCE_DIR" 2>/dev/null | cut -f1)
echo "Total size: $SIZE"
echo ""

# Copy sources to reference directory
echo "Copying sources to .reference/minecraft/1.21.1/decompiled..."
rm -rf .reference/minecraft/1.21.1/decompiled
cp -r "$SOURCE_DIR" .reference/minecraft/1.21.1/decompiled

if [ $? -eq 0 ]; then
    echo "✓ Sources copied successfully"
    echo ""

    # Verify critical classes exist
    echo "Verifying critical Minecraft classes..."

    CRITICAL_CLASSES=(
        "net/minecraft/util/thread/BlockableEventLoop.java"
        "net/minecraft/client/Minecraft.java"
        "net/minecraft/client/renderer/LevelRenderer.java"
        "net/minecraft/world/level/chunk/LevelChunk.java"
    )

    for class_path in "${CRITICAL_CLASSES[@]}"; do
        if [ -f ".reference/minecraft/1.21.1/decompiled/$class_path" ]; then
            echo "  ✓ $class_path"
        else
            echo "  ✗ $class_path (NOT FOUND)"
        fi
    done

    echo ""
    echo "=== SETUP COMPLETE ==="
    echo ""
    echo "Usage examples:"
    echo "  # Search for a method:"
    echo "  grep -r 'isNonRecoverable' .reference/minecraft/1.21.1/decompiled/"
    echo ""
    echo "  # Find a class:"
    echo "  find .reference/minecraft/1.21.1/decompiled -name 'BlockableEventLoop.java'"
    echo ""
    echo "  # View a class:"
    echo "  cat .reference/minecraft/1.21.1/decompiled/net/minecraft/util/thread/BlockableEventLoop.java"

else
    echo "✗ Failed to copy sources"
    exit 1
fi
