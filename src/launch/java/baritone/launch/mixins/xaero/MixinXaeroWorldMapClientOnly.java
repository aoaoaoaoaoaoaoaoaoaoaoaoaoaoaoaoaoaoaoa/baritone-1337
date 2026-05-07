package baritone.launch.mixins.xaero;

import baritone.launch.xaero.BaritoneXaeroOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "xaero.map.WorldMapClientOnly", remap = false)
abstract class MixinXaeroWorldMapClientOnly {

  @Inject(method = "loadLaterClientRender", at = @At("TAIL"), remap = false)
  private void installBaritoneMapOverlay(CallbackInfo ci) {
    BaritoneXaeroOverlay.install();
  }
}
