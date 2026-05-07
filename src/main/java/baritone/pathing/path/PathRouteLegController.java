package baritone.pathing.path;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.control.ControlFrame;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.LiquidLocomotionController;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementTick;
import baritone.pathing.movement.movements.*;
import baritone.pathing.movement.water.SurfaceWaterDomain;
import baritone.pathing.route.LegTickResult;
import baritone.pathing.route.SurfaceLineController;
import baritone.pathing.route.PathSurfaceOverlayLeg;
import baritone.pathing.route.RouteRenderPlan;
import baritone.pathing.route.SurfaceLinePhase;
import baritone.pathing.transport.TransportControl;
import baritone.pathing.transport.TransportMode;
import baritone.pathing.transport.TransportSnapshot;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.phys.Vec3;
import java.util.*;

import static baritone.api.pathing.movement.MovementStatus.*;

/**
 * Behavior to execute a precomputed path
 *
 * @author leijurv
 */
final class PathRouteLegController implements RouteLegController, Helper {

  private static final double MAX_MAX_DIST_FROM_PATH = 3;
  private static final double MAX_DIST_FROM_PATH = 2;
  private static final int MAX_POSITION_REVISIONS_PER_TICK = 64;

  /**
   * Default value is equal to 10 seconds. It's find to decrease it, but it must be at least 5.5s (110 ticks).
   * For more information, see issue #102.
   *
   * @see <a href="https://github.com/cabaletta/baritone/issues/102">Issue #102</a>
   * @see <a href="https://i.imgur.com/5s5GLnI.png">Anime</a>
   */
  private static final double MAX_TICKS_AWAY = 200;

  private final IPath path;
  private final BlockPos[] flatValidPositions;
  private final Long2IntOpenHashMap pathIndexByPosition;
  private int pathPosition;
  private int ticksAway;
  private int ticksOnCurrent;
  private Double currentMovementOriginalCostEstimate;
  private Integer costEstimateIndex;
  private boolean failed;
  private boolean recalcBP = true;
  private HashSet<BlockPos> toBreak = new HashSet<>();
  private HashSet<BlockPos> toPlace = new HashSet<>();
  private HashSet<BlockPos> toWalkInto = new HashSet<>();

  private final PathingBehavior behavior;
  private final IPlayerContext ctx;
  private final LiquidLocomotionController liquidLocomotion;
  private final List<PathSurfaceOverlayLeg> surfaceLegs;
  private PathSurfaceOverlayLeg activeSurfaceLeg;
  private SurfaceLineController surfaceLine;

  private boolean sprintNextTick;
  private TransportControl transportControl;
  private ControlFrame controlFrame = ControlFrame.EMPTY;

  PathRouteLegController(PathingBehavior behavior, IPath path) {
    this(behavior, path, true);
  }

  PathRouteLegController(PathingBehavior behavior, IPath path, boolean surfaceOverlay) {
    this.behavior = behavior;
    this.ctx = behavior.ctx;
    this.path = path;
    this.flatValidPositions = flatValidPositions(path);
    this.pathIndexByPosition = pathIndexByPosition(path);
    this.liquidLocomotion = new LiquidLocomotionController(ctx);
    this.surfaceLegs = surfaceOverlay ? surfaceLegs(behavior, path) : List.of();
    this.pathPosition = 0;
  }

  /**
   * Tick this executor
   *
   * @return True if a movement just finished (and the player is therefore in a "stable" state, like,
   * not sneaking out over lava), false otherwise
   */
  public boolean onTick() {
    transportControl = null;
    controlFrame = ControlFrame.EMPTY;
    sprintNextTick = false;
    ExecutionPolicy policy = ExecutionPolicy.capture(behavior);
    for (int revisions = 0; revisions < MAX_POSITION_REVISIONS_PER_TICK; revisions++) {
      if (pathPosition == path.length() - 1) {
        pathPosition++;
      }
      if (pathPosition >= path.length()) {
        return true; // stop bugging me, I'm done
      }
      PathSurfaceOverlayLeg surfaceLeg = surfaceLegAt(pathPosition);
      if (surfaceLeg != null) {
        if (activeSurfaceLeg != surfaceLeg) {
          activeSurfaceLeg = surfaceLeg;
          surfaceLine = new SurfaceLineController(behavior.baritone, surfaceLeg.segment());
          ticksOnCurrent = 0;
        }
        LegTickResult<SurfaceLinePhase> tick = surfaceLine.tick();
        controlFrame = tick.frame();
        transportControl = transportControl("SurfaceLineController", tick.status(), controlFrame);
        sprintNextTick = surfaceLineSprintRequested();
        if (tick.status() == UNREACHABLE || tick.status() == FAILED) {
          logDebug("Surface line returns status " + tick.status() + " in phase " + tick.phase());
          cancel();
          return true;
        }
        if (tick.status() == SUCCESS) {
          pathPosition = surfaceLeg.endExclusive();
          onChangeInPathPosition();
          continue;
        }
        ticksOnCurrent++;
        if (ticksOnCurrent > surfaceLeg.segment().cost() + policy.movementTimeoutTicks()) {
          logDebug("Surface line has taken too long (" + ticksOnCurrent + " ticks, expected " + surfaceLeg.segment().cost() + "). Cancelling.");
          cancel();
          return true;
        }
        return tick.safeToCancel();
      }
      Movement movement = (Movement) path.movements().get(pathPosition);
      int projectedWaterPosition = liquidLocomotion.projectedWaterPosition(path, pathPosition);
      if (projectedWaterPosition > pathPosition) {
        pathPosition = projectedWaterPosition;
        onChangeInPathPosition();
        continue;
      }
      BetterBlockPos whereAmI = ctx.playerFeet();
      if (!movement.acceptsPosition(whereAmI) && !movement.acceptsPathingDrift(whereAmI)) {
        boolean revised = false;
        for (int i = Math.min(pathPosition - 1, path.movements().size() - 1); i >= 0; i--) {//this happens for example when you lag out and get teleported back a couple blocks
          if (((Movement) path.movements().get(i)).acceptsPosition(whereAmI)) {
            int previousPos = pathPosition;
            pathPosition = i;
            for (int j = pathPosition; j <= previousPos; j++) {
              path.movements().get(j).reset();
            }
            onChangeInPathPosition();
            revised = true;
            break;
          }
        }
        if (revised) {
          continue;
        }
        for (int i = pathPosition + 3; i < path.length() - 1; i++) { //dont check pathPosition+1. the movement tells us when it's done (e.g. sneak placing)
          // also don't check pathPosition+2 because reasons
          if (((Movement) path.movements().get(i)).acceptsPosition(whereAmI)) {
            if (i - pathPosition > 2) {
              logDebug("Skipping forward " + (i - pathPosition) + " steps, to " + i);
            }
            //System.out.println("Double skip sundae");
            pathPosition = i - 1;
            onChangeInPathPosition();
            revised = true;
            break;
          }
        }
        if (revised) {
          continue;
        }
      }
      ClosestPathPosition status = closestPathPos();
      double sustainedPathTolerance = Math.max(MAX_DIST_FROM_PATH, movement.sustainedPathDistanceTolerance());
      double immediatePathTolerance = Math.max(MAX_MAX_DIST_FROM_PATH, movement.immediatePathDistanceTolerance());
      if (possiblyOffPath(status, sustainedPathTolerance)) {
        ticksAway++;
        System.out.println("FAR AWAY FROM PATH FOR " + ticksAway + " TICKS. Current distance: " + status.distance() + ". Threshold: " + sustainedPathTolerance);
        if (ticksAway > MAX_TICKS_AWAY) {
          logDebug("Too far away from path for too long, cancelling path");
          cancel();
          return false;
        }
      } else {
        ticksAway = 0;
      }
      if (possiblyOffPath(status, immediatePathTolerance)) { // ok, stop right away, we're way too far.
        logDebug("too far from path");
        cancel();
        return false;
      }
      //long start = System.nanoTime() / 1000000L;
      BlockStateInterface bsi = new BlockStateInterface(ctx);
      for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
        if (i < 0 || i >= path.movements().size()) {
          continue;
        }
        Movement m = (Movement) path.movements().get(i);
        List<BlockPos> prevBreak = m.toBreak(bsi);
        List<BlockPos> prevPlace = m.toPlace(bsi);
        List<BlockPos> prevWalkInto = m.toWalkInto(bsi);
        m.resetBlockCache();
        if (!prevBreak.equals(m.toBreak(bsi))) {
          recalcBP = true;
        }
        if (!prevPlace.equals(m.toPlace(bsi))) {
          recalcBP = true;
        }
        if (!prevWalkInto.equals(m.toWalkInto(bsi))) {
          recalcBP = true;
        }
      }
      if (recalcBP) {
        HashSet<BlockPos> newBreak = new HashSet<>();
        HashSet<BlockPos> newPlace = new HashSet<>();
        HashSet<BlockPos> newWalkInto = new HashSet<>();
        for (int i = pathPosition; i < path.movements().size(); i++) {
          Movement m = (Movement) path.movements().get(i);
          newBreak.addAll(m.toBreak(bsi));
          newPlace.addAll(m.toPlace(bsi));
          newWalkInto.addAll(m.toWalkInto(bsi));
        }
        toBreak = newBreak;
        toPlace = newPlace;
        toWalkInto = newWalkInto;
        recalcBP = false;
      }
      /*long end = System.nanoTime() / 1000000L;
      if (end - start > 0) {
          System.out.println("Recalculating break and place took " + (end - start) + "ms");
      }*/
      if (pathPosition < path.movements().size() - 1) {
        IMovement next = path.movements().get(pathPosition + 1);
        if (!behavior.baritone.bsi.worldContainsLoadedChunk(next.getDest().x, next.getDest().z)) {
          logDebug("Pausing since destination is at edge of loaded chunks");
          clearKeys();
          return true;
        }
      }
      boolean canCancel = movement.safeToCancel();
      if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
        costEstimateIndex = pathPosition;
        // do this only once, when the movement starts, and deliberately get the cost as cached when this path was calculated, not the cost as it is right now
        currentMovementOriginalCostEstimate = movement.getCost();
        for (int i = 1; i < policy.costVerificationLookahead() && pathPosition + i < path.length() - 1; i++) {
          if (((Movement) path.movements().get(pathPosition + i)).calculateCost(behavior.secretInternalGetCalculationContext()) >= ActionCosts.COST_INF && canCancel) {
            logDebug("Something has changed in the world and a future movement has become impossible. Cancelling.");
            cancel();
            return true;
          }
        }
      }
      double currentCost = movement.recalculateCost(behavior.secretInternalGetCalculationContext());
      if (currentCost >= ActionCosts.COST_INF && canCancel) {
        logDebug("Something has changed in the world and this movement has become impossible. Cancelling.");
        cancel();
        return true;
      }
      if (!movement.calculatedWhileLoaded() && currentCost - currentMovementOriginalCostEstimate > policy.maxCostIncrease() && canCancel) {
        // don't do this if the movement was calculated while loaded
        // that means that this isn't a cache error, it's just part of the path interfering with a later part
        logDebug("Original cost " + currentMovementOriginalCostEstimate + " current cost " + currentCost + ". Cancelling.");
        cancel();
        return true;
      }
      if (shouldPause()) {
        logDebug("Pausing since current best path is a backtrack");
        clearKeys();
        return true;
      }
      MovementTick movementTick = movement.tick(liquidLocomotion, path, pathPosition);
      MovementStatus movementStatus = movementTick.status();
      controlFrame = movementTick.frame();
      transportControl = movement.transportControl();
      if (movementStatus == UNREACHABLE || movementStatus == FAILED) {
        logDebug("Movement returns status " + movementStatus);
        cancel();
        return true;
      }
      if (movementStatus == SUCCESS) {
        //System.out.println("Movement done, next path");
        pathPosition++;
        onChangeInPathPosition();
        continue;
      } else {
        sprintNextTick = shouldSprintNextTick(policy);
        if (!sprintNextTick) {
          ctx.player().setSprinting(false); // letting go of control doesn't make you stop sprinting actually
        }
        ticksOnCurrent++;
        if (ticksOnCurrent > currentMovementOriginalCostEstimate + policy.movementTimeoutTicks()) {
          // only cancel if the total time has exceeded the initial estimate
          // as you break the blocks required, the remaining cost goes down, to the point where
          // ticksOnCurrent is greater than recalculateCost + 100
          // this is why we cache cost at the beginning, and don't recalculate for this comparison every tick
          logDebug("This movement has taken too long (" + ticksOnCurrent + " ticks, expected " + currentMovementOriginalCostEstimate + "). Cancelling.");
          cancel();
          return true;
        }
      }
      return canCancel; // movement is in progress, but if it reports cancellable, PathingBehavior is good to cut onto the next path
    }
    logDebug("Path position correction oscillated for " + MAX_POSITION_REVISIONS_PER_TICK + " revisions at " + ctx.playerFeet() + "; pausing this tick");
    clearKeys();
    return false;
  }

  private ClosestPathPosition closestPathPos() {
    double best = -1;
    BlockPos bestPos = null;
    for (BlockPos pos : flatValidPositions) {
      double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
      if (dist < best || best == -1) {
        best = dist;
        bestPos = pos;
      }
    }
    return new ClosestPathPosition(best, bestPos);
  }

  private boolean shouldPause() {
    Optional<AbstractNodeCostSearch> current = behavior.getInProgress();
    if (!current.isPresent()) {
      return false;
    }
    if (!ctx.player().onGround()) {
      return false;
    }
    if (pathIndex(current.get().getStart()) != -1) {
      return false;
    }
    if (!MovementHelper.canWalkOn(ctx, ctx.playerFeet().below())) {
      // we're in some kind of sketchy situation, maybe parkouring
      return false;
    }
    if (!MovementHelper.canWalkThrough(ctx, ctx.playerFeet()) || !MovementHelper.canWalkThrough(ctx, ctx.playerFeet().above())) {
      // suffocating?
      return false;
    }
    if (!path.movements().get(pathPosition).safeToCancel()) {
      return false;
    }
    Optional<IPath> currentBest = current.get().bestPathSoFar();
    if (!currentBest.isPresent()) {
      return false;
    }
    List<BetterBlockPos> positions = currentBest.get().positions();
    if (positions.size() < 3) {
      return false; // not long enough yet to justify pausing, its far from certain we'll actually take this route
    }
    // the first block of the next path will always overlap
    // no need to pause our very last movement when it would have otherwise cleanly exited with MovementStatus SUCCESS
    positions = positions.subList(1, positions.size());
    return positions.contains(ctx.playerFeet());
  }

  private boolean possiblyOffPath(ClosestPathPosition status, double leniency) {
    double distanceFromPath = status.distance();
    if (distanceFromPath > leniency) {
      // when we're midair in the middle of a fall, we're very far from both the beginning and the end, but we aren't actually off path
      if (path.movements().get(pathPosition) instanceof MovementFall) {
        BlockPos fallDest = path.positions().get(pathPosition + 1); // .get(pathPosition) is the block we fell off of
        return VecUtils.entityFlatDistanceToCenter(ctx.player(), fallDest) >= leniency; // ignore Y by using flat distance
      } else {
        return true;
      }
    } else {
      return false;
    }
  }

  /**
   * Regardless of current path position, snap to the current player feet if possible
   *
   * @return Whether or not it was possible to snap to the current player feet
   */
  public boolean snipsnapifpossible() {
    if (!ctx.player().onGround() && ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
      // if we're falling in the air, and not in water, don't splice
      return false;
    } else {
      // we are either onGround or in liquid
      if (ctx.player().getDeltaMovement().y < -0.1) {
        // if we are strictly moving downwards (not stationary)
        // we could be falling through water, which could be unsafe to splice
        return false; // so don't
      }
    }
    int index = pathIndex(ctx.playerFeet());
    if (index == -1) {
      return false;
    }
    pathPosition = index; // jump directly to current position
    clearKeys();
    return true;
  }

  private boolean shouldSprintNextTick(ExecutionPolicy policy) {
    boolean requested = controlFrame.input(Input.SPRINT);

    // we'll take it from here, no need for minecraft to see we're holding down control and sprint for us
    controlFrame = controlFrame.mutate().setInput(Input.SPRINT, false).build();

    // first and foremost, if allowSprint is off, or if we don't have enough hunger, don't try and sprint
    if (!new CalculationContext(behavior.baritone, false).movement.canSprint()) {
      return false;
    }
    IMovement current = path.movements().get(pathPosition);

    // traverse requests sprinting, so we need to do this check first
    if (current instanceof MovementTraverse && pathPosition < path.length() - 3) {
      IMovement next = path.movements().get(pathPosition + 1);
      if (next instanceof MovementAscend && sprintableAscend(policy, ctx, (MovementTraverse) current, (MovementAscend) next, path.movements().get(pathPosition + 2))) {
        if (skipNow(ctx, current)) {
          logDebug("Skipping traverse to straight ascend");
          pathPosition++;
          onChangeInPathPosition();
          onTick();
          controlFrame = controlFrame.mutate().setInput(Input.JUMP, !((MovementAscend) next).stairStepAscent()).build();
          return true;
        } else {
          logDebug("Too far to the side to safely sprint ascend");
        }
      }
    }

    // if the movement requested sprinting, then we're done
    if (requested) {
      return true;
    }

    // however, descend and ascend don't request sprinting, because they don't know the context of what movement comes after it
    if (current instanceof MovementDescend) {

      if (pathPosition < path.length() - 2) {
        // keep this out of onTick, even if that means a tick of delay before it has an effect
        IMovement next = path.movements().get(pathPosition + 1);
        if (MovementHelper.canUseFrostWalker(ctx, next.getDest().below())) {
          // frostwalker only works if you cross the edge of the block on ground so in some cases we may not overshoot
          // Since MovementDescend can't know the next movement we have to tell it
          if (next instanceof MovementTraverse || next instanceof MovementParkour) {
            boolean couldPlaceInstead = policy.canPlaceGeneric() && next instanceof MovementParkour; // traverse doesn't react fast enough
            // this is true if the next movement does not ascend or descends and goes into the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend
            // in that case current.getDirection() is e.g. (0, -1, 1) and next.getDirection() is e.g. (0, 0, 3) so the cross product of (0, 0, 1) and (0, 0, 3) is taken, which is (0, 0, 0) because the vectors are colinear (don't form a plane)
            // since movements in exactly the opposite direction (e.g. descend (0, -1, 1) and traverse (0, 0, -1)) would also pass this check we also have to rule out that case
            // we can do that by adding the directions because traverse is always 1 long like descend and parkour can't jump through current.getSrc().down()
            boolean sameFlatDirection =
              !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO) && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO); // here's why you learn maths in school
            if (sameFlatDirection && !couldPlaceInstead) {
              ((MovementDescend) current).forceSafeMode();
            }
          }
        }
      }
      if (((MovementDescend) current).safeMode() && !((MovementDescend) current).skipToAscend()) {
        logDebug("Sprinting would be unsafe");
        return false;
      }

      if (pathPosition < path.length() - 2) {
        IMovement next = path.movements().get(pathPosition + 1);
        if (next instanceof MovementAscend && current.getDirection().above().equals(next.getDirection().below())) {
          // a descend then an ascend in the same direction
          pathPosition++;
          onChangeInPathPosition();
          onTick();
          // okay to skip clearKeys and / or onChangeInPathPosition here since this isn't possible to repeat, since it's asymmetric
          logDebug("Skipping descend to straight ascend");
          return true;
        }
        if (canSprintFromDescendInto(policy, ctx, current, next)) {

          if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
            IMovement next_next = path.movements().get(pathPosition + 2);
            if (next_next instanceof MovementDescend && !canSprintFromDescendInto(policy, ctx, next, next_next)) {
              return false;
            }

          }
          if (ctx.playerFeet().equals(current.getDest())) {
            pathPosition++;
            onChangeInPathPosition();
            onTick();
          }

          return true;
        }
        //logDebug("Turning off sprinting " + movement + " " + next + " " + movement.getDirection() + " " + next.getDirection().down() + " " + next.getDirection().down().equals(movement.getDirection()));
      }
    }
    if (current instanceof MovementAscend && pathPosition != 0) {
      IMovement prev = path.movements().get(pathPosition - 1);
      if (prev instanceof MovementDescend && prev.getDirection().above().equals(current.getDirection().below())) {
        BlockPos center = current.getSrc().above();
        // playerFeet adds 0.1251 to account for soul sand
        // farmland is 0.9375
        // 0.07 is to account for farmland
        if (ctx.player().position().y >= center.getY() - 0.07) {
          controlFrame = controlFrame.mutate().setInput(Input.JUMP, false).build();
          return true;
        }
      }
      if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse
        && sprintableAscend(policy, ctx, (MovementTraverse) prev, (MovementAscend) current, path.movements().get(pathPosition + 1))) {
        return true;
      }
    }
    if (current instanceof MovementFall) {
      Tuple<Vec3, BlockPos> data = overrideFall((MovementFall) current);
      if (data != null) {
        BetterBlockPos fallDest = new BetterBlockPos(data.getB());
        int fallDestIndex = pathIndex(fallDest);
        if (fallDestIndex == -1) {
          throw new IllegalStateException(String.format("Fall override at %s %s %s returned illegal destination %s %s %s", current.getSrc(), fallDest));
        }
        if (ctx.playerFeet().equals(fallDest)) {
          pathPosition = fallDestIndex;
          onChangeInPathPosition();
          onTick();
          return true;
        }
        clearKeys();
        controlFrame = ControlFrame.builder().setTarget(new ControlFrame.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), data.getA(), ctx.playerRotations()), false))
          .setInput(Input.MOVE_FORWARD, true).build();
        return true;
      }
    }
    return false;
  }

  private boolean surfaceLineSprintRequested() {
    return controlFrame.input(Input.SPRINT) && new CalculationContext(behavior.baritone, false).movement.canSprint();
  }

  private Tuple<Vec3, BlockPos> overrideFall(MovementFall movement) {
    Vec3i dir = movement.getDirection();
    if (dir.getY() < -3) {
      return null;
    }
    if (!movement.toBreakCached.isEmpty()) {
      return null; // it's breaking
    }
    Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
    int i;
    outer : for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
      IMovement next = path.movements().get(i);
      if (!(next instanceof MovementTraverse)) {
        break;
      }
      if (!flatDir.equals(next.getDirection())) {
        break;
      }
      for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
        BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
        if (!MovementHelper.fullyPassable(ctx, chk)) {
          break outer;
        }
      }
      if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
        break;
      }
    }
    i--;
    if (i == pathPosition) {
      return null; // no valid extension exists
    }
    double len = i - pathPosition - 0.4;
    return new Tuple<>(new Vec3(flatDir.getX() * len + movement.getDest().x + 0.5, movement.getDest().y, flatDir.getZ() * len + movement.getDest().z + 0.5),
      movement.getDest().offset(flatDir.getX() * (i - pathPosition), 0, flatDir.getZ() * (i - pathPosition)));
  }

  private int pathIndex(BlockPos pos) {
    return pathIndexByPosition.get(pos.asLong());
  }

  public boolean containsPathPosition(BlockPos pos) {
    return pathIndex(pos) != -1;
  }

  private static BlockPos[] flatValidPositions(IPath path) {
    ArrayList<BlockPos> validPositions = new ArrayList<>();
    LongOpenHashSet seen = new LongOpenHashSet();
    for (IMovement movement : path.movements()) {
      for (BlockPos pos : ((Movement) movement).getValidPositions()) {
        if (seen.add(pos.asLong())) {
          validPositions.add(pos);
        }
      }
    }
    return validPositions.toArray(BlockPos[]::new);
  }

  private static Long2IntOpenHashMap pathIndexByPosition(IPath path) {
    List<BetterBlockPos> positions = path.positions();
    Long2IntOpenHashMap indices = new Long2IntOpenHashMap(positions.size());
    indices.defaultReturnValue(-1);
    for (int i = 0; i < positions.size(); i++) {
      long key = positions.get(i).asLong();
      if (!indices.containsKey(key)) {
        indices.put(key, i);
      }
    }
    List<IMovement> movements = path.movements();
    for (int i = 0; i < movements.size(); i++) {
      for (BlockPos pos : ((Movement) movements.get(i)).getValidPositions()) {
        long key = pos.asLong();
        if (!indices.containsKey(key)) {
          indices.put(key, i);
        }
      }
    }
    return indices;
  }

  private static List<PathSurfaceOverlayLeg> surfaceLegs(PathingBehavior behavior, IPath path) {
    CalculationContext context = behavior.secretInternalGetCalculationContext();
    if (context == null) {
      return List.of();
    }
    ArrayList<Movement> movements = new ArrayList<>(path.movements().size());
    for (IMovement movement : path.movements()) {
      movements.add((Movement) movement);
    }
    return SurfaceWaterDomain.route(context, movements);
  }

  private PathSurfaceOverlayLeg surfaceLegAt(int position) {
    for (PathSurfaceOverlayLeg leg : surfaceLegs) {
      if (leg.contains(position)) {
        return leg;
      }
      if (leg.startIndex() > position) {
        return null;
      }
    }
    return null;
  }

  void appendRenderPlan(ArrayList<RouteRenderPlan.Segment> segments, ArrayList<RouteRenderPlan.Anchor> anchors, boolean current) {
    List<BetterBlockPos> positions = path.positions();
    if (positions.size() < 2) {
      return;
    }
    int cursor = current ? Math.max(pathPosition - 3, 0) : 0;
    if (surfaceLegs.isEmpty()) {
      addPathRenderSegment(segments, positions, cursor, positions.size() - 1, TransportMode.PEDESTRIAN, false, current, -1);
      return;
    }
    for (PathSurfaceOverlayLeg leg : surfaceLegs) {
      if (leg.endExclusive() <= cursor) {
        continue;
      }
      if (leg.startIndex() > cursor) {
        addPathRenderSegment(segments, positions, cursor, Math.min(leg.startIndex(), positions.size() - 1), TransportMode.PEDESTRIAN, false, current, -1);
      }
      segments.add(new RouteRenderPlan.Segment(leg.segment().mode(), leg.segment().terminal(), leg.contains(pathPosition), -1, List.of(leg.segment().waterStart(), leg.segment().waterEnd()), 0));
      anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.LAUNCH, leg.segment().src()));
      anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.ENTRY, leg.segment().waterStart()));
      if (leg.segment().terminal()) {
        anchors.add(new RouteRenderPlan.Anchor(RouteRenderPlan.AnchorKind.TERMINAL, leg.segment().dest()));
      }
      cursor = Math.max(cursor, leg.endExclusive());
    }
    addPathRenderSegment(segments, positions, cursor, positions.size() - 1, TransportMode.PEDESTRIAN, false, current, -1);
  }

  private static void addPathRenderSegment(ArrayList<RouteRenderPlan.Segment> segments, List<BetterBlockPos> positions, int start, int endInclusive, TransportMode mode, boolean terminal,
    boolean active, int componentId) {
    if (endInclusive <= start || start < 0 || start >= positions.size()) {
      return;
    }
    int end = Math.min(endInclusive, positions.size() - 1);
    segments.add(new RouteRenderPlan.Segment(mode, terminal, active, componentId, List.copyOf(positions.subList(start, end + 1)), 0));
  }

  private static TransportControl transportControl(String movement, MovementStatus status, ControlFrame frame) {
    Optional<Rotation> rotation = frame.target().getRotation();
    return new TransportControl(movement, status, rotation.map(Rotation::getYaw).orElse(null), rotation.map(Rotation::getPitch).orElse(null), frame.target().hasToForceRotations());
  }

  private record ClosestPathPosition(double distance, BlockPos pos) {
  }

  private record ExecutionPolicy(int costVerificationLookahead, double maxCostIncrease, int movementTimeoutTicks, boolean canPlaceGeneric, boolean sprintAscends, boolean allowOvershootDiagonalDescend,
    int maxPathHistoryLength, int pathHistoryCutoffAmount) {
    static ExecutionPolicy capture(PathingBehavior behavior) {
      return new ExecutionPolicy(Baritone.settings().costVerificationLookahead.value, Baritone.settings().maxCostIncrease.value, Baritone.settings().movementTimeoutTicks.value,
        Baritone.settings().allowPlace.value && behavior.baritone.getInventoryBehavior().hasGenericThrowaway(), Baritone.settings().sprintAscends.value,
        Baritone.settings().allowOvershootDiagonalDescend.value, Baritone.settings().maxPathHistoryLength.value, Baritone.settings().pathHistoryCutoffAmount.value);
    }
  }

  private static boolean skipNow(IPlayerContext ctx, IMovement current) {
    double offTarget = Math.abs(current.getDirection().getX() * (current.getSrc().z + 0.5D - ctx.player().position().z))
      + Math.abs(current.getDirection().getZ() * (current.getSrc().x + 0.5D - ctx.player().position().x));
    if (offTarget > 0.1) {
      return false;
    }
    // we are centered
    BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
    if (MovementHelper.fullyPassable(ctx, headBonk)) {
      return true;
    }
    // wait 0.3
    double flatDist =
      Math.abs(current.getDirection().getX() * (headBonk.getX() + 0.5D - ctx.player().position().x)) + Math.abs(current.getDirection().getZ() * (headBonk.getZ() + 0.5 - ctx.player().position().z));
    return flatDist > 0.8;
  }

  private static boolean sprintableAscend(ExecutionPolicy policy, IPlayerContext ctx, MovementTraverse current, MovementAscend next, IMovement nextnext) {
    if (!policy.sprintAscends()) {
      return false;
    }
    if (!current.getDirection().equals(next.getDirection().below())) {
      return false;
    }
    if (nextnext.getDirection().getX() != next.getDirection().getX() || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
      return false;
    }
    if (!MovementHelper.canWalkOn(ctx, current.getDest().below())) {
      return false;
    }
    if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
      return false;
    }
    if (!next.toBreakCached.isEmpty()) {
      return false; // it's breaking
    }
    for (int x = 0; x < 2; x++) {
      for (int y = 0; y < 3; y++) {
        BlockPos chk = current.getSrc().above(y);
        if (x == 1) {
          chk = chk.offset(current.getDirection());
        }
        if (!MovementHelper.fullyPassable(ctx, chk)) {
          return false;
        }
      }
    }
    if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(current.getSrc().above(3)))) {
      return false;
    }
    return !MovementHelper.avoidWalkingInto(ctx.world().getBlockState(next.getDest().above(2))); // codacy smh my head
  }

  private static boolean canSprintFromDescendInto(ExecutionPolicy policy, IPlayerContext ctx, IMovement current, IMovement next) {
    if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
      return true;
    }
    if (!MovementHelper.canWalkOn(ctx, current.getDest().offset(current.getDirection()))) {
      return false;
    }
    if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
      return true;
    }
    return next instanceof MovementDiagonal && policy.allowOvershootDiagonalDescend();
  }

  private void onChangeInPathPosition() {
    clearKeys();
    ticksOnCurrent = 0;
    activeSurfaceLeg = null;
    surfaceLine = null;
  }

  private void clearKeys() {
    controlFrame = ControlFrame.EMPTY;
  }

  private void cancel() {
    clearKeys();
    pathPosition = path.length() + 3;
    failed = true;
  }

  @Override
  public int getPosition() { return pathPosition; }

  @Override
  public int size() {
    return path.movements().size();
  }

  public TransportControl transportControl() {
    return transportControl;
  }

  public ControlFrame controlFrame() {
    return controlFrame;
  }

  public TransportSnapshot.Plan transportPlan(IPlayerContext ctx) {
    if (surfaceLine != null) {
      return surfaceLine.transportPlan();
    }
    if (pathPosition < 0 || pathPosition >= path.movements().size()) {
      return null;
    }
    IMovement movement = path.movements().get(pathPosition);
    return movement instanceof Movement concrete ? concrete.transportPlan() : TransportSnapshot.Plan.pedestrian(movement.getClass().getSimpleName(), movement.getSrc(), movement.getDest());
  }

  public PathRouteLegController trySplice(PathRouteLegController next) {
    if (next == null) {
      return cutIfTooLong();
    }
    return SplicedPath.trySplice(path, next.path, false).map(path -> {
      if (!path.getDest().equals(next.getPath().getDest())) {
        throw new IllegalStateException(String.format("Path has end %s instead of %s after splicing", path.getDest(), next.getPath().getDest()));
      }
      return transplant(path, pathPosition, costEstimateIndex);
    }).orElseGet(this::cutIfTooLong); // dont actually call cutIfTooLong every tick if we won't actually use it, use a method reference
  }

  public Optional<PathRouteLegController> tryReplaceSuffix(PathRouteLegController replacement, int minimumAnchorIndex) {
    if (replacement == null) {
      return Optional.empty();
    }
    return SplicedPath.tryReplaceSuffix(path, replacement.path, minimumAnchorIndex).map(path -> {
      if (!path.getDest().equals(replacement.getPath().getDest())) {
        throw new IllegalStateException(String.format("Path has end %s instead of %s after suffix replacement", path.getDest(), replacement.getPath().getDest()));
      }
      return transplant(path, pathPosition, costEstimateIndex);
    });
  }

  private PathRouteLegController cutIfTooLong() {
    ExecutionPolicy policy = ExecutionPolicy.capture(behavior);
    if (pathPosition > policy.maxPathHistoryLength()) {
      int cutoffAmt = policy.pathHistoryCutoffAmount();
      CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
      if (!newPath.getDest().equals(path.getDest())) {
        throw new IllegalStateException(String.format("Path has end %s instead of %s after trimming its start", newPath.getDest(), path.getDest()));
      }
      logDebug("Discarding earliest segment movements, length cut from " + path.length() + " to " + newPath.length());
      return transplant(newPath, pathPosition - cutoffAmt, costEstimateIndex == null ? null : costEstimateIndex - cutoffAmt);
    }
    return this;
  }

  private PathRouteLegController transplant(IPath path, int pathPosition, Integer costEstimateIndex) {
    PathRouteLegController ret = new PathRouteLegController(behavior, path);
    ret.liquidLocomotion.copyFrom(liquidLocomotion);
    ret.pathPosition = pathPosition;
    ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
    ret.costEstimateIndex = costEstimateIndex;
    ret.ticksOnCurrent = ticksOnCurrent;
    return ret;
  }

  public IPath getPath() { return path; }

  public boolean failed() {
    return failed;
  }

  public boolean finished() {
    return pathPosition >= path.length();
  }

  public Set<BlockPos> toBreak() {
    return Collections.unmodifiableSet(toBreak);
  }

  public Set<BlockPos> toPlace() {
    return Collections.unmodifiableSet(toPlace);
  }

  public Set<BlockPos> toWalkInto() {
    return Collections.unmodifiableSet(toWalkInto);
  }

  public boolean isSprinting() { return sprintNextTick; }
}
