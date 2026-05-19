package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.macro.portal.PortalFrameSchematic;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

  private static final Set<Property<?>> ORIENTATION_PROPS = ImmutableSet.of(RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING, StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP, TrapDoorBlock.OPEN, TrapDoorBlock.HALF);
  private static final Set<String> ORIENTATION_PROP_NAMES = Set.of("axis", "facing", "horizontal_facing", "orientation", "rotation", "half", "shape", "north", "east", "south", "west", "up", "down");
  private static final Set<String> CONNECTION_PROP_NAMES = Set.of("north", "east", "south", "west", "up");
  private static final int NEAR_ACTION_RADIUS = 5;
  private static final List<ScanOffset> BREAK_SCAN_OFFSETS = scanOffsets(NEAR_ACTION_RADIUS, -1, 5);
  private static final List<ScanOffset> PLACE_SCAN_OFFSETS = scanOffsets(NEAR_ACTION_RADIUS, -5, 3);
  private static final Direction[] PLACE_AGAINST_PRIORITY = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
  private static final Vec3[] AABB_UP_MULTIPLIERS = {new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
  private static final Vec3[] AABB_DOWN_MULTIPLIERS = {new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
  private static final Vec3i NO_REPEAT = new Vec3i(0, 0, 0);

  private HashSet<BetterBlockPos> incorrectPositions;
  private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
  private String name;
  private ISchematic realSchematic;
  private ISchematic schematic;
  private Vec3i origin;
  private int ticks;
  private boolean paused;
  private int layer;
  private int numRepeats;
  private Vec3i repeat;
  private int repeatCount;
  private boolean repeatSneaky;
  private List<BlockState> approxPlaceable;
  public int stopAtHeight = 0;

  public BuilderProcess(Baritone baritone) {
    super(baritone);
  }

  @Override
  public void build(String name, ISchematic schematic, Vec3i origin) {
    build(name, schematic, origin, Baritone.settings().buildRepeat.value, Baritone.settings().buildRepeatCount.value, Baritone.settings().buildRepeatSneaky.value);
  }

  @Override
  public void build(String name, ISchematic schematic, Vec3i origin, Vec3i repeat, int repeatCount, boolean repeatSneaky) {
    this.name = name;
    this.schematic = schematic;
    this.realSchematic = null;
    this.repeat = repeat;
    this.repeatCount = repeatCount;
    this.repeatSneaky = repeatSneaky;
    boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
    if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
      this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
    }
    if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
      this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
    }
    if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
      this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
    }
    this.schematic = new MaskSchematic(this.schematic) {
      private final Set<Block> skippedBlocks = Set.copyOf(Baritone.settings().buildSkipBlocks.value);

      @Override
      public boolean partOfMask(int x, int y, int z, BlockState current) {
        // partOfMask is only called inside the schematic so desiredState is not null
        return !skippedBlocks.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
      }
    };
    int x = origin.getX();
    int y = origin.getY();
    int z = origin.getZ();
    if (Baritone.settings().schematicOrientationX.value) {
      x += schematic.widthX();
    }
    if (Baritone.settings().schematicOrientationY.value) {
      y += schematic.heightY();
    }
    if (Baritone.settings().schematicOrientationZ.value) {
      z += schematic.lengthZ();
    }
    this.origin = new Vec3i(x, y, z);
    this.paused = false;
    this.layer = Baritone.settings().startAtLayer.value;
    this.stopAtHeight = schematic.heightY();
    if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) { // currently redundant but safer maybe
      if (baritone.getSelectionManager().getSelections().length == 0) {
        logDirect("No selection set while buildOnlySelection is true");
        this.stopAtHeight = 0;
      } else if (Baritone.settings().buildInLayers.value) {
        OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
        OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
        if (minim.isPresent() && maxim.isPresent()) {
          int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
          this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
          this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value); // startAtLayer or startAtHeight, whichever is highest
          logDebug("Schematic starts at y=%s with height %s".formatted(y, schematic.heightY()));
          logDebug("Selection starts at y=%s and ends at y=%s".formatted(minim.getAsInt(), maxim.getAsInt()));
          logDebug("Considering relevant height %s - %s".formatted(startAtHeight, this.stopAtHeight));
        }
      }
    }

    this.numRepeats = 0;
    this.observedCompleted = new LongOpenHashSet();
    this.incorrectPositions = null;
    this.approxPlaceable = approxPlaceable(36);
  }

  public void resume() {
    paused = false;
  }

  public void pause() {
    paused = true;
  }

  @Override
  public boolean isPaused() { return paused; }

  @Override
  public boolean build(String name, File schematic, Vec3i origin) {
    Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
    if (format.isEmpty()) {
      return false;
    }
    IStaticSchematic parsed;
    try {
      parsed = format.get().parse(new FileInputStream(schematic));
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
    ISchematic schem = applyMapArtAndSelection(origin, parsed);
    build(name, schem, origin);
    return true;
  }

  private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
    ISchematic schematic = parsed;
    if (Baritone.settings().mapArtMode.value) {
      schematic = new MapArtSchematic(parsed);
    }
    if (Baritone.settings().buildOnlySelection.value) {
      schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
    }
    return schematic;
  }

  public void clearArea(BlockPos corner1, BlockPos corner2) {
    BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
    int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
    int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
    int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
    build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
  }

  @Override
  public void buildPortalFrame(BlockPos lowerLeftInterior, Direction.Axis axis) {
    build("nether portal frame", new PortalFrameSchematic(axis), PortalFrameSchematic.originFor(new BetterBlockPos(lowerLeftInterior), axis));
  }

  @Override
  public List<BlockState> getApproxPlaceable() { return new ArrayList<>(approxPlaceableView()); }

  @Override
  public boolean isActive() { return schematic != null; }

  public BlockState placeAt(int x, int y, int z, BlockState current) {
    if (!isActive()) {
      return null;
    }
    if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
      return null;
    }
    BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, approxPlaceableView());
    if (state.getBlock() instanceof AirBlock) {
      return null;
    }
    return state;
  }

  private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
    BetterBlockPos center = ctx.playerFeet();
    BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
    boolean breakFromAbove = Baritone.settings().breakFromAbove.value;
    for (ScanOffset offset : BREAK_SCAN_OFFSETS) {
      if (!breakFromAbove && offset.dy < 0) {
        continue;
      }
      int x = center.x + offset.dx;
      int y = center.y + offset.dy;
      int z = center.z + offset.dz;
      if (offset.dy == -1 && pathStart != null && x == pathStart.x && z == pathStart.z) {
        continue; // dont mine what we're supported by, but not directly standing on
      }
      BlockState current = bcc.bsi.get0(x, y, z);
      BlockState desired = bcc.getSchematic(x, y, z, current);
      if (desired == null) {
        continue; // irrelevant
      }
      if (!(current.getBlock() instanceof AirBlock) && !(current.getBlock() == Blocks.WATER || current.getBlock() == Blocks.LAVA) && !valid(current, desired, false)
        && bcc.breakCostMultiplierAt(x, y, z, current) < COST_INF) {
        BetterBlockPos pos = new BetterBlockPos(x, y, z);
        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
        if (rot.isPresent()) {
          return Optional.of(new Tuple<>(pos, rot.get()));
        }
      }
    }
    return Optional.empty();
  }

  private record Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot) {
  }

  private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
    if (!Baritone.settings().allowPlace.value) {
      return Optional.empty();
    }
    BetterBlockPos center = ctx.playerFeet();
    for (ScanOffset offset : PLACE_SCAN_OFFSETS) {
      int x = center.x + offset.dx;
      int y = center.y + offset.dy;
      int z = center.z + offset.dz;
      BlockState current = bcc.bsi.get0(x, y, z);
      BlockState desired = bcc.getSchematic(x, y, z, current);
      if (desired == null) {
        continue; // irrelevant
      }
      if (MovementHelper.isReplaceable(x, y, z, current, bcc.bsi) && !valid(current, desired, false)) {
        addEquivalentState(desirableOnHotbar, desired);
        Optional<Placement> opt = possibleToPlace(bcc, desired, x, y, z);
        if (opt.isPresent()) {
          return opt;
        }
      }
    }
    return Optional.empty();
  }

  public boolean placementPlausible(BlockPos pos, BlockState state) {
    VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
    return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
  }

  private Optional<Placement> possibleToPlace(BuilderCalculationContext bcc, BlockState toPlace, int x, int y, int z) {
    if (!Baritone.settings().allowPlace.value || bcc.costOfPlacingAt(x, y, z, bcc.bsi.get0(x, y, z)) >= COST_INF) {
      return Optional.empty();
    }
    BlockStateInterface bsi = bcc.bsi;
    BetterBlockPos target = new BetterBlockPos(x, y, z);
    if (!toPlace.canSurvive(ctx.world(), target) || !placementPlausible(target, toPlace)) {
      return Optional.empty();
    }
    for (Direction against : PLACE_AGAINST_PRIORITY) {
      BetterBlockPos placeAgainstPos = target.relative(against);
      BlockState placeAgainstState = bsi.get0(placeAgainstPos);
      if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
        continue;
      }
      VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
      if (shape.isEmpty()) {
        continue;
      }
      AABB aabb = shape.bounds();
      for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
        double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
        double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
        double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
        Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
        Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
        HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
        if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos)
          && ((BlockHitResult) result).getDirection() == against.getOpposite()) {
          OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
          if (hotbar.isPresent()) {
            return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot));
          }
        }
      }
    }
    return Optional.empty();
  }

  private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
    for (int i = 0; i < 9; i++) {
      ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
      if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
        continue;
      }
      float originalYaw = ctx.player().getYRot();
      float originalPitch = ctx.player().getXRot();
      // the state depends on the facing of the player sometimes
      BlockPlaceContext placeContext;
      BlockState wouldBePlaced;
      try {
        ctx.player().setYRot(rot.getYaw());
        ctx.player().setXRot(rot.getPitch());
        placeContext = new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, (BlockHitResult) result) {
        });
        wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(placeContext);
      } finally {
        ctx.player().setYRot(originalYaw);
        ctx.player().setXRot(originalPitch);
      }
      if (wouldBePlaced == null) {
        continue;
      }
      if (!placeContext.canPlace()) {
        continue;
      }
      if (valid(wouldBePlaced, desired, true)) {
        return OptionalInt.of(i);
      }
    }
    return OptionalInt.empty();
  }

  private static Vec3[] aabbSideMultipliers(Direction side) {
    switch (side) {
      case UP :
        return AABB_UP_MULTIPLIERS;
      case DOWN :
        return AABB_DOWN_MULTIPLIERS;
      case NORTH :
      case SOUTH :
      case EAST :
      case WEST :
        double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
        double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
        return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
      default : // null
        throw new IllegalStateException("Unexpected side " + side);
    }
  }

  @Override
  public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
    return onTick(calcFailed, isSafeToCancel, 0);
  }

  private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
    if (recursions > 100) { // onTick calls itself, don't crash
      return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
    }
    approxPlaceable = approxPlaceable(36);
    if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
      ticks = 5;
    } else {
      ticks--;
    }
    baritone.getInputOverrideHandler().clearAllKeys();
    if (paused) {
      return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    if (Baritone.settings().buildInLayers.value) {
      if (realSchematic == null) {
        realSchematic = schematic;
      }
      ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
      int minYInclusive;
      int maxYInclusive;
      // layer = 0 should be nothing
      // layer = realSchematic.heightY() should be everything
      if (Baritone.settings().layerOrder.value) { // top to bottom
        maxYInclusive = realSchematic.heightY() - 1;
        minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
      } else {
        maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
        minYInclusive = 0;
      }
      schematic = new ISchematic() {
        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
          return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
          return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
        }

        @Override
        public void reset() {
          realSchematic.reset();
        }

        @Override
        public int widthX() {
          return realSchematic.widthX();
        }

        @Override
        public int heightY() {
          return realSchematic.heightY();
        }

        @Override
        public int lengthZ() {
          return realSchematic.lengthZ();
        }
      };
    }
    BuilderCalculationContext bcc = new BuilderCalculationContext();
    if (!recalc(bcc)) {
      if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
        logDirect("Starting layer " + layer);
        layer++;
        return onTick(calcFailed, isSafeToCancel, recursions + 1);
      }
      numRepeats++;
      if (repeat.equals(NO_REPEAT) || (repeatCount != -1 && numRepeats >= repeatCount)) {
        logDirect("Done building");
        if (Baritone.settings().notificationOnBuildFinished.value) {
          logNotification("Done building", false);
        }
        onLostControl();
        return null;
      }
      // build repeat time
      layer = 0;
      origin = new BlockPos(origin).offset(repeat);
      if (!repeatSneaky) {
        schematic.reset();
      }
      logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
      return onTick(calcFailed, isSafeToCancel, recursions + 1);
    }
    if (Baritone.settings().distanceTrim.value) {
      trim();
    }

    Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
    if (toBreak.isPresent() && isSafeToCancel && ctx.player().onGround()) {
      // we'd like to pause to break this block
      // only change look direction if it is safe; active movement may depend on the current rotation
      Rotation rot = toBreak.get().getB();
      BetterBlockPos pos = toBreak.get().getA();
      baritone.getLookBehavior().updateTarget(rot, true);
      MovementHelper.bestToolSlot(ctx, bcc.get(pos)).ifPresent(ctx.player().getInventory()::setSelectedSlot);
      if (ctx.player().isCrouching()) {
        // really horrible bug where a block is visible for breaking while sneaking but not otherwise
        // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
        // and is unable since it's unsneaked in the intermediary tick
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
      }
      if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
      }
      return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    List<BlockState> desirableOnHotbar = new ArrayList<>();
    Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
    if (toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
      applyExactBuilderPlacement(toPlace.get());
      return cancelForDirectBuilderControl();
    }
    Optional<PathingCommand> genericPlacement = tryGenericBuilderPlacement(bcc, desirableOnHotbar, isSafeToCancel);
    if (genericPlacement.isPresent()) {
      return genericPlacement.get();
    }

    if (Baritone.settings().allowInventory.value) {
      ArrayList<Integer> usefulSlots = new ArrayList<>();
      List<BlockState> noValidHotbarOption = new ArrayList<>();
      outer : for (BlockState desired : desirableOnHotbar) {
        for (int i = 0; i < 9; i++) {
          if (canInventoryStateProvide(approxPlaceable.get(i), desired)) {
            if (!usefulSlots.contains(i)) {
              usefulSlots.add(i);
            }
            continue outer;
          }
        }
        noValidHotbarOption.add(desired);
      }

      outer : for (int i = 9; i < 36; i++) {
        for (BlockState desired : noValidHotbarOption) {
          if (canInventoryStateProvide(approxPlaceable.get(i), desired)) {
            if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
              // awaiting inventory move, so pause
              return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            break outer;
          }
        }
      }
    }

    Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
    if (goal == null) {
      goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
      if (goal == null) {
        if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
          logDirect("Skipping layer that I cannot construct! Layer #" + layer);
          layer++;
          return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
        paused = true;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
      }
    }
    return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
  }

  private Optional<PathingCommand> tryGenericBuilderPlacement(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar, boolean isSafeToCancel) {
    if (!Baritone.settings().allowPlace.value || !isSafeToCancel || !ctx.player().onGround() || ticks > 0) {
      return Optional.empty();
    }
    BetterBlockPos center = ctx.playerFeet();
    for (ScanOffset offset : PLACE_SCAN_OFFSETS) {
      int x = center.x + offset.dx;
      int y = center.y + offset.dy;
      int z = center.z + offset.dz;
      BlockState current = bcc.bsi.get0(x, y, z);
      BlockState desired = bcc.getSchematic(x, y, z, current);
      if (desired == null || !genericBuilderPlacementCandidate(desired) || !MovementHelper.isReplaceable(x, y, z, current, bcc.bsi) || valid(current, desired, false)) {
        continue;
      }
      if (bcc.costOfPlacingAt(x, y, z, current) >= COST_INF) {
        continue;
      }
      addEquivalentState(desirableOnHotbar, desired);
      ControlFrame.Builder placement = ControlFrame.builder();
      switch (MovementHelper.attemptToPlaceABlock(placement, baritone, new BlockPos(x, y, z), false, true)) {
        case NO_OPTION :
          continue;
        case ATTEMPTING :
          applyGenericBuilderPlacement(placement);
          return Optional.of(cancelForDirectBuilderControl());
        case READY_TO_PLACE :
          applyGenericBuilderPlacement(placement);
          baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
          return Optional.of(cancelForDirectBuilderControl());
      }
    }
    return Optional.empty();
  }

  private PathingCommand cancelForDirectBuilderControl() {
    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
  }

  private void applyExactBuilderPlacement(Placement placement) {
    baritone.getLookBehavior().updateTarget(placement.rot(), true);
    ctx.player().getInventory().setSelectedSlot(placement.hotbarSelection());
    baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
    if (lookingAtPlacement(placement)) {
      baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
    }
  }

  private boolean lookingAtPlacement(Placement placement) {
    if (!(ctx.objectMouseOver() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
      return false;
    }
    return hit.getBlockPos().equals(placement.placeAgainst()) && hit.getDirection() == placement.side();
  }

  private static boolean genericBuilderPlacementCandidate(BlockState desired) {
    Block block = desired.getBlock();
    return block instanceof WallBlock || block instanceof FenceBlock;
  }

  private void applyGenericBuilderPlacement(ControlFrame.Builder placement) {
    placement.getTarget().getRotation().ifPresent(rot -> baritone.getLookBehavior().updateTarget(rot, true));
    placement.selectedHotbarSlot().ifPresent(ctx.player().getInventory()::setSelectedSlot);
    placement.getInputStates().forEach(baritone.getInputOverrideHandler()::setInputForceState);
  }

  private boolean recalc(BuilderCalculationContext bcc) {
    if (incorrectPositions == null) {
      incorrectPositions = new HashSet<>();
      fullRecalc(bcc);
      if (incorrectPositions.isEmpty()) {
        return false;
      }
    }
    recalcNearby(bcc);
    if (incorrectPositions.isEmpty()) {
      fullRecalc(bcc);
    }
    return !incorrectPositions.isEmpty();
  }

  private void trim() {
    HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
    copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
    if (!copy.isEmpty()) {
      incorrectPositions = copy;
    }
  }

  private void recalcNearby(BuilderCalculationContext bcc) {
    BetterBlockPos center = ctx.playerFeet();
    int radius = Baritone.settings().builderTickScanRadius.value;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          int x = center.x + dx;
          int y = center.y + dy;
          int z = center.z + dz;
          BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
          if (desired != null) {
            // we care about this position
            BetterBlockPos pos = new BetterBlockPos(x, y, z);
            if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
              incorrectPositions.remove(pos);
              observedCompleted.add(BetterBlockPos.longHash(pos));
            } else {
              incorrectPositions.add(pos);
              observedCompleted.remove(BetterBlockPos.longHash(pos));
            }
          }
        }
      }
    }
  }

  private void fullRecalc(BuilderCalculationContext bcc) {
    incorrectPositions = new HashSet<>();
    for (int y = 0; y < schematic.heightY(); y++) {
      for (int z = 0; z < schematic.lengthZ(); z++) {
        for (int x = 0; x < schematic.widthX(); x++) {
          int blockX = x + origin.getX();
          int blockY = y + origin.getY();
          int blockZ = z + origin.getZ();
          BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
          if (!schematic.inSchematic(x, y, z, current)) {
            continue;
          }
          if (bcc.bsi.hasLiveChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
            // we can directly observe this block, it is in render distance
            if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
              observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
            } else {
              incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
              observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
              if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                return;
              }
            }
            continue;
          }
          // this is not in render distance
          if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
            // and we've never seen this position be correct
            // therefore mark as incorrect
            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
              return;
            }
          }
        }
      }
    }
  }

  private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
    return assemble(bcc, approxPlaceable, false);
  }

  private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
    List<BetterBlockPos> placeable = new ArrayList<>();
    List<BetterBlockPos> breakable = new ArrayList<>();
    List<BetterBlockPos> sourceLiquids = new ArrayList<>();
    List<BetterBlockPos> flowingLiquids = new ArrayList<>();
    Map<BlockState, Integer> missing = new HashMap<>();
    List<BetterBlockPos> outOfBounds = new ArrayList<>();
    List<BetterBlockPos> orderedIncorrect = orderedPositions(incorrectPositions, ctx.playerFeet());
    for (BetterBlockPos pos : orderedIncorrect) {
      BlockState state = bcc.bsi.get0(pos);
      if (state.getBlock() instanceof AirBlock) {
        BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
        if (desired == null) {
          outOfBounds.add(pos);
        } else if (containsInventoryStateFor(approxPlaceable, desired)) {
          placeable.add(pos);
        } else {
          missing.put(desired, 1 + missing.getOrDefault(desired, 0));
        }
      } else {
        if (state.getBlock() instanceof LiquidBlock) {
          // A pure liquid block is not breakable; waterlogged blocks are not LiquidBlocks and remain handled by ordinary break/place validity.
          if (!MovementHelper.possiblyFlowing(state)) {
            // if it's a source block then we want to replace it with a throwaway
            sourceLiquids.add(pos);
          } else {
            flowingLiquids.add(pos);
          }
        } else {
          breakable.add(pos);
        }
      }
    }
    incorrectPositions.removeAll(outOfBounds);
    List<Goal> toBreak = new ArrayList<>();
    breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
    List<Goal> toPlace = new ArrayList<>();
    LongOpenHashSet placeablePositions = new LongOpenHashSet(placeable.size());
    placeable.forEach(pos -> placeablePositions.add(BetterBlockPos.longHash(pos)));
    placeable.forEach(pos -> {
      if (!placeablePositions.contains(BetterBlockPos.longHash(pos.below())) && !placeablePositions.contains(BetterBlockPos.longHash(pos.below(2)))) {
        toPlace.add(placementGoal(pos, bcc));
      }
    });
    sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

    if (!toPlace.isEmpty()) {
      return new PrimaryFallbackGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
    }
    if (toBreak.isEmpty()) {
      if (logMissing && !missing.isEmpty()) {
        logDirect("Missing materials for at least:");
        logDirect(missing.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString())).map(e -> "%sx %s".formatted(e.getValue(), e.getKey())).collect(Collectors.joining("\n")));
      }
      if (logMissing && !flowingLiquids.isEmpty()) {
        logDirect("Unreplaceable liquids at at least:");
        logDirect(flowingLiquids.stream().map(p -> "%s %s %s".formatted(p.x, p.y, p.z)).collect(Collectors.joining("\n")));
      }
      return null;
    }
    return new GoalComposite(toBreak.toArray(new Goal[0]));
  }

  private static final class PrimaryFallbackGoalComposite implements Goal {

    private final Goal primary;
    private final Goal fallback;

    private PrimaryFallbackGoalComposite(Goal primary, Goal fallback) {
      this.primary = primary;
      this.fallback = fallback;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
      return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
      return primary.heuristic(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      PrimaryFallbackGoalComposite goal = (PrimaryFallbackGoalComposite) o;
      return Objects.equals(primary, goal.primary) && Objects.equals(fallback, goal.fallback);
    }

    @Override
    public int hashCode() {
      int hash = -1701079641;
      hash = hash * 1196141026 + primary.hashCode();
      hash = hash * -80327868 + fallback.hashCode();
      return hash;
    }

    @Override
    public String toString() {
      return "PrimaryFallbackGoalComposite{primary=" + primary + ", fallback=" + fallback + "}";
    }
  }

  public static final class GoalBreak extends GoalGetToBlock {

    public GoalBreak(BlockPos pos) {
      super(pos);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
      // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
      if (y > this.y) {
        return false;
      }
      // but any other adjacent works for breaking, including inside or below
      return super.isInGoal(x, y, z);
    }

    @Override
    public String toString() {
      return "GoalBreak{x=%s,y=%s,z=%s}".formatted(SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z));
    }

    @Override
    public int hashCode() {
      return super.hashCode() * 1636324008;
    }
  }

  private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
    if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {
      return new GoalPlace(pos);
    }
    BlockState current = ctx.world().getBlockState(pos);
    BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
    if (desired != null && builderShouldStandOnPlacedBlock(pos, desired, bcc)) {
      return new GoalPlace(pos);
    }
    boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
    for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
      //noinspection ConstantConditions
      if (builderPlaceAnchorUsable(bcc.bsi, pos.relative(facing)) && placementPlausible(pos, desired)) {
        return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
      }
    }
    return new GoalPlace(pos);
  }

  private boolean builderShouldStandOnPlacedBlock(BlockPos pos, BlockState desired, BuilderCalculationContext bcc) {
    return pos.getY() < ctx.playerFeet().y && MovementHelper.canWalkOn(bcc.bsi, pos.getX(), pos.getY(), pos.getZ(), desired) && builderCurrentlyPassable(bcc.bsi, pos.above())
      && builderCurrentlyPassable(bcc.bsi, pos.above(2));
  }

  private boolean builderCurrentlyPassable(BlockStateInterface bsi, BlockPos pos) {
    BlockState state = bsi.get0(pos);
    return MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state, bsi) || state.getCollisionShape(ctx.world(), pos).isEmpty();
  }

  private boolean builderPlaceAnchorUsable(BlockStateInterface bsi, BlockPos pos) {
    BlockState state = bsi.get0(pos);
    return !MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state, bsi) && !state.getShape(ctx.world(), pos).isEmpty();
  }

  private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
    if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) {
      return new PrimaryFallbackGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
        @Override
        public boolean isInGoal(int x, int y, int z) {
          if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
            return false;
          }
          return super.isInGoal(x, y, z);
        }
      });
    }
    return new GoalBreak(pos);
  }

  public static final class GoalAdjacent extends GoalGetToBlock {

    private final boolean allowSameLevel;
    private final BlockPos forbiddenStandingPos;

    public GoalAdjacent(BlockPos pos, BlockPos forbiddenStandingPos, boolean allowSameLevel) {
      super(pos);
      this.forbiddenStandingPos = forbiddenStandingPos;
      this.allowSameLevel = allowSameLevel;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
      if (x == this.x && y == this.y && z == this.z) {
        return false;
      }
      if (x == forbiddenStandingPos.getX() && y == forbiddenStandingPos.getY() && z == forbiddenStandingPos.getZ()) {
        return false;
      }
      if (!allowSameLevel && x == this.x && y == this.y - 1 && z == this.z) {
        return false;
      }
      if (y < this.y - 1) {
        return false;
      }
      return super.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
      // prioritize lower y coordinates
      return this.y * 100 + super.heuristic(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      GoalAdjacent goal = (GoalAdjacent) o;
      return allowSameLevel == goal.allowSameLevel && Objects.equals(forbiddenStandingPos, goal.forbiddenStandingPos);
    }

    @Override
    public int hashCode() {
      int hash = 806368046;
      hash = hash * 1412661222 + super.hashCode();
      hash = hash * 1730799370 + (int) BetterBlockPos.longHash(forbiddenStandingPos.getX(), forbiddenStandingPos.getY(), forbiddenStandingPos.getZ());
      hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
      return hash;
    }

    @Override
    public String toString() {
      return "GoalAdjacent{x=%s,y=%s,z=%s}".formatted(SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z));
    }
  }

  private static final class GoalPlace extends GoalBlock {

    private GoalPlace(BlockPos placeAt) {
      super(placeAt.above());
    }

    @Override
    public double heuristic(int x, int y, int z) {
      // prioritize lower y coordinates
      return this.y * 100 + super.heuristic(x, y, z);
    }

    @Override
    public int hashCode() {
      return super.hashCode() * 1910811835;
    }

    @Override
    public String toString() {
      return "GoalPlace{x=%s,y=%s,z=%s}".formatted(SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z));
    }
  }

  @Override
  public void onLostControl() {
    incorrectPositions = null;
    name = null;
    schematic = null;
    realSchematic = null;
    layer = Baritone.settings().startAtLayer.value;
    numRepeats = 0;
    repeat = NO_REPEAT;
    repeatCount = 0;
    repeatSneaky = false;
    paused = false;
    observedCompleted = null;
    approxPlaceable = Collections.emptyList();
  }

  @Override
  public String displayName0() {
    return paused ? "Builder Paused" : "Building " + name;
  }

  @Override
  public Optional<Integer> getMinLayer() {
    if (Baritone.settings().buildInLayers.value) {
      return Optional.of(this.layer);
    }
    return Optional.empty();
  }

  @Override
  public Optional<Integer> getMaxLayer() {
    if (Baritone.settings().buildInLayers.value) {
      return Optional.of(this.stopAtHeight);
    }
    return Optional.empty();
  }

  private List<BlockState> approxPlaceable(int size) {
    List<BlockState> result = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
      if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
        result.add(Blocks.AIR.defaultBlockState());
        continue;
      }
      BlockState itemState = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack,
        new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {
      }));
      if (itemState != null) {
        result.add(itemState);
      } else {
        result.add(Blocks.AIR.defaultBlockState());
      }
    }
    return result;
  }

  private List<BlockState> approxPlaceableView() {
    return approxPlaceable == null ? Collections.emptyList() : approxPlaceable;
  }

  private static void addEquivalentState(List<BlockState> states, BlockState state) {
    if (!containsBlockState(states, state)) {
      states.add(state);
    }
  }

  private static List<BetterBlockPos> orderedPositions(Collection<BetterBlockPos> positions, BetterBlockPos center) {
    ArrayList<BetterBlockPos> ordered = new ArrayList<>(positions);
    ordered.sort(positionComparator(center));
    return ordered;
  }

  private static Comparator<BetterBlockPos> positionComparator(BetterBlockPos center) {
    return Comparator.<BetterBlockPos>comparingDouble(pos -> pos.distSqr(center)).thenComparingInt(pos -> pos.y).thenComparingInt(pos -> pos.x).thenComparingInt(pos -> pos.z);
  }

  private static List<ScanOffset> scanOffsets(int horizontalRadius, int minY, int maxY) {
    ArrayList<ScanOffset> result = new ArrayList<>((horizontalRadius * 2 + 1) * (horizontalRadius * 2 + 1) * (maxY - minY + 1));
    for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
      for (int dy = minY; dy <= maxY; dy++) {
        for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
          result.add(new ScanOffset(dx, dy, dz));
        }
      }
    }
    result.sort(Comparator.comparingInt(ScanOffset::distanceSqr).thenComparingInt(offset -> Math.abs(offset.dy)).thenComparingInt(offset -> offset.dy).thenComparingInt(offset -> offset.dx)
      .thenComparingInt(offset -> offset.dz));
    return List.copyOf(result);
  }

  private record ScanOffset(int dx, int dy, int dz) {
    private int distanceSqr() {
      return dx * dx + dy * dy + dz * dz;
    }
  }

  private static boolean sameBlockstate(BlockState first, BlockState second) {
    if (first.getBlock() != second.getBlock()) {
      return false;
    }
    boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
    List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
    if (!ignoreDirection && ignoredProps.isEmpty() && !hasEnvironmentDrivenProperties(first.getBlock())) {
      return first.equals(second); // early return if no properties are being ignored
    }
    for (Property<?> prop : first.getProperties()) {
      if (!Objects.equals(first.getValue(prop), second.getValue(prop)) && !ignoredProperty(first, prop, ignoreDirection, ignoredProps)) {
        return false;
      }
    }
    return true;
  }

  private static boolean ignoredProperty(BlockState state, Property<?> prop, boolean ignoreDirection, List<String> ignoredProps) {
    String name = prop.getName();
    return ignoredProps.contains(name) || environmentDrivenProperty(state.getBlock(), name) || ignoreDirection && (ORIENTATION_PROPS.contains(prop) || ORIENTATION_PROP_NAMES.contains(name));
  }

  private static boolean environmentDrivenProperty(Block block, String name) {
    return block instanceof CrossCollisionBlock && CONNECTION_PROP_NAMES.contains(name) || block instanceof WallBlock && CONNECTION_PROP_NAMES.contains(name);
  }

  private static boolean hasEnvironmentDrivenProperties(Block block) {
    return block instanceof CrossCollisionBlock || block instanceof WallBlock;
  }

  private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
    for (BlockState testee : states) {
      if (sameBlockstate(testee, state)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsInventoryStateFor(Collection<BlockState> inventoryStates, BlockState desired) {
    for (BlockState inventoryState : inventoryStates) {
      if (canInventoryStateProvide(inventoryState, desired)) {
        return true;
      }
    }
    return false;
  }

  private static boolean canInventoryStateProvide(BlockState inventoryState, BlockState desired) {
    if (valid(inventoryState, desired, true)) {
      return true;
    }
    if (inventoryState.getBlock() instanceof AirBlock || desired == null || desired.getBlock() instanceof AirBlock) {
      return false;
    }
    return inventoryState.getBlock() == desired.getBlock() && desiredPropertiesArePlaceOrEnvironmentDerived(desired);
  }

  private static boolean desiredPropertiesArePlaceOrEnvironmentDerived(BlockState desired) {
    for (Property<?> prop : desired.getProperties()) {
      String name = prop.getName();
      if (!environmentDrivenProperty(desired.getBlock(), name) && !placeDerivedProperty(desired.getBlock(), name) && !ORIENTATION_PROP_NAMES.contains(name)
        && !Baritone.settings().buildIgnoreProperties.value.contains(name)) {
        return false;
      }
    }
    return true;
  }

  private static boolean placeDerivedProperty(Block block, String name) {
    return block instanceof SlabBlock && name.equals("type");
  }

  private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
    if (desired == null) {
      return true;
    }
    if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
      return true;
    }
    if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
      return true;
    }
    if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
      return true;
    }
    if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
      return true;
    }
    if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
      return true;
    }
    if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
      return true;
    }
    if (current.equals(desired)) {
      return true;
    }
    return sameBlockstate(current, desired);
  }

  public class BuilderCalculationContext extends CalculationContext {

    private final List<BlockState> placeable;
    private final ISchematic schematic;
    private final int originX;
    private final int originY;
    private final int originZ;

    public BuilderCalculationContext() {
      super(BuilderProcess.this.baritone, true);
      this.placeable = approxPlaceable(9);
      this.schematic = BuilderProcess.this.schematic;
      this.originX = origin.getX();
      this.originY = origin.getY();
      this.originZ = origin.getZ();

      this.costs = this.costs.withJumpPenalty(this.costs.jumpPenalty() + 10).withBacktrackFavoringCoefficient(1);
    }

    private BlockState getSchematic(int x, int y, int z, BlockState current) {
      if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
        return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
      } else {
        return null;
      }
    }

    @Override
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
      if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
        return COST_INF;
      }
      if (!Baritone.settings().allowPlace.value) {
        return COST_INF;
      }
      BlockState sch = getSchematic(x, y, z, current);
      if (sch != null) {
        if (sch.getBlock() instanceof AirBlock) {
          // This is a temporary throwaway in a location the schematic ultimately wants cleared.
          return placement.hasThrowaway() ? placement.blockCost() * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value : COST_INF;
        }
        if (containsInventoryStateFor(placeable, sch)) {
          // No generic place-block penalty: this is exactly what the schematic needs.
          return 0;
        }
        return placement.hasThrowaway() ? placement.blockCost() * 1.5D * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value : COST_INF;
      } else {
        return placement.hasThrowaway() ? placement.blockCost() : COST_INF;
      }
    }

    @Override
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
      if (!breaking.allows(current.getBlock()) || isPossiblyProtected(x, y, z)) {
        return COST_INF;
      }
      BlockState sch = getSchematic(x, y, z, current);
      if (sch != null) {
        if (sch.getBlock() instanceof AirBlock) {
          // it should be air
          // regardless of current contents, we can break it
          return 1;
        }
        // it should be a real block
        // is it already that block?
        if (valid(bsi.get0(x, y, z), sch, false)) {
          return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
        } else {
          // can break if it's wrong
          // would be great to return less than 1 here, but that would actually make the cost calculation messed up
          // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
          return 1;

        }
      } else {
        return 1; // outside the schematic, use the normal break cost
      }
    }
  }
}
