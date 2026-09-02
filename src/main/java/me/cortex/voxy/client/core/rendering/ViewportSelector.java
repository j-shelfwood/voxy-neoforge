package me.cortex.voxy.client.core.rendering;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.neoforged.fml.ModList;

public class ViewportSelector<T extends Viewport<?>> {
   public static final boolean VIVECRAFT_INSTALLED = ModList.get() != null && ModList.get().isLoaded("vivecraft");
   private final Supplier<T> creator;
   private final T defaultViewport;
   private final Map<Object, T> extraViewports = new HashMap<>();
   private static final Object IRIS_SHADOW_OBJECT = new Object();

   public ViewportSelector(Supplier<T> viewportCreator) {
      this.creator = viewportCreator;
      this.defaultViewport = viewportCreator.get();
   }

   private T getOrCreate(Object holder) {
      return this.extraViewports.computeIfAbsent(holder, a -> this.creator.get());
   }

   public T getViewport() {
      return this.defaultViewport;
   }

   public T getShadowViewport() {
      return this.getOrCreate(IRIS_SHADOW_OBJECT);
   }

   public void free() {
      this.defaultViewport.delete();
      this.extraViewports.values().forEach(Viewport::delete);
      this.extraViewports.clear();
   }
}
