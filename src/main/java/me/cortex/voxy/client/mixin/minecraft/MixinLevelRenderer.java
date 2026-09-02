package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LevelRenderer.class})
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
   @Shadow
   @Nullable
   private ClientLevel level;
   @Unique
   private VoxyRenderSystem renderer;

   @Override
   public VoxyRenderSystem getVoxyRenderSystem() {
      return this.renderer;
   }

   @Inject(
      method = {"allChanged()V"},
      at = {@At("RETURN")},
      order = 900
   )
   private void reloadVoxyRenderer(CallbackInfo ci) {
      this.shutdownRenderer();
      if (this.level != null) {
         this.createRenderer();
      }
   }

   @Inject(
      method = {"setLevel"},
      at = {@At("HEAD")}
   )
   private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
      if (this.level != world) {
         this.shutdownRenderer();
      }
   }

   @Inject(
      method = {"close"},
      at = {@At("HEAD")}
   )
   private void injectClose(CallbackInfo ci) {
      this.shutdownRenderer();
   }

   @Override
   public void shutdownRenderer() {
      if (this.renderer != null) {
         this.renderer.shutdown();
         this.renderer = null;
      }
   }

   @Override
   public void createRenderer() {
      if (this.renderer != null) {
         throw new IllegalStateException("Cannot have multiple renderers");
      } else if (!VoxyConfig.CONFIG.enabled) {
         Logger.info("Not creating renderer due to disabled");
      } else if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
         Logger.info("Not creating renderer due to disabled rendering");
      } else if (this.level == null) {
         Logger.error("Not creating renderer due to null world");
      } else {
         VoxyClientInstance instance = (VoxyClientInstance)VoxyCommon.getInstance();
         if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
         } else {
            WorldEngine world = WorldIdentifier.ofEngine(this.level);
            if (world == null) {
               Logger.error("Null world selected");
            } else {
               try {
                  this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
               } catch (RuntimeException var4) {
                  if (!IrisUtil.irisShaderPackEnabled()) {
                     throw var4;
                  }

                  Logger.error("Voxy Iris pipeline creation failed; disabling shaders", var4);
                  IrisUtil.disableIrisShaders();
               }

               instance.updateDedicatedThreads();
            }
         }
      }
   }
}
