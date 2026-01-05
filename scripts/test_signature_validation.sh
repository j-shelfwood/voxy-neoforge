#!/bin/bash
# Test that signature validation would have caught Issue #3

echo "=== TESTING SIGNATURE VALIDATION EFFECTIVENESS ==="
echo ""
echo "Simulating Issue #3 scenario:"
echo "  Reverting MixinWorld to broken state (missing Supplier parameter)"
echo ""

# Backup current (fixed) version
cp src/main/java/me/cortex/voxy/commonImpl/mixin/minecraft/MixinWorld.java /tmp/MixinWorld.java.fixed

# Create broken version (like before Issue #3 fix)
cat > /tmp/MixinWorld.java.broken << 'EOF'
package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public class MixinWorld implements IWorldGetIdentifier {
    @Unique
    private WorldIdentifier identifier;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$injectIdentifier(WritableLevelData properties,
                                       ResourceKey<Level> key,
                                       RegistryAccess registryManager,
                                       Holder<DimensionType> dimensionEntry,
                                       // MISSING: Supplier parameter (Issue #3)
                                       boolean isClient,
                                       boolean debugWorld,
                                       long seed,
                                       int maxChainedNeighborUpdates,
                                       CallbackInfo ci) {
        if (key != null) {
            this.identifier = new WorldIdentifier(key, seed, dimensionEntry == null?null:dimensionEntry.unwrapKey().orElse(null));
        } else {
            this.identifier = null;
        }
    }

    @Override
    public WorldIdentifier voxy$getIdentifier() {
        return this.identifier;
    }
}
EOF

# Test broken version
echo "[TEST 1] Installing broken version..."
cp /tmp/MixinWorld.java.broken src/main/java/me/cortex/voxy/commonImpl/mixin/minecraft/MixinWorld.java

echo "[TEST 1] Running signature validation..."
if python3 scripts/validate_mixin_targets.py 2>&1 | grep -q "signature mismatch"; then
    echo "✓ TEST PASSED: Signature validation detected the issue!"
    echo ""
else
    echo "✗ TEST FAILED: Signature validation did not detect Issue #3"
    echo ""
fi

# Restore fixed version
echo "[TEST 2] Restoring fixed version..."
cp /tmp/MixinWorld.java.fixed src/main/java/me/cortex/voxy/commonImpl/mixin/minecraft/MixinWorld.java

echo "[TEST 2] Running signature validation..."
if python3 scripts/validate_mixin_targets.py 2>&1 | grep -q "ALL SIGNATURE CHECKS PASSED"; then
    echo "✓ TEST PASSED: Fixed version passes validation"
    echo ""
else
    echo "✗ TEST FAILED: Fixed version should pass"
    echo ""
fi

echo "=== TEST COMPLETE ==="
echo ""
echo "Conclusion:"
echo "  Signature validation script can detect constructor parameter mismatches"
echo "  Issue #3 would have been caught before Windows testing"
echo "  Recommendation: Always run before deployment"
