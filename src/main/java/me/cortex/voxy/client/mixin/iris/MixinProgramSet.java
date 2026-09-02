package me.cortex.voxy.client.mixin.iris;

import java.util.function.Function;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {ProgramSet.class},
   remap = false
)
public class MixinProgramSet implements IGetVoxyPatchData {
   @Shadow
   @Final
   private PackDirectives packDirectives;
   @Unique
   IrisShaderPatch patchData;

   @Inject(
      method = {"<init>"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;locateDirectives()V",
         shift = Shift.BEFORE
      )}
   )
   private void voxy$injectPatchMaker(
      AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider, ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci
   ) {
      if (VoxyConfig.CONFIG.isRenderingEnabled()) {
         this.patchData = IrisShaderPatch.makePatch(pack, directory, sourceProvider);
      }
   }

   @Override
   public IrisShaderPatch voxy$getPatchData() {
      return this.patchData;
   }
}
