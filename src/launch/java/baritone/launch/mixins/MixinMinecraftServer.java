package baritone.launch.mixins;

import baritone.oracle.AStarOracleHarness;
import baritone.playtest.physics.HorsePhysicsHarness;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public final class MixinMinecraftServer {
  @Inject(method = "tickServer", at = @At("RETURN"))
  private void baritone$tickPhysicsHarness(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
    HorsePhysicsHarness.tick((MinecraftServer) (Object) this);
    AStarOracleHarness.tick((MinecraftServer) (Object) this);
  }
}
