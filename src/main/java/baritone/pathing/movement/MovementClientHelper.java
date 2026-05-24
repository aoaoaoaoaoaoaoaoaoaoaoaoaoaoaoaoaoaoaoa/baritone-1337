package baritone.pathing.movement;

import static baritone.api.utils.RotationUtils.DEG_TO_RAD_F;
import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.control.ControlFrame.MovementTarget;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class MovementClientHelper {
  private MovementClientHelper() {
  }

  public static boolean canWalkThrough(IPlayerContext ctx, BetterBlockPos pos) {
    return MovementHelper.canWalkThrough(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
  }

  public static boolean fullyPassable(IPlayerContext ctx, BlockPos pos) {
    BlockState state = ctx.world().getBlockState(pos);
    StateAffordance fullyPassable = MovementHelper.fullyPassableBlockState(state);
    if (fullyPassable == StateAffordance.YES) {
      return true;
    }
    if (fullyPassable == StateAffordance.NO) {
      return false;
    }
    return state.isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND);
  }

  public static boolean isDoorPassable(IPlayerContext ctx, BlockPos doorPos, BlockPos playerPos) {
    if (playerPos.equals(doorPos)) {
      return false;
    }
    BlockState state = BlockStateInterface.get(ctx, doorPos);
    if (!(state.getBlock() instanceof DoorBlock)) {
      return true;
    }
    return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
  }

  public static boolean isGatePassable(IPlayerContext ctx, BlockPos gatePos, BlockPos playerPos) {
    if (playerPos.equals(gatePos)) {
      return false;
    }
    BlockState state = BlockStateInterface.get(ctx, gatePos);
    if (!(state.getBlock() instanceof FenceGateBlock)) {
      return true;
    }
    return state.getValue(FenceGateBlock.OPEN);
  }

  private static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState, BlockPos playerPos, BooleanProperty propertyOpen) {
    if (playerPos.equals(blockPos)) {
      return false;
    }
    Direction.Axis facing = blockState.getValue(HorizontalDirectionalBlock.FACING).getAxis();
    boolean open = blockState.getValue(propertyOpen);
    Direction.Axis playerFacing;
    if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
      playerFacing = Direction.Axis.Z;
    } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
      playerFacing = Direction.Axis.X;
    } else {
      return true;
    }
    return (facing == playerFacing) == open;
  }

  public static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos, BlockState state) {
    return MovementHelper.canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z, state);
  }

  public static boolean canWalkOn(IPlayerContext ctx, BlockPos pos) {
    return MovementHelper.canWalkOn(new BlockStateInterface(ctx), pos.getX(), pos.getY(), pos.getZ());
  }

  public static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos) {
    return MovementHelper.canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
  }

  public static boolean canUseFrostWalker(IPlayerContext ctx, BlockPos pos) {
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      ItemEnchantments itemEnchantments = ctx.player().getItemBySlot(slot).getEnchantments();
      for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
        if (enchant.is(Enchantments.FROST_WALKER) && itemEnchantments.getLevel(enchant) > 0) {
          BlockState state = BlockStateInterface.get(ctx, pos);
          return MovementHelper.isWater(state) && !MovementHelper.isFlowing(pos.getX(), pos.getY(), pos.getZ(), state, new BlockStateInterface(ctx));
        }
      }
    }
    return false;
  }

  public static boolean canPlaceAgainst(IPlayerContext ctx, BlockPos pos) {
    return MovementHelper.canPlaceAgainst(new BlockStateInterface(ctx), pos);
  }

  public static boolean canStrideUpStair(IPlayerContext ctx, BlockPos support, BlockState state, Direction movement) {
    if (!(state.getBlock() instanceof StairBlock) || state.getValue(StairBlock.HALF) != Half.BOTTOM) {
      return false;
    }
    return collisionHeightAtEntry(ctx, support, state, movement) <= 0.5625D;
  }

  private static double collisionHeightAtEntry(IPlayerContext ctx, BlockPos support, BlockState state, Direction movement) {
    double x = switch (movement) {
      case EAST -> 0.0625D;
      case WEST -> 0.9375D;
      default -> 0.5D;
    };
    double z = switch (movement) {
      case SOUTH -> 0.0625D;
      case NORTH -> 0.9375D;
      default -> 0.5D;
    };
    double height = 0D;
    for (AABB box : state.getCollisionShape(ctx.world(), support).toAabbs()) {
      if (x >= box.minX - 1.0E-7D && x <= box.maxX + 1.0E-7D && z >= box.minZ - 1.0E-7D && z <= box.maxZ + 1.0E-7D) {
        height = Math.max(height, box.maxY);
      }
    }
    return height;
  }

  public static void switchToBestToolFor(ControlFrame.Builder state, IPlayerContext ctx, BlockState b) {
    bestToolSlot(ctx, b).ifPresent(state::selectHotbarSlot);
  }

  public static void switchToBestToolFor(ControlFrame.Builder state, IPlayerContext ctx, BlockState b, ToolSet ts, boolean preferSilkTouch) {
    MovementHelper.bestToolSlot(b, ts, preferSilkTouch).ifPresent(state::selectHotbarSlot);
  }

  public static OptionalInt bestToolSlot(IPlayerContext ctx, BlockState b) {
    return MovementHelper.bestToolSlot(b, new ToolSet(ctx.player()), BaritoneAPI.getSettings().preferSilkTouch.value);
  }

  public static void moveTowards(IPlayerContext ctx, ControlFrame.Builder state, BlockPos pos) {
    state
      .setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(pos), ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()), false))
      .setInput(Input.MOVE_FORWARD, true);
  }

  public static void moveTowardsWithoutRotation(IPlayerContext ctx, ControlFrame.Builder state, float idealYaw) {
    MovementOption.getOptions(Mth.sin(ctx.playerRotations().getYaw() * DEG_TO_RAD_F), Mth.cos(ctx.playerRotations().getYaw() * DEG_TO_RAD_F), BaritoneAPI.getSettings().allowSprint.value)
      .min(Comparator.comparing(option -> option.distanceToSq(Mth.sin(idealYaw * DEG_TO_RAD_F), Mth.cos(idealYaw * DEG_TO_RAD_F)))).ifPresent(selection -> selection.setInputs(state));
  }

  public static void moveTowardsWithoutRotation(IPlayerContext ctx, ControlFrame.Builder state, BlockPos dest) {
    float idealYaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getYaw();
    moveTowardsWithoutRotation(ctx, state, idealYaw);
  }

  public static void moveTowardsWithSlightRotation(IPlayerContext ctx, ControlFrame.Builder state, BlockPos dest) {
    float idealYaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getYaw();
    float distance = Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), idealYaw) % 45f;
    float newYaw = distance > 0f ? distance > 22.5f ? distance - 45f : distance : distance < -22.5f ? distance + 45f : distance;
    state.setTarget(new MovementTarget(new Rotation(ctx.playerRotations().getYaw() - newYaw, ctx.playerRotations().getPitch()), true));
    moveTowardsWithoutRotation(ctx, state, idealYaw);
  }

  public static boolean isWater(IPlayerContext ctx, BlockPos bp) {
    return MovementHelper.isWater(BlockStateInterface.get(ctx, bp));
  }

  public static boolean isDeepWater(IPlayerContext ctx, BlockPos bp) {
    return isWater(ctx, bp) && (isWater(ctx, bp.below()) || isWater(ctx, bp.above()));
  }

  public static boolean surfaceSwimCell(IPlayerContext ctx, BlockPos pos) {
    return isWater(ctx, pos) && isWater(ctx, pos.below()) && !isWater(ctx, pos.above());
  }

  public static boolean surfaceSwimEnvelopeCell(IPlayerContext ctx, BlockPos pos) {
    return isWater(ctx, pos) && (isWater(ctx, pos.below()) && !isWater(ctx, pos.above()) || isWater(ctx, pos.above()) && !isWater(ctx, pos.above(2)));
  }

  public static boolean canMoveThrough(IPlayerContext ctx, BlockPos pos) {
    BlockStateInterface bsi = new BlockStateInterface(ctx);
    BlockState state = bsi.get0(pos.getX(), pos.getY(), pos.getZ());
    return MovementHelper.isWater(state) ? MovementHelper.canSwimThrough(state) : MovementHelper.canWalkThrough(bsi, pos.getX(), pos.getY(), pos.getZ(), state);
  }

  public static boolean isLiquid(IPlayerContext ctx, BlockPos p) {
    return MovementHelper.isLiquid(BlockStateInterface.get(ctx, p));
  }

  public static MovementHelper.PlaceResult attemptToPlaceABlock(ControlFrame.Builder state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
    IPlayerContext ctx = baritone.getPlayerContext();
    Optional<Rotation> direct = RotationUtils.reachable(ctx, placeAt, wouldSneak);
    boolean found = false;
    if (direct.isPresent()) {
      state.setTarget(new MovementTarget(direct.get(), true));
      found = true;
    }
    for (int i = 0; i < 5; i++) {
      BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
      if (canPlaceAgainst(ctx, against1)) {
        if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(false, placeAt.getX(), placeAt.getY(), placeAt.getZ())) {
          System.out.println("[Baritone] bb pls get me some blocks. dirt, netherrack, cobble");
          state.setStatus(MovementStatus.UNREACHABLE);
          return MovementHelper.PlaceResult.NO_OPTION;
        }
        double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
        double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
        double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
        Rotation place =
          RotationUtils.calcRotationFromVec3d(wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
        Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(place);
        HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), actual, ctx.playerController().getBlockReachDistance(), wouldSneak);
        if (res != null && res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1)
          && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
          state.setTarget(new MovementTarget(place, true));
          found = true;
          if (!preferDown) {
            break;
          }
        }
      }
    }
    if (ctx.getSelectedBlock().isPresent()) {
      BlockPos selectedBlock = ctx.getSelectedBlock().get();
      Direction side = ((BlockHitResult) ctx.objectMouseOver()).getDirection();
      if (selectedBlock.equals(placeAt) || (canPlaceAgainst(ctx, selectedBlock) && selectedBlock.relative(side).equals(placeAt))) {
        if (wouldSneak) {
          state.setInput(Input.SNEAK, true);
        }
        ((Baritone) baritone).getInventoryBehavior().findThrowawayHotbarSlotForLocation(placeAt.getX(), placeAt.getY(), placeAt.getZ()).ifPresent(state::selectHotbarSlot);
        return MovementHelper.PlaceResult.READY_TO_PLACE;
      }
    }
    if (found) {
      if (wouldSneak) {
        state.setInput(Input.SNEAK, true);
      }
      ((Baritone) baritone).getInventoryBehavior().findThrowawayHotbarSlotForLocation(placeAt.getX(), placeAt.getY(), placeAt.getZ()).ifPresent(state::selectHotbarSlot);
      return MovementHelper.PlaceResult.ATTEMPTING;
    }
    return MovementHelper.PlaceResult.NO_OPTION;
  }

  public static List<BetterBlockPos> steppingOnBlocks(IPlayerContext ctx) {
    List<BetterBlockPos> blocks = new ArrayList<>();
    for (byte x = -1; x <= 1; x++) {
      for (byte z = -1; z <= 1; z++) {
        if (ctx.player().getBoundingBox().intersects(Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x, 0, z), Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x + 1, 1, z + 1))) {
          blocks.add(new BetterBlockPos(ctx.player().getBlockX() + x, ctx.player().getBlockY() - 1, ctx.player().getBlockZ() + z));
        }
      }
    }
    return blocks;
  }
}
