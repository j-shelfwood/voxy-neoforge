package me.cortex.voxy.client.mixin.monocle.embeddium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.embeddedt.embeddium.impl.gl.attribute.GlVertexAttribute;
import org.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeFormat;
import org.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = GlVertexFormat.Builder.class, remap = false)
public class MixinGlVertexFormatBuilder {
    private final GlVertexAttribute voxy$dummyAttribute =
        new GlVertexAttribute(GlVertexAttributeFormat.UNSIGNED_BYTE, 0, false, 0, 0, false);

    @ModifyVariable(method = "build", at = @At("LOAD"))
    private GlVertexAttribute voxy$putDummy(GlVertexAttribute value) {
        return value == null ? voxy$dummyAttribute : value;
    }

    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
    private int voxy$skipDummyStride(int a, int b, Operation<Integer> original, @Local GlVertexAttribute attribute) {
        if (attribute == voxy$dummyAttribute) {
            return a;
        }
        return original.call(a, b);
    }
}
