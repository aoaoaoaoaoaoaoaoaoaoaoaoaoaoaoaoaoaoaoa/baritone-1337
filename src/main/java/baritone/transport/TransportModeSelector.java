package baritone.transport;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ElytraLaunchMode;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.process.elytra.ElytraFireworks;
import baritone.process.elytra.ElytraFlightPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class TransportModeSelector {
  private static final double ELYTRA_PROMOTION_DISTANCE = 500;
  private static final int OVERWORLD_AUTOLAUNCH_Y_MARGIN = 16;
  private static final int OPEN_RADIUS = 2;
  private static final int OPEN_HEADROOM = 10;

  private TransportModeSelector() {
  }

  public static Decision select(Baritone baritone, Goal goal) {
    IPlayerContext ctx = baritone.getPlayerContext();
    if (!Baritone.settings().elytraEnabled.value || goal == null || ctx.player() == null || ctx.world() == null || !baritone.getElytraProcess().isLoaded()) {
      return Decision.walk();
    }

    ElytraFlightPolicy policy = ElytraFlightPolicy.capture(ctx.world());
    BlockPos destination = elytraDestination(ctx, goal, policy);
    if (destination == null) {
      return Decision.walk();
    }

    if (!hasFirework(ctx)) {
      return Decision.walk();
    }

    double distance = horizontalDistance(ctx.playerFeet(), destination);
    ElytraLaunchMode launchMode = launchMode(ctx, policy, destination);
    if (distance < ELYTRA_PROMOTION_DISTANCE || !elytraEquipped(ctx) || launchMode == null) {
      return Decision.walk();
    }
    return new Decision.Elytra(destination, distance, launchMode);
  }

  private static BlockPos elytraDestination(IPlayerContext ctx, Goal goal, ElytraFlightPolicy policy) {
    if (goal instanceof GoalXZ xz) {
      return new BlockPos(xz.getX(), policy.targetY(ctx), xz.getZ());
    }
    if (goal instanceof GoalBlock block && policy.validGoalY(block.y)) {
      return new BlockPos(block.x, block.y, block.z);
    }
    return null;
  }

  private static double horizontalDistance(BetterBlockPos from, BlockPos to) {
    return Math.hypot((double) from.x - to.getX(), (double) from.z - to.getZ());
  }

  private static boolean elytraEquipped(IPlayerContext ctx) {
    ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
    return chest.getItem() == Items.ELYTRA && chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value;
  }

  private static ElytraLaunchMode launchMode(IPlayerContext ctx, ElytraFlightPolicy policy, BlockPos destination) {
    if (ctx.player().isFallFlying()) {
      return ElytraLaunchMode.MANUAL;
    }
    if (!ctx.player().onGround() && ctx.player().getDeltaMovement().y < -0.1) {
      return ElytraLaunchMode.MANUAL;
    }
    if (openFlatLaunch(ctx, destination)) {
      return ElytraLaunchMode.SPRINT_JUMP;
    }
    return openAutoLaunch(ctx, policy) ? ElytraLaunchMode.AUTO_JUMP : null;
  }

  private static boolean hasFirework(IPlayerContext ctx) {
    return ctx.player().getInventory().getNonEquipmentItems().stream().anyMatch(ElytraFireworks::isPlain);
  }

  private static boolean openAutoLaunch(IPlayerContext ctx, ElytraFlightPolicy policy) {
    BetterBlockPos feet = ctx.playerFeet();
    if (feet.y < policy.autoLaunchY() - (policy.dimension() == Level.NETHER ? 0 : OVERWORLD_AUTOLAUNCH_Y_MARGIN)) {
      return false;
    }
    if (policy.dimension() != Level.NETHER && !ctx.world().canSeeSky(feet.above(2))) {
      return false;
    }
    return clearVolume(ctx, feet.x, feet.y, feet.z, OPEN_RADIUS, OPEN_HEADROOM);
  }

  private static boolean openFlatLaunch(IPlayerContext ctx, BlockPos destination) {
    BetterBlockPos feet = ctx.playerFeet();
    if (!ctx.player().onGround() || !ctx.world().canSeeSky(feet.above(2)) || !clearVolume(ctx, feet.x, feet.y, feet.z, 1, OPEN_HEADROOM)) {
      return false;
    }
    Vec3 delta = Vec3.atCenterOf(destination).subtract(ctx.playerFeetAsVec()).multiply(1, 0, 1);
    return delta.lengthSqr() >= 1 && ctx.world().getWorldBorder().isWithinBounds(feet);
  }

  private static boolean clearVolume(IPlayerContext ctx, int x, int y, int z, int radius, int headroom) {
    MutableBlockPos cursor = new MutableBlockPos();
    for (int dy = 0; dy <= headroom; dy++) {
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          cursor.set(x + dx, y + dy, z + dz);
          if (!ctx.world().getBlockState(cursor).getCollisionShape(ctx.world(), cursor).isEmpty()) {
            return false;
          }
        }
      }
    }
    return true;
  }

  public sealed interface Decision permits Decision.Walk, Decision.Elytra {
    TransportKind kind();

    static Walk walk() {
      return Walk.INSTANCE;
    }

    record Walk() implements Decision {
      private static final Walk INSTANCE = new Walk();

      @Override
      public TransportKind kind() {
        return TransportKind.WALK;
      }
    }

    record Elytra(BlockPos destination, double horizontalDistance, ElytraLaunchMode launchMode) implements Decision {
      @Override
      public TransportKind kind() {
        return TransportKind.ELYTRA;
      }
    }
  }
}
