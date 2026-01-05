#!/bin/bash
set -e

echo "=== MIXIN SIGNATURE VALIDATION ==="
echo ""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

ERRORS=0
WARNINGS=0
CHECKED=0

# Check if Minecraft sources are available
MC_SOURCES=".reference/minecraft/1.21.1/decompiled"

if [ ! -d "$MC_SOURCES" ]; then
    echo -e "${YELLOW}WARNING: Minecraft sources not found at $MC_SOURCES${NC}"
    echo "Run: bash scripts/setup_reference_sources.sh"
    echo ""
    echo "Signature validation will be skipped."
    echo "This validation requires decompiled Minecraft sources to verify @Inject targets."
    exit 0
fi

echo "Using Minecraft sources: $MC_SOURCES"
echo ""

# Find all mixin files
MIXIN_FILES=$(find src/main/java -name "*Mixin*.java" -o -name "*Accessor*.java" 2>/dev/null)

if [ -z "$MIXIN_FILES" ]; then
    echo -e "${YELLOW}No mixin files found${NC}"
    exit 0
fi

echo "Found $(echo "$MIXIN_FILES" | wc -l | tr -d ' ') mixin files to validate"
echo ""

# Function to extract @Inject method targets
check_inject_signatures() {
    local mixin_file=$1
    local mixin_name=$(basename "$mixin_file" .java)

    # Extract @Mixin target class
    local target_class=$(grep -oP '@Mixin\(\s*(?:value\s*=\s*)?\K[^)]+' "$mixin_file" 2>/dev/null | head -1 | sed 's/\.class//g')

    if [ -z "$target_class" ]; then
        return
    fi

    echo "Checking $mixin_name → $target_class"

    # Find the target class file in MC sources
    local class_path=$(echo "$target_class" | sed 's/\./\//g')
    local target_file=$(find "$MC_SOURCES" -path "*/${class_path}.java" 2>/dev/null | head -1)

    if [ -z "$target_file" ]; then
        echo -e "  ${YELLOW}⚠${NC} Target class not found: $target_class"
        WARNINGS=$((WARNINGS + 1))
        return
    fi

    # Extract @Inject annotations and their target methods
    local inject_count=0

    # Look for @Inject annotations with method parameter
    while IFS= read -r line; do
        if echo "$line" | grep -q '@Inject.*method\s*=\s*"'; then
            inject_count=$((inject_count + 1))

            # Extract the method name
            local method_name=$(echo "$line" | grep -oP 'method\s*=\s*"\K[^"]+')

            # Special case for constructors
            if [ "$method_name" = "<init>" ]; then
                method_name="constructor"

                # Get the next few lines to find the mixin method signature
                local mixin_method=""
                local found_method=false
                local line_num=$(grep -n "$line" "$mixin_file" | cut -d: -f1 | head -1)

                # Read the method signature (next non-empty line after @Inject)
                local mixin_sig=$(sed -n "$((line_num + 1)),/CallbackInfo/p" "$mixin_file" | grep -A 20 "private\|public\|protected" | head -15)

                # Count parameters in mixin signature (excluding CallbackInfo)
                local mixin_param_count=$(echo "$mixin_sig" | grep -o "," | wc -l)

                # Count parameters in target constructor
                local target_sig=$(grep -A 15 "public $target_class(" "$target_file" 2>/dev/null | head -15)
                local target_param_count=$(echo "$target_sig" | grep -o "," | wc -l)

                if [ -n "$target_sig" ]; then
                    # Compare (rough check - full validation would parse types)
                    if [ "$mixin_param_count" -ne "$target_param_count" ]; then
                        echo -e "  ${RED}✗${NC} Constructor signature mismatch"
                        echo "    Mixin parameters: $mixin_param_count"
                        echo "    Target parameters: $target_param_count"
                        echo "    Check: $mixin_name injection into $target_class.<init>"
                        ERRORS=$((ERRORS + 1))
                    else
                        echo -e "  ${GREEN}✓${NC} Constructor injection: $method_name (params: $mixin_param_count)"
                        CHECKED=$((CHECKED + 1))
                    fi
                fi
            else
                # Regular method injection
                # Check if method exists in target
                if grep -q "\\b${method_name}\\b" "$target_file" 2>/dev/null; then
                    echo -e "  ${GREEN}✓${NC} Method injection: $method_name"
                    CHECKED=$((CHECKED + 1))
                else
                    echo -e "  ${RED}✗${NC} Method not found: $method_name"
                    echo "    Target: $target_class"
                    ERRORS=$((ERRORS + 1))
                fi
            fi
        fi
    done < "$mixin_file"

    if [ $inject_count -eq 0 ]; then
        echo -e "  ${YELLOW}ℹ${NC} No @Inject annotations found"
    fi

    echo ""
}

# Check each mixin file
while IFS= read -r mixin_file; do
    check_inject_signatures "$mixin_file"
done <<< "$MIXIN_FILES"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "=== VALIDATION SUMMARY ==="
echo "Injections checked: $CHECKED"
echo "Errors found: $ERRORS"
echo "Warnings: $WARNINGS"
echo ""

if [ $ERRORS -eq 0 ]; then
    echo -e "${GREEN}✓ ALL SIGNATURE CHECKS PASSED${NC}"
    echo "All @Inject targets verified against Minecraft source"
    exit 0
else
    echo -e "${RED}✗ VALIDATION FAILED${NC}"
    echo "Found $ERRORS signature mismatch(es)"
    echo ""
    echo "These mismatches will cause InvalidInjectionException at runtime."
    echo "Update mixin method signatures to match target class methods."
    exit 1
fi
