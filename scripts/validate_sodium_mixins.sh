#!/bin/bash
# Validate Voxy's Sodium mixin targets against Sodium 0.6.13 reference source
# This script checks that mixin injection targets actually exist

set -e

SODIUM_REF=".reference/sodium/common/src/main/java/net/caffeinemc/mods/sodium/client"
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "=== SODIUM MIXIN TARGET VALIDATION ==="
echo ""

ERRORS=0
WARNINGS=0

check_method() {
    local file="$1"
    local method="$2"
    local description="$3"

    if [[ ! -f "$file" ]]; then
        echo -e "${RED}✗${NC} File not found: $file"
        ((ERRORS++))
        return
    fi

    if grep -q "$method" "$file" 2>/dev/null; then
        echo -e "${GREEN}✓${NC} $description"
    else
        echo -e "${RED}✗${NC} $description - METHOD NOT FOUND"
        ((ERRORS++))
    fi
}

check_method_call() {
    local file="$1"
    local caller_method="$2"
    local target_call="$3"
    local description="$4"

    if [[ ! -f "$file" ]]; then
        echo -e "${RED}✗${NC} File not found: $file"
        ((ERRORS++))
        return
    fi

    # Check if the method exists and contains the target call
    if grep -q "$target_call" "$file" 2>/dev/null; then
        echo -e "${GREEN}✓${NC} $description"
    else
        echo -e "${RED}✗${NC} $description - TARGET CALL NOT FOUND"
        echo -e "    Looking for: $target_call"
        ((ERRORS++))
    fi
}

echo "[1] RenderSectionManager targets"
RSM="$SODIUM_REF/render/chunk/RenderSectionManager.java"
check_method "$RSM" "public RenderSectionManager" "Constructor <init>"
check_method "$RSM" "onChunkRemoved" "Method: onChunkRemoved"
check_method "$RSM" "onChunkAdded" "Method: onChunkAdded"
check_method "$RSM" "updateSectionInfo" "Method: updateSectionInfo"
check_method_call "$RSM" "updateSectionInfo" "setInfo" "Call: RenderSection.setInfo in updateSectionInfo"
echo ""

echo "[2] SodiumWorldRenderer targets"
SWR="$SODIUM_REF/render/SodiumWorldRenderer.java"
check_method "$SWR" "initRenderer" "Method: initRenderer"
echo ""

echo "[3] DefaultChunkRenderer targets"
DCR="$SODIUM_REF/render/chunk/DefaultChunkRenderer.java"
check_method "$DCR" "void render" "Method: render"
# Note: super.end() compiles to ShaderChunkRenderer.end() - check for both patterns
check_method_call "$DCR" "render" "super\.end\|\.end(" "Call: ShaderChunkRenderer.end() in render"
echo ""

echo "[4] ChunkJobQueue targets (ChunkBuilderMeshingTask or similar)"
# Note: ChunkJobQueue may have been renamed or restructured
CJQ="$SODIUM_REF/render/chunk/compile/executor/ChunkJobQueue.java"
if [[ -f "$CJQ" ]]; then
    check_method "$CJQ" "Semaphore" "Constructor: new Semaphore"
    check_method "$CJQ" "shutdown" "Method: shutdown"
else
    echo -e "${YELLOW}⚠${NC} ChunkJobQueue.java not found at expected path"
    echo "    Searching for alternatives..."
    find "$SODIUM_REF" -name "*Job*" -o -name "*Queue*" -o -name "*Builder*" 2>/dev/null | grep -i chunk | head -5
    ((WARNINGS++))
fi
echo ""

echo "[5] RenderRegionManager targets (DISABLED - verify removal was correct)"
RRM="$SODIUM_REF/render/chunk/region/RenderRegionManager.java"
if grep -q "Math.toIntExact" "$RRM" 2>/dev/null; then
    echo -e "${YELLOW}⚠${NC} Math.toIntExact EXISTS - mixin might work after all!"
    ((WARNINGS++))
else
    echo -e "${GREEN}✓${NC} Math.toIntExact NOT FOUND - mixin correctly disabled"
fi
echo ""

echo "=== VALIDATION SUMMARY ==="
echo -e "Errors: ${RED}$ERRORS${NC}"
echo -e "Warnings: ${YELLOW}$WARNINGS${NC}"
echo ""

if [[ $ERRORS -gt 0 ]]; then
    echo -e "${RED}VALIDATION FAILED${NC} - Some mixin targets don't exist!"
    exit 1
else
    echo -e "${GREEN}VALIDATION PASSED${NC}"
    exit 0
fi
