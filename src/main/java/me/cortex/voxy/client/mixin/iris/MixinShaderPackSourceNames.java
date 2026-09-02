package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList.Builder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(
   value = {ShaderPackSourceNames.class},
   remap = false
)
public class MixinShaderPackSourceNames {
   @WrapOperation(
      method = {"findPotentialStarts"},
      at = {@At(
         value = "INVOKE",
         target = "Lcom/google/common/collect/ImmutableList;builder()Lcom/google/common/collect/ImmutableList$Builder;"
      )}
   )
   private static Builder<String> voxy$injectVoxyShaderPatch(Operation<Builder<String>> original) {
      Builder<String> builder = (Builder<String>)original.call(new Object[0]);
      builder.add("voxy.json");
      builder.add("voxy_opaque.glsl");
      builder.add("voxy_translucent.glsl");
      return builder;
   }
}
