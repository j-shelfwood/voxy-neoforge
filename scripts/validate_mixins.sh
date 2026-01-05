#!/bin/bash
set -e

echo "=== MIXIN CONFIGURATION VALIDATION ==="
echo ""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Find JAR file
JAR_FILE=$(find build/libs -name "*.jar" -type f | grep -v sources | head -1)

if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}ERROR: No JAR file found in build/libs/${NC}"
    echo "Run './gradlew build' first"
    exit 1
fi

echo "Validating JAR: $(basename $JAR_FILE)"
echo ""

ERRORS=0
TOTAL_MIXINS=0

# Function to validate mixin references
validate_mixin_config() {
    local json_file=$1
    local config_name=$(basename $json_file)

    echo "Checking $config_name..."

    # Verify JSON is valid
    if ! jq empty "$json_file" 2>/dev/null; then
        echo -e "${RED}  ✗ Invalid JSON syntax${NC}"
        ERRORS=$((ERRORS + 1))
        return
    fi

    # Extract package prefix
    local package=$(jq -r '.package' "$json_file")

    if [ -z "$package" ] || [ "$package" = "null" ]; then
        echo -e "${RED}  ✗ Missing package declaration${NC}"
        ERRORS=$((ERRORS + 1))
        return
    fi

    # Check each mixin array type
    for array_type in 'mixins' 'client' 'server'; do
        local mixins=$(jq -r ".$array_type[]? // empty" "$json_file" 2>/dev/null)

        if [ -n "$mixins" ]; then
            echo "  Validating .$array_type[]..."

            while IFS= read -r mixin; do
                if [ -n "$mixin" ]; then
                    TOTAL_MIXINS=$((TOTAL_MIXINS + 1))

                    # Convert package.Class to path/Class.class
                    local class_path="${package//./\/}/${mixin//./\/}.class"

                    # Verify exists in JAR
                    if jar tf "$JAR_FILE" 2>/dev/null | grep -q "^$class_path\$"; then
                        echo -e "    ${GREEN}✓${NC} $mixin"
                    else
                        echo -e "    ${RED}✗ $mixin${NC}"
                        echo -e "      ${YELLOW}Expected: $class_path${NC}"
                        echo -e "      ${YELLOW}Status: NOT FOUND IN JAR${NC}"
                        ERRORS=$((ERRORS + 1))
                    fi
                fi
            done <<< "$mixins"
        fi
    done

    echo ""
}

# Find and validate all mixin configuration files
MIXIN_CONFIGS=$(find src/main/resources -name "*.mixins.json" 2>/dev/null)

if [ -z "$MIXIN_CONFIGS" ]; then
    echo -e "${YELLOW}WARNING: No mixin configuration files found${NC}"
    exit 0
fi

while IFS= read -r config_file; do
    validate_mixin_config "$config_file"
done <<< "$MIXIN_CONFIGS"

echo "=== VALIDATION SUMMARY ==="
echo "Total mixins checked: $TOTAL_MIXINS"
echo "JAR file: $(basename $JAR_FILE)"
echo "JAR size: $(du -h $JAR_FILE | cut -f1)"
echo ""

if [ $ERRORS -eq 0 ]; then
    echo -e "${GREEN}✓ ALL CHECKS PASSED${NC}"
    echo "All mixin references are valid and present in JAR"
    exit 0
else
    echo -e "${RED}✗ VALIDATION FAILED${NC}"
    echo "Found $ERRORS phantom mixin reference(s)"
    echo ""
    echo "These mixins are declared in JSON configs but missing from the JAR."
    echo "This will cause ClassNotFoundException at runtime."
    echo ""
    echo "Common causes:"
    echo "  1. Mixin class excluded from compilation (check build.gradle sourceSets)"
    echo "  2. Mixin class deleted but not removed from JSON"
    echo "  3. Typo in mixin class name"
    exit 1
fi
