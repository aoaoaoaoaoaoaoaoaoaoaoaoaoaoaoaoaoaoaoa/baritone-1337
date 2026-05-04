package baritone.planning;

import net.minecraft.client.player.LocalPlayer;

public record PlayerVitals(int air, int maxAir, int food, float health, boolean onGround, boolean sprinting, boolean crouching, boolean swimming, boolean fallFlying) {
  public static PlayerVitals capture(LocalPlayer player) {
    return new PlayerVitals(player.getAirSupply(), player.getMaxAirSupply(), player.getFoodData().getFoodLevel(), player.getHealth(), player.onGround(), player.isSprinting(), player.isCrouching(),
      player.isSwimming(), player.isFallFlying());
  }
}
