package baritone.process;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.ElytraLaunchMode;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.ElytraBehavior;
import baritone.process.elytra.ElytraFireworks;
import baritone.process.elytra.ElytraFlightPolicy;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ElytraProcess extends BaritoneProcessHelper implements IElytraProcess, AbstractGameEventListener {
  private static final int LANDING_SEARCH_RADIUS_XZ = 192;
  private static final int LANDING_SEARCH_CANDIDATE_BUDGET = 8000;
  private static final int LANDING_SEARCH_RETRY_TICKS = 100;
  private static final int[] LANDING_SEARCH_Y_OFFSETS = {0, 16, -16, 32, -32, 48, -48};
  private static final int[] LANDING_SEARCH_SIGNS = {-1, 1};

  public State state;
  private boolean goingToLandingSpot;
  private BetterBlockPos landingSpot;
  private boolean reachedGoal; // this basically just prevents potential notification spam
  private Goal goal;
  private ElytraBehavior behavior;
  private boolean predictingTerrain;
  private ElytraLaunchMode launchMode = ElytraLaunchMode.MANUAL;
  private Goal continuationGoal;
  private boolean hasGlided;
  private int sprintLaunchTicks;
  private int wingOpenAttempts;
  private int waterRecoveryTicks;
  private int initialPathWaitTicks;
  private boolean recoveringFromWater;
  private boolean emergencyLanding;
  private int landingSearchCooldownTicks;

  @Override
  public void onLostControl() {
    this.state = State.START_FLYING; // TODO: null state?
    this.goingToLandingSpot = false;
    this.landingSpot = null;
    this.reachedGoal = false;
    this.goal = null;
    this.launchMode = ElytraLaunchMode.MANUAL;
    this.continuationGoal = null;
    this.hasGlided = false;
    this.sprintLaunchTicks = 0;
    this.wingOpenAttempts = 0;
    this.waterRecoveryTicks = 0;
    this.initialPathWaitTicks = 0;
    this.recoveringFromWater = false;
    this.emergencyLanding = false;
    this.landingSearchCooldownTicks = 0;
    destroyBehaviorAsync();
  }

  private ElytraProcess(Baritone baritone) {
    super(baritone);
    baritone.getGameEventHandler().registerEventListener(this);
  }

  public static IElytraProcess create(final Baritone baritone) {
    return new ElytraProcess(baritone);
  }

  @Override
  public boolean isActive() { return this.behavior != null; }

  @Override
  public void resetState() {
    BlockPos destination = this.currentDestination();
    ElytraLaunchMode launchMode = this.launchMode;
    Goal continuationGoal = this.continuationGoal;
    this.onLostControl();
    if (destination != null) {
      this.pathTo0(destination, false, launchMode, continuationGoal);
      this.repackChunks();
    }
  }

  private static final String AUTO_JUMP_FAILURE_MSG =
    "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location near an overhang, or manually begin gliding.";

  @Override
  public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
    if (!Baritone.settings().elytraEnabled.value) {
      logDirect("Elytra pathing disabled");
      return resumeContinuation("Elytra pathing disabled", PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    final long seedSetting = Baritone.settings().elytraNetherSeed.value;
    if (this.behavior.context.usesTerrainSeed() && seedSetting != this.behavior.context.seed()) {
      logDirect("Nether seed changed, recalculating path");
      this.resetState();
    }
    if (this.behavior.policy().supportsTerrainPrediction() && predictingTerrain != Baritone.settings().elytraPredictTerrain.value) {
      logDirect("elytraPredictTerrain setting changed, recalculating path");
      predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
      this.resetState();
    }

    this.behavior.onTick();
    if (landingSearchCooldownTicks > 0) {
      landingSearchCooldownTicks--;
    }

    if (calcFailed) {
      logDirect(AUTO_JUMP_FAILURE_MSG);
      return resumeContinuation("Elytra launch path failed", PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    if (ctx.player().isFallFlying()) {
      hasGlided = true;
      recoveringFromWater = false;
    } else if (ctx.player().isInWater()) {
      return waterRecovery();
    } else if (recoveringFromWater) {
      return resumeContinuation("Elytra leg recovered from water", PathingCommandType.REQUEST_PAUSE);
    } else if (hasGlided && continuationGoal != null && ctx.player().onGround() && this.state != State.LANDING) {
      return resumeContinuation("Elytra leg ended on the ground before the destination", PathingCommandType.REQUEST_PAUSE);
    }

    boolean safetyLanding = emergencyLanding;
    if (ctx.player().isFallFlying() && !emergencyLanding && shouldLandForSafety()) {
      if (Baritone.settings().elytraAllowEmergencyLand.value) {
        logDirect("Emergency landing - almost out of elytra durability or fireworks");
        emergencyLanding = true;
        safetyLanding = true;
      } else {
        logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
      }
    }
    if (ctx.player().isFallFlying() && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
      final BetterBlockPos last = this.behavior.pathManager.path.getLast();
      boolean landingSearchReady = !goingToLandingSpot || safetyLanding && this.landingSpot == null && landingSearchCooldownTicks <= 0;
      if (last != null && (ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48) || safetyLanding) && landingSearchReady) {
        logDirect("Path complete, picking a nearby safe landing spot...");
        BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
        // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
        if (landingSpot != null) {
          this.pathTo0(landingSpot, true, ElytraLaunchMode.MANUAL, continuationGoal);
          this.landingSpot = landingSpot;
          this.emergencyLanding = safetyLanding;
        } else {
          this.landingSearchCooldownTicks = LANDING_SEARCH_RETRY_TICKS;
        }
        this.goingToLandingSpot = true;
      }

      if (last != null && ctx.player().position().distanceToSqr(last.getCenter()) < 1) {
        if (Baritone.settings().notificationOnPathComplete.value && !reachedGoal) {
          logNotification("Pathing complete", false);
        }
        if (Baritone.settings().disconnectOnArrival.value && !reachedGoal) {
          // don't be active when the user logs back in
          this.onLostControl();
          if (ctx.world() instanceof ClientLevel clientLevel) {
            clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
          }
          return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        reachedGoal = true;

        // we are goingToLandingSpot and we are in the last node of the path
        if (this.goingToLandingSpot) {
          this.state = State.LANDING;
          logDirect("Above the landing spot, landing...");
        }
      }
    }

    if (this.state == State.LANDING) {
      final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
      if (ctx.player().isFallFlying() && endPos != null) {
        Vec3 from = ctx.player().position();
        Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

        if (ctx.player().position().y < endPos.y - behavior.policy().landingColumnHeight()) {
          logDirect("bad landing spot, trying again...");
          landingSpotIsBad(endPos);
        }
      }
    }

    if (ctx.player().isFallFlying()) {
      behavior.landingMode = this.state == State.LANDING;
      behavior.conserveFireworks = emergencyLanding;
      this.goal = null;
      baritone.getInputOverrideHandler().clearAllKeys();
      baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
      behavior.tick();
      return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    } else if (this.state == State.LANDING) {
      if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
        logDirect("Landed, but still moving, waiting for velocity to die down... ");
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
      }
      logDirect("Done :)");
      baritone.getInputOverrideHandler().clearAllKeys();
      if (continuationGoal != null) {
        return resumeContinuation("Elytra leg landed", PathingCommandType.REQUEST_PAUSE);
      }
      this.onLostControl();
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    if (this.state == State.FLYING || this.state == State.START_FLYING) {
      this.state = ctx.player().onGround() ? launchMode.sprintJump() ? State.SPRINT_JUMP : autoJumpLaunch() ? State.LOCATE_JUMP : State.START_FLYING : State.START_FLYING;
    }

    if (this.state == State.LOCATE_JUMP) {
      if (shouldLandForSafety()) {
        logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
        return resumeContinuation("Elytra launch declined for safety", PathingCommandType.CANCEL_AND_SET_GOAL);
      }
      if (this.goal == null) {
        this.goal = new GoalYLevel(behavior.policy().autoLaunchY());
      }
      final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
      if (executor != null && executor.getPath() != null && executor.getPath().getGoal() == this.goal) {
        final IMovement fall = executor.getPath().movements().stream().filter(movement -> movement instanceof MovementFall).findFirst().orElse(null);

        if (fall != null) {
          final BetterBlockPos from = new BetterBlockPos((fall.getSrc().x + fall.getDest().x) / 2, (fall.getSrc().y + fall.getDest().y) / 2, (fall.getSrc().z + fall.getDest().z) / 2);
          behavior.telemetryEvent("fall_launch_candidate", "from", from);
          this.state = State.PAUSE;
          behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
            if (ex == null) {
              if (this.state == State.PAUSE) {
                this.state = State.GET_TO_JUMP;
              }
              behavior.telemetryEvent("fall_launch_path_ready", "from", from);
              return;
            }
            behavior.telemetryEvent("fall_launch_path_failed", "from", from, "exception", ex);
            onLostControl();
          });
        } else {
          logDirect(AUTO_JUMP_FAILURE_MSG);
          return resumeContinuation("Elytra launch path had no usable fall", PathingCommandType.CANCEL_AND_SET_GOAL);
        }
      }
      return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
    }

    if (this.state == State.SPRINT_JUMP) {
      if (shouldLandForSafety()) {
        logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
        return resumeContinuation("Flat elytra launch declined for safety", PathingCommandType.CANCEL_AND_SET_GOAL);
      }
      if (!behavior.hasPath()) {
        baritone.getInputOverrideHandler().clearAllKeys();
        if (initialPathWaitTicks++ % 20 == 0) {
          behavior.telemetryEvent("await_initial_path", "tick", initialPathWaitTicks, "position", ctx.playerFeetAsVec());
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
      }
      initialPathWaitTicks = 0;
      sprintLaunchTicks++;
      behavior.telemetryEvent("sprint_jump", "tick", sprintLaunchTicks, "position", ctx.playerFeetAsVec(), "motion", ctx.playerMotion(), "onGround", ctx.player().onGround(), "fallDistance",
        ctx.player().fallDistance);
      aimAtDestination(-10);
      baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
      baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
      if (ctx.player().onGround()) {
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
      } else {
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        if (tryStartFallFlying("sprint_jump")) {
          return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (canOpenWings()) {
          this.state = State.START_FLYING;
        }
      }
      return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    if (this.state == State.PAUSE) {
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    if (this.state == State.GET_TO_JUMP) {
      final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
      final boolean canStartFlying =
        canOpenWings() && !isSafeToCancel && executor != null && executor.getPath() != null && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

      if (canStartFlying) {
        this.state = State.START_FLYING;
      } else {
        return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
      }
    }

    if (this.state == State.START_FLYING) {
      if (!isSafeToCancel) {
        // owned
        baritone.getPathingBehavior().secretInternalSegmentCancel();
      }
      baritone.getInputOverrideHandler().clearAllKeys();
      if (launchMode.sprintJump()) {
        aimAtDestination(-10);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
      }
      if (!tryStartFallFlying("start_flying") && canOpenWings()) {
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
      }
    }
    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
  }

  public void landingSpotIsBad(BetterBlockPos endPos) {
    badLandingSpots.add(endPos);
    goingToLandingSpot = false;
    this.landingSpot = null;
    this.state = State.FLYING;
  }

  private void destroyBehaviorAsync() {
    ElytraBehavior behavior = this.behavior;
    if (behavior != null) {
      this.behavior = null;
      Baritone.getExecutor().execute(behavior::destroy);
    }
  }

  @Override
  public double priority() {
    return 0; // higher priority than CustomGoalProcess
  }

  @Override
  public String displayName0() {
    return "Elytra - " + this.state.description;
  }

  @Override
  public void repackChunks() {
    if (this.behavior != null) {
      this.behavior.repackChunks();
    }
  }

  @Override
  public BlockPos currentDestination() {
    return this.behavior != null ? this.behavior.destination : null;
  }

  @Override
  public void pathTo(BlockPos destination) {
    this.pathTo(destination, ElytraLaunchMode.MANUAL);
  }

  @Override
  public void pathTo(BlockPos destination, ElytraLaunchMode launchMode) {
    this.pathTo(destination, launchMode, null);
  }

  @Override
  public void pathTo(BlockPos destination, ElytraLaunchMode launchMode, Goal continuationGoal) {
    this.pathTo0(destination, false, launchMode, continuationGoal);
  }

  private void pathTo0(BlockPos destination, boolean appendDestination, ElytraLaunchMode launchMode, Goal continuationGoal) {
    if (ctx.player() == null || ctx.world() == null) {
      return;
    }
    if (!Baritone.settings().elytraEnabled.value) {
      logDirect("Elytra pathing disabled");
      this.onLostControl();
      return;
    }
    this.onLostControl();
    this.launchMode = Objects.requireNonNull(launchMode);
    this.continuationGoal = continuationGoal;
    this.sprintLaunchTicks = 0;
    this.wingOpenAttempts = 0;
    this.waterRecoveryTicks = 0;
    this.initialPathWaitTicks = 0;
    this.recoveringFromWater = false;
    this.emergencyLanding = false;
    this.landingSearchCooldownTicks = 0;
    this.predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
    this.behavior = new ElytraBehavior(this.baritone, this, destination, appendDestination, this.launchMode);
    if (ctx.world() != null) {
      this.behavior.repackChunks();
    }
    this.behavior.pathTo(this.launchMode);
  }

  @Override
  public void pathTo(Goal iGoal) {
    final int x;
    final int y;
    final int z;
    if (iGoal instanceof GoalXZ) {
      GoalXZ goal = (GoalXZ) iGoal;
      ElytraFlightPolicy policy = ElytraFlightPolicy.capture(ctx.world());
      x = goal.getX();
      y = policy.targetY(ctx);
      z = goal.getZ();
    } else if (iGoal instanceof GoalBlock) {
      GoalBlock goal = (GoalBlock) iGoal;
      x = goal.x;
      y = goal.y;
      z = goal.z;
    } else {
      throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
    }
    ElytraFlightPolicy policy = ElytraFlightPolicy.capture(ctx.world());
    if (!policy.validGoalY(y)) {
      throw new IllegalArgumentException("The y of the goal is not in this dimension's elytra flight bounds [" + policy.minY() + ", " + policy.maxYExclusive() + ")");
    }
    this.pathTo(new BlockPos(x, y, z), this.launchMode, null);
  }

  private boolean autoJumpLaunch() {
    return Baritone.settings().elytraAutoJump.value || launchMode.autoJump();
  }

  private boolean canOpenWings() {
    return !ctx.player().onGround() && (ctx.player().fallDistance > 0 || ctx.player().getDeltaMovement().y < -0.01);
  }

  private boolean tryStartFallFlying(String phase) {
    if (!canOpenWings() || ctx.player().isInWater() || ctx.player().isFallFlying()) {
      return false;
    }
    wingOpenAttempts++;
    boolean started = ctx.player().tryToStartFallFlying();
    behavior.telemetryEvent("open_wings", "phase", phase, "attempt", wingOpenAttempts, "started", started, "position", ctx.playerFeetAsVec(), "motion", ctx.playerMotion(), "fallDistance",
      ctx.player().fallDistance);
    if (started) {
      ctx.player().connection.send(new ServerboundPlayerCommandPacket(ctx.player(), ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
      this.state = State.FLYING;
      if (launchMode.launchFirework()) {
        behavior.launchFireworkNow("wing_open");
      }
    }
    return started;
  }

  private void aimAtDestination(float pitch) {
    Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(behavior.destination), ctx.playerRotations());
    baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), pitch), false);
  }

  private PathingCommand waterRecovery() {
    recoveringFromWater = true;
    waterRecoveryTicks++;
    behavior.telemetryEvent("water_recovery", "tick", waterRecoveryTicks, "position", ctx.playerFeetAsVec(), "motion", ctx.playerMotion(), "underwater", ctx.player().isUnderWater(), "air",
      ctx.player().getAirSupply(), "continuation", continuationGoal);
    baritone.getInputOverrideHandler().clearAllKeys();
    aimAtDestination(ctx.player().isUnderWater() ? -35 : -5);
    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
    baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
    if (continuationGoal != null && ctx.player().onGround()) {
      return resumeContinuation("Elytra leg fell into water", PathingCommandType.REQUEST_PAUSE);
    }
    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
  }

  private PathingCommand resumeContinuation(String reason, PathingCommandType commandType) {
    Goal continuation = this.continuationGoal;
    if (behavior != null) {
      behavior.telemetryEvent("resume_continuation", "reason", reason, "continuation", continuation);
    }
    this.onLostControl();
    if (continuation != null) {
      logDirect(reason + "; continuing on foot");
      baritone.getCustomGoalProcess().suppressTransportPromotion(20 * 30);
      baritone.getCustomGoalProcess().setGoalAndPath(continuation);
    }
    return new PathingCommand(null, commandType);
  }

  private boolean shouldLandForSafety() {
    ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
    int minimumDurability = Baritone.settings().elytraMinimumDurability.value;
    if (chest.getItem() != Items.ELYTRA) {
      return true;
    }
    if (remainingDurability(chest) <= minimumDurability && (!Baritone.settings().elytraAutoSwap.value || !hasSpareElytra(minimumDurability))) {
      return true;
    }

    if (behavior == null || !behavior.policy().fireworkPolicy().fireworkReserveMatters()) {
      return false;
    }
    NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
    int qty = 0;
    for (int i = 0; i < 36; i++) {
      if (ElytraFireworks.isPlain(inv.get(i))) {
        qty += inv.get(i).getCount();
      }
    }
    if (qty <= Baritone.settings().elytraMinFireworksBeforeLanding.value) {
      return true;
    }
    return false;
  }

  private boolean hasSpareElytra(int minimumDurability) {
    NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
    for (int i = 0; i < 36; i++) {
      ItemStack stack = inv.get(i);
      if (stack.getItem() == Items.ELYTRA && remainingDurability(stack) > minimumDurability) {
        return true;
      }
    }
    return false;
  }

  private static int remainingDurability(ItemStack stack) {
    return stack.getMaxDamage() - stack.getDamageValue();
  }

  @Override
  public boolean isLoaded() { return true; }

  @Override
  public boolean isSafeToCancel() { return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING); }

  public enum State {
    LOCATE_JUMP("Finding spot to jump off"), SPRINT_JUMP("Sprint-jump takeoff"), PAUSE("Waiting for elytra path"), GET_TO_JUMP("Walking to takeoff"), START_FLYING("Begin flying"), FLYING(
      "Flying"), LANDING("Landing");

    public final String description;

    State(String desc) {
      this.description = desc;
    }
  }

  @Override
  public void onRenderPass(RenderEvent event) {
    if (this.behavior != null) this.behavior.onRenderPass(event);
  }

  @Override
  public void onWorldEvent(WorldEvent event) {
    if (event.getWorld() != null && event.getState() == EventState.POST) {
      // Exiting the world, just destroy
      destroyBehaviorAsync();
    }
  }

  @Override
  public void onChunkEvent(ChunkEvent event) {
    if (this.behavior != null) this.behavior.onChunkEvent(event);
  }

  @Override
  public void onBlockChange(BlockChangeEvent event) {
    if (this.behavior != null) this.behavior.onBlockChange(event);
  }

  @Override
  public void onReceivePacket(PacketEvent event) {
    if (this.behavior != null) this.behavior.onReceivePacket(event);
  }

  @Override
  public void onPostTick(TickEvent event) {
    IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
    if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
  }

  /**
   * Custom calculation context which makes the player fall into lava
   */
  public static final class WalkOffCalculationContext extends CalculationContext {
    public WalkOffCalculationContext(IBaritone baritone) {
      super(baritone, true);
      this.fall = this.fall.walkOffIntoLava();
    }

    @Override
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
      return COST_INF;
    }

    @Override
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
      return COST_INF;
    }

    @Override
    public double placeBucketCostAt(int x, int y, int z) {
      return COST_INF;
    }
  }

  private boolean isInBounds(BlockPos pos) {
    return behavior.policy().inBounds(pos);
  }

  private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
    BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
    final int maxY = mut.getY() + minHeight;
    for (int y = mut.getY() + 1; y <= maxY; y++) {
      mut.set(mut.getX(), y, mut.getZ());
      if (!isInBounds(mut) || !isAir(mut)) {
        return false;
      }
    }
    return true;
  }

  private boolean hasAirBubble(BlockPos pos) {
    final int radius = behavior.policy().landingBubbleRadius();
    BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
    for (int x = -radius; x <= radius; x++) {
      for (int y = -radius; y <= radius; y++) {
        for (int z = -radius; z <= radius; z++) {
          mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
          if (!isInBounds(mut) || !isAir(mut)) {
            return false;
          }
        }
      }
    }

    return true;
  }

  private boolean isLandingPad(BlockPos pos) {
    int radius = behavior.policy().landingSupportRadius();
    BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
    for (int x = -radius; x <= radius; x++) {
      for (int z = -radius; z <= radius; z++) {
        mut.set(pos.getX() + x, pos.getY(), pos.getZ() + z);
        if (!behavior.policy().safeLandingBlock(ctx, mut) || !isAir(mut.above()) || !isAir(mut.above(2))) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean isAir(BlockPos pos) {
    return ctx.world().getBlockState(pos).getBlock() instanceof AirBlock;
  }

  private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
    BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
    while (mut.getY() >= behavior.policy().minY()) {
      if (checkedSpots.contains(mut.asLong())) {
        return null;
      }
      checkedSpots.add(mut.asLong());

      if (behavior.policy().safeLandingBlock(ctx, mut)) {
        return isLandingPad(mut) ? new BetterBlockPos(mut) : null;
      } else if (!isAir(mut)) {
        return null;
      }
      mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
    }
    return null; // void
  }

  private Set<BetterBlockPos> badLandingSpots = new HashSet<>();

  private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
    LongOpenHashSet checkedPositions = new LongOpenHashSet();
    int candidates = 0;
    for (int radius = 0; radius <= LANDING_SEARCH_RADIUS_XZ && candidates < LANDING_SEARCH_CANDIDATE_BUDGET; radius++) {
      if (radius == 0) {
        for (int dy : LANDING_SEARCH_Y_OFFSETS) {
          candidates++;
          BetterBlockPos found = checkLandingCandidate(start, 0, dy, 0, checkedPositions);
          if (found != null) return found;
        }
        continue;
      }
      for (int dx = -radius; dx <= radius && candidates < LANDING_SEARCH_CANDIDATE_BUDGET; dx++) {
        for (int sign : LANDING_SEARCH_SIGNS) {
          int dz = sign * radius;
          for (int dy : LANDING_SEARCH_Y_OFFSETS) {
            candidates++;
            BetterBlockPos found = checkLandingCandidate(start, dx, dy, dz, checkedPositions);
            if (found != null) return found;
            if (candidates >= LANDING_SEARCH_CANDIDATE_BUDGET) break;
          }
        }
      }
      for (int dz = -radius + 1; dz < radius && candidates < LANDING_SEARCH_CANDIDATE_BUDGET; dz++) {
        for (int sign : LANDING_SEARCH_SIGNS) {
          int dx = sign * radius;
          for (int dy : LANDING_SEARCH_Y_OFFSETS) {
            candidates++;
            BetterBlockPos found = checkLandingCandidate(start, dx, dy, dz, checkedPositions);
            if (found != null) return found;
            if (candidates >= LANDING_SEARCH_CANDIDATE_BUDGET) break;
          }
        }
      }
    }
    if (behavior != null) {
      behavior.telemetryEvent("landing_search_failed", "start", start, "candidates", candidates, "radius", LANDING_SEARCH_RADIUS_XZ);
    }
    return null;
  }

  private BetterBlockPos checkLandingCandidate(BetterBlockPos start, int dx, int dy, int dz, LongOpenHashSet checkedPositions) {
    BetterBlockPos pos = new BetterBlockPos(start.x + dx, start.y + dy, start.z + dz);
    if (!ctx.world().isLoaded(pos) || !isInBounds(pos) || !isAir(pos)) {
      return null;
    }
    BetterBlockPos actualLandingSpot = checkLandingSpot(pos, checkedPositions);
    int columnHeight = behavior.policy().landingColumnHeight();
    BetterBlockPos approach = actualLandingSpot == null ? null : actualLandingSpot.above(columnHeight);
    if (actualLandingSpot != null && isColumnAir(actualLandingSpot, columnHeight) && hasAirBubble(approach) && !badLandingSpots.contains(approach)) {
      return approach;
    }
    return null;
  }
}
