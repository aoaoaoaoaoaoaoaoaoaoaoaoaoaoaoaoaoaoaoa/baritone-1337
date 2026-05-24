package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.macro.core.MacroActionInstance;
import baritone.pathing.macro.core.MacroActionKind;
import baritone.pathing.macro.core.MacroCapabilities;
import baritone.pathing.macro.core.MacroNodeKey;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.macro.core.MacroPolicy;
import baritone.pathing.macro.portal.PortalFrame;
import baritone.pathing.meso.MesoTaskBudget;
import baritone.pathing.meso.MesoTaskRequest;
import baritone.pathing.meso.MesoTaskResult;
import baritone.pathing.meso.portal.PortalMesoSiter;
import baritone.pathing.meso.portal.PortalTaskIntent;
import baritone.pathing.meso.portal.PortalTaskPlan;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class PortalTaskProcess extends BaritoneProcessHelper {
  private static final int ACTIVATION_RADIUS = 12;
  private static final int ACTIVATION_RADIUS_SQ = ACTIVATION_RADIUS * ACTIVATION_RADIUS;
  private static final int PORTAL_WAIT_TIMEOUT_TICKS = 300;
  private static final int IGNITION_RETRY_TICKS = 8;

  private final PortalMesoSiter siter = new PortalMesoSiter();
  private Execution execution = new Execution.Idle();
  private ActionKey rejected;

  public PortalTaskProcess(Baritone baritone) {
    super(baritone);
  }

  @Override
  public boolean isActive() { return !(execution instanceof Execution.Idle) || eligibleAction().isPresent(); }

  public Optional<MacroPlan> activeMacroPlan() {
    return switch (execution) {
      case Execution.Idle ignored -> Optional.empty();
      case Execution.Traveling traveling -> Optional.of(traveling.plan());
      case Execution.Entering entering -> Optional.of(entering.plan());
      case Execution.Building building -> Optional.of(building.macroPlan());
    };
  }

  @Override
  public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
    if (!(execution instanceof Execution.Idle) && (ctx.world() == null || ctx.player() == null)) {
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
    return switch (execution) {
      case Execution.Idle ignored -> start();
      case Execution.Traveling traveling -> travel(traveling);
      case Execution.Entering entering -> enter(entering);
      case Execution.Building building -> build(building);
    };
  }

  private PathingCommand start() {
    Optional<EligibleAction> action = eligibleAction();
    if (action.isEmpty()) {
      return null;
    }
    return solveAndBegin(action.get());
  }

  private PathingCommand solveAndBegin(EligibleAction action) {
    MacroActionInstance instance = action.action();
    PortalTaskIntent intent = (PortalTaskIntent) instance.taskIntent();
    CalculationContext calculation = new CalculationContext(baritone, true);
    MesoTaskRequest<PortalTaskIntent> request = new MesoTaskRequest<>(calculation, ctx.playerFeet(), MacroCapabilities.physical(calculation), MacroPolicy.configured(), intent);
    return switch (siter.solve(request, MesoTaskBudget.FAST)) {
      case MesoTaskResult.Solved<PortalTaskPlan> solved -> begin(action, solved.plan());
      case MesoTaskResult.NeedSurvey<PortalTaskPlan> survey -> new PathingCommand(survey.surveyGoal(), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
      case MesoTaskResult.RefinedTooExpensive<PortalTaskPlan> ignored -> reject(instance, "portal task refined too expensive");
      case MesoTaskResult.Impossible<PortalTaskPlan> impossible -> reject(instance, "portal task impossible: " + impossible.failure().code() + " " + impossible.failure().detail());
    };
  }

  private PathingCommand begin(EligibleAction eligible, PortalTaskPlan plan) {
    MacroActionInstance action = eligible.action();
    Goal terminalGoal = baritone.getCustomGoalProcess().mostRecentGoal();
    if (terminalGoal == null) {
      return reject(action, "portal task has no terminal custom goal to restore");
    }
    int sourceDimension = MacroNodeKey.dimensionId(action.fromNode());
    rejected = null;
    logDebug("Executing macro portal task " + action.kind() + " at " + plan.approachAnchor());
    baritone.getPathingBehavior().pinMacroPlan(eligible.plan());
    execution = switch (plan) {
      case PortalTaskPlan.EnterExisting enter -> new Execution.Entering(terminalGoal, eligible.plan(), sourceDimension, enter.portalBlock(), eligible.remaining(), 0, false);
      case PortalTaskPlan.BuildPortal build -> new Execution.Building(terminalGoal, eligible.plan(), sourceDimension, build, eligible.remaining(), BuildPhase.START_BUILD, 0, 0);
    };
    return onTick(false, true);
  }

  private PathingCommand travel(Execution.Traveling traveling) {
    if (MacroNodeKey.dimensionId(ctx.world().dimension()) != MacroNodeKey.dimensionId(traveling.action().fromNode())) {
      return continueOrComplete(traveling.terminalGoal(), traveling.plan(), traveling.remaining());
    }
    if (near(traveling.action())) {
      return solveAndBegin(new EligibleAction(traveling.plan(), traveling.action(), traveling.remaining()));
    }
    if (traveling.ticks() > PORTAL_WAIT_TIMEOUT_TICKS * 20) {
      logDebug("Timed out walking to chained portal task; restoring terminal goal");
      return complete(traveling.terminalGoal());
    }
    execution = traveling.tick();
    return new PathingCommand(new GoalGetToBlock(traveling.action().renderPositions().getFirst()), PathingCommandType.SET_GOAL_AND_PATH);
  }

  private PathingCommand enter(Execution.Entering entering) {
    if (dimensionChanged(entering.sourceDimension())) {
      return continueOrComplete(entering.terminalGoal(), entering.plan(), entering.remaining());
    }
    if (insidePortal() || entering.entered()) {
      baritone.getInputOverrideHandler().clearAllKeys();
      if (entering.ticks() > PORTAL_WAIT_TIMEOUT_TICKS) {
        logDebug("Timed out waiting in portal at " + entering.portalBlock() + "; restoring terminal goal");
        return complete(entering.terminalGoal());
      }
      execution = entering.markEntered().tick();
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
    if (!ctx.world().getBlockState(entering.portalBlock()).is(Blocks.NETHER_PORTAL)) {
      logDebug("Portal task lost lit portal at " + entering.portalBlock() + "; restoring terminal goal");
      return complete(entering.terminalGoal());
    }
    if (entering.ticks() > PORTAL_WAIT_TIMEOUT_TICKS) {
      logDebug("Timed out waiting in portal at " + entering.portalBlock() + "; restoring terminal goal");
      return complete(entering.terminalGoal());
    }
    execution = entering.tick();
    if (closeEnoughForManualPortalEntry(entering.portalBlock())) {
      steerIntoPortal(entering.portalBlock());
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
    return new PathingCommand(new GoalBlock(entering.portalBlock()), PathingCommandType.SET_GOAL_AND_PATH);
  }

  private boolean closeEnoughForManualPortalEntry(BetterBlockPos portalBlock) {
    BetterBlockPos feet = ctx.playerFeet();
    int dx = feet.x - portalBlock.x;
    int dz = feet.z - portalBlock.z;
    return Math.abs(feet.y - portalBlock.y) <= 2 && dx * dx + dz * dz <= 1;
  }

  private void steerIntoPortal(BetterBlockPos portalBlock) {
    Vec3 target = Vec3.atCenterOf(portalBlock);
    baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()), true);
    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    if (portalBlock.y > ctx.playerFeet().y) {
      baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
    }
  }

  private PathingCommand build(Execution.Building building) {
    if (dimensionChanged(building.sourceDimension())) {
      return continueOrComplete(building.terminalGoal(), building.macroPlan(), building.remaining());
    }
    Optional<BetterBlockPos> lit = litInterior(building.plan().frame());
    if (lit.isPresent()) {
      execution = new Execution.Entering(building.terminalGoal(), building.macroPlan(), building.sourceDimension(), lit.get(), building.remaining(), 0, false);
      return onTick(false, true);
    }
    if (frameComplete(building.plan().frame())) {
      return ignite(building.withPhase(BuildPhase.IGNITING));
    }
    Optional<PathingCommand> direct = tryDirectFramePlacement(building);
    if (direct.isPresent()) {
      execution = building.withPhase(BuildPhase.DIRECT_BUILDING).tick();
      return direct.get();
    }
    if (baritone.getBuilderProcess().isActive()) {
      execution = building.withPhase(BuildPhase.BUILDING).tick();
      return new PathingCommand(null, PathingCommandType.DEFER);
    }
    if (building.phase() != BuildPhase.BUILDING) {
      baritone.getBuilderProcess().buildPortalFrame(building.plan().frame().lowerLeftInterior(), building.plan().frame().axis());
      execution = building.withPhase(BuildPhase.BUILDING).tick();
      return new PathingCommand(null, PathingCommandType.DEFER);
    }
    if (building.ticks() > PORTAL_WAIT_TIMEOUT_TICKS) {
      logDebug("Timed out building or lighting portal at " + building.plan().frame().lowerLeftInterior() + "; restoring terminal goal");
      return complete(building.terminalGoal());
    }
    return ignite(building);
  }

  private Optional<PathingCommand> tryDirectFramePlacement(Execution.Building building) {
    PortalFrame.FrameMatch frame = building.plan().frame();
    for (FrameOffset offset : directFrameOffsets(frame)) {
      BetterBlockPos target = offset.apply(frame);
      BlockState current = ctx.world().getBlockState(target);
      if (current.is(Blocks.OBSIDIAN)) {
        continue;
      }
      if (!MovementHelper.isReplaceable(target.x, target.y, target.z, current, baritone.bsi)) {
        return Optional.empty();
      }
      Optional<BlockHitResult> hit = placementHit(target);
      if (hit.isEmpty()) {
        continue;
      }
      if (!baritone.getInventoryBehavior().throwaway(true, stack -> stack.is(Items.OBSIDIAN))) {
        logDebug("Portal task cannot build: no obsidian");
        return Optional.of(complete(building.terminalGoal()));
      }
      RotationUtils.reachable(ctx, hit.get().getBlockPos(), ctx.playerController().getBlockReachDistance()).ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, true));
      InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit.get());
      if (result.consumesAction()) {
        ctx.player().swing(InteractionHand.MAIN_HAND);
        return Optional.of(new PathingCommand(null, PathingCommandType.REQUEST_PAUSE));
      }
    }
    return Optional.empty();
  }

  private List<FrameOffset> directFrameOffsets(PortalFrame.FrameMatch frame) {
    if (!canSpendFullFrame(frame)) {
      return FrameOffset.MINIMAL;
    }
    return FrameOffset.FULL;
  }

  private boolean canSpendFullFrame(PortalFrame.FrameMatch frame) {
    int available = baritone.getInventoryBehavior().obsidianBlocks();
    for (FrameOffset offset : FrameOffset.FULL) {
      if (ctx.world().getBlockState(offset.apply(frame)).is(Blocks.OBSIDIAN)) {
        available++;
      }
    }
    return available >= FrameOffset.FULL.size();
  }

  private Optional<BlockHitResult> placementHit(BetterBlockPos target) {
    for (Direction towardSupport : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
      BetterBlockPos support = target.relative(towardSupport);
      BlockState supportState = ctx.world().getBlockState(support);
      if (MovementHelper.isReplaceable(support.x, support.y, support.z, supportState, baritone.bsi)) {
        continue;
      }
      Direction face = towardSupport.getOpposite();
      Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
      double reach = ctx.playerController().getBlockReachDistance();
      if (ctx.playerHead().distanceToSqr(hit) > reach * reach) {
        continue;
      }
      return Optional.of(new BlockHitResult(hit, face, support, false));
    }
    return Optional.empty();
  }

  private PathingCommand ignite(Execution.Building building) {
    PortalFrame.FrameMatch frame = building.plan().frame();
    Optional<IgnitionClick> click = ignitionClick(frame);
    if (click.isEmpty()) {
      execution = building.withPhase(BuildPhase.IGNITING).tick();
      return new PathingCommand(new GoalGetToBlock(ignitionTarget(frame)), PathingCommandType.SET_GOAL_AND_PATH);
    }
    baritone.getLookBehavior().updateTarget(click.get().rotation(), true);
    Optional<InteractionHand> hand = flintAndSteelHand();
    if (hand.isEmpty()) {
      if (!baritone.getInventoryBehavior().throwaway(true, stack -> stack.is(Items.FLINT_AND_STEEL))) {
        logDebug("Portal task cannot ignite: no flint and steel");
        return complete(building.terminalGoal());
      }
      execution = building.withPhase(BuildPhase.IGNITING).tick();
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
    if (building.ignitionCooldown() > 0) {
      execution = building.cooldown().tick();
      return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }
    InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand.get(), click.get().hit());
    if (result.consumesAction()) {
      ctx.player().swing(hand.get());
    }
    execution = building.withPhase(BuildPhase.IGNITING).withCooldown(IGNITION_RETRY_TICKS).tick();
    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
  }

  private Optional<EligibleAction> eligibleAction() {
    if (!(execution instanceof Execution.Idle) || ctx.world() == null || ctx.player() == null || baritone.getPathingBehavior().getCurrent() != null
      || baritone.getPathingBehavior().getInProgress().isPresent() || baritone.getCustomGoalProcess().mostRecentGoal() == null) {
      return Optional.empty();
    }
    int dimension = MacroNodeKey.dimensionId(ctx.world().dimension());
    Optional<MacroPlan> plan = baritone.getPathingBehavior().getMacroPlan();
    if (plan.isEmpty()) {
      return Optional.empty();
    }
    MacroPlan macroPlan = plan.get();
    List<MacroActionInstance> actions = macroPlan.actions();
    for (int i = 0; i < actions.size(); i++) {
      MacroActionInstance action = actions.get(i);
      if (action.kind().portal() && action.taskIntent() instanceof PortalTaskIntent && !MacroNodeKey.anchorKey(action.fromNode()) && MacroNodeKey.dimensionId(action.fromNode()) == dimension
        && !new ActionKey(action).equals(rejected) && near(action)) {
        return Optional.of(new EligibleAction(macroPlan, action, actions.subList(i + 1, actions.size())));
      }
    }
    return Optional.empty();
  }

  private boolean near(MacroActionInstance action) {
    BetterBlockPos feet = ctx.playerFeet();
    for (BetterBlockPos pos : action.renderPositions()) {
      int dx = feet.x - pos.x;
      int dz = feet.z - pos.z;
      if (Math.abs(feet.y - pos.y) <= ACTIVATION_RADIUS && dx * dx + dz * dz <= ACTIVATION_RADIUS_SQ) {
        return true;
      }
    }
    return false;
  }

  private PathingCommand reject(MacroActionInstance action, String reason) {
    rejected = new ActionKey(action);
    logDebug(reason);
    execution = new Execution.Idle();
    return null;
  }

  private PathingCommand complete(Goal terminalGoal) {
    execution = new Execution.Idle();
    baritone.getInputOverrideHandler().clearAllKeys();
    if (terminalGoal != null) {
      baritone.getCustomGoalProcess().setGoal(terminalGoal);
      baritone.getCustomGoalProcess().path();
    }
    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
  }

  private PathingCommand continueOrComplete(Goal terminalGoal, MacroPlan macroPlan, List<MacroActionInstance> remaining) {
    int dimension = MacroNodeKey.dimensionId(ctx.world().dimension());
    for (int i = 0; i < remaining.size(); i++) {
      MacroActionInstance action = remaining.get(i);
      if (action.kind().portal() && action.taskIntent() instanceof PortalTaskIntent && !MacroNodeKey.anchorKey(action.fromNode()) && MacroNodeKey.dimensionId(action.fromNode()) == dimension) {
        execution = new Execution.Traveling(terminalGoal, macroPlan, action, remaining.subList(i + 1, remaining.size()), 0);
        return travel((Execution.Traveling) execution);
      }
    }
    return complete(terminalGoal);
  }

  private boolean dimensionChanged(int sourceDimension) {
    return MacroNodeKey.dimensionId(ctx.world().dimension()) != sourceDimension;
  }

  private boolean insidePortal() {
    return ctx.world().getBlockState(ctx.playerFeet()).is(Blocks.NETHER_PORTAL) || ctx.world().getBlockState(ctx.playerFeet().above()).is(Blocks.NETHER_PORTAL);
  }

  private Optional<BetterBlockPos> litInterior(PortalFrame.FrameMatch frame) {
    for (int width = 0; width < PortalFrame.INNER_WIDTH; width++) {
      for (int height = 0; height < PortalFrame.INNER_HEIGHT; height++) {
        BetterBlockPos pos = PortalFrame.interior(frame.lowerLeftInterior(), frame.axis(), width, height);
        if (ctx.world().getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
          return Optional.of(pos);
        }
      }
    }
    return Optional.empty();
  }

  private boolean frameComplete(PortalFrame.FrameMatch frame) {
    for (int vertical = -1; vertical <= PortalFrame.INNER_HEIGHT; vertical++) {
      for (int horizontal = -1; horizontal <= PortalFrame.INNER_WIDTH; horizontal++) {
        if (PortalFrame.requiredFrameOffset(horizontal, vertical)
          && !ctx.world().getBlockState(PortalFrame.frameBlock(frame.lowerLeftInterior(), frame.axis(), horizontal, vertical)).is(Blocks.OBSIDIAN)) {
          return false;
        }
      }
    }
    return true;
  }

  private static BetterBlockPos ignitionTarget(PortalFrame.FrameMatch frame) {
    if (frame.axis() == Direction.Axis.Y) {
      throw new IllegalArgumentException("portal axis must be horizontal: " + frame.axis());
    }
    return frame.lowerLeftInterior().below();
  }

  private Optional<IgnitionClick> ignitionClick(PortalFrame.FrameMatch frame) {
    BetterBlockPos target = ignitionTarget(frame);
    Vec3 hit = Vec3.atCenterOf(target).add(0D, 0.5D, 0D);
    return RotationUtils.reachableOffset(ctx, target, hit, ctx.playerController().getBlockReachDistance(), false)
      .map(rotation -> new IgnitionClick(new BlockHitResult(hit, Direction.UP, target, false), rotation));
  }

  private Optional<InteractionHand> flintAndSteelHand() {
    if (ctx.player().getMainHandItem().is(Items.FLINT_AND_STEEL)) {
      return Optional.of(InteractionHand.MAIN_HAND);
    }
    if (ctx.player().getOffhandItem().is(Items.FLINT_AND_STEEL)) {
      return Optional.of(InteractionHand.OFF_HAND);
    }
    return Optional.empty();
  }

  @Override
  public void onLostControl() {
    execution = new Execution.Idle();
    baritone.getInputOverrideHandler().clearAllKeys();
  }

  @Override
  public double priority() {
    return DEFAULT_PRIORITY + 2D;
  }

  @Override
  public String displayName0() {
    return "Portal Task";
  }

  private enum BuildPhase {
    START_BUILD, DIRECT_BUILDING, BUILDING, IGNITING
  }

  private record FrameOffset(int horizontal, int vertical, boolean required) {
    private static final List<FrameOffset> MINIMAL = minimal();
    private static final List<FrameOffset> FULL = full();

    private BetterBlockPos apply(PortalFrame.FrameMatch frame) {
      if (required) {
        return PortalFrame.frameBlock(frame.lowerLeftInterior(), frame.axis(), horizontal, vertical);
      }
      return switch (frame.axis()) {
        case X -> new BetterBlockPos(frame.lowerLeftInterior().x + horizontal, frame.lowerLeftInterior().y + vertical, frame.lowerLeftInterior().z);
        case Z -> new BetterBlockPos(frame.lowerLeftInterior().x, frame.lowerLeftInterior().y + vertical, frame.lowerLeftInterior().z + horizontal);
        default -> throw new IllegalArgumentException("portal frame axis must be horizontal: " + frame.axis());
      };
    }

    private static List<FrameOffset> minimal() {
      ArrayList<FrameOffset> offsets = new ArrayList<>(PortalFrame.MINIMAL_FRAME_BLOCKS);
      offsets.add(new FrameOffset(0, -1, true));
      offsets.add(new FrameOffset(1, -1, true));
      for (int vertical = 0; vertical < PortalFrame.INNER_HEIGHT; vertical++) {
        offsets.add(new FrameOffset(-1, vertical, true));
        offsets.add(new FrameOffset(PortalFrame.INNER_WIDTH, vertical, true));
      }
      offsets.add(new FrameOffset(0, PortalFrame.INNER_HEIGHT, true));
      offsets.add(new FrameOffset(1, PortalFrame.INNER_HEIGHT, true));
      return List.copyOf(offsets);
    }

    private static List<FrameOffset> full() {
      ArrayList<FrameOffset> offsets = new ArrayList<>(PortalFrame.MINIMAL_FRAME_BLOCKS + 4);
      offsets.add(new FrameOffset(-1, -1, false));
      offsets.add(new FrameOffset(0, -1, true));
      offsets.add(new FrameOffset(1, -1, true));
      offsets.add(new FrameOffset(PortalFrame.INNER_WIDTH, -1, false));
      for (int vertical = 0; vertical < PortalFrame.INNER_HEIGHT; vertical++) {
        offsets.add(new FrameOffset(-1, vertical, true));
        offsets.add(new FrameOffset(PortalFrame.INNER_WIDTH, vertical, true));
      }
      offsets.add(new FrameOffset(-1, PortalFrame.INNER_HEIGHT, false));
      offsets.add(new FrameOffset(0, PortalFrame.INNER_HEIGHT, true));
      offsets.add(new FrameOffset(1, PortalFrame.INNER_HEIGHT, true));
      offsets.add(new FrameOffset(PortalFrame.INNER_WIDTH, PortalFrame.INNER_HEIGHT, false));
      return List.copyOf(offsets);
    }
  }

  private sealed interface Execution permits Execution.Idle, Execution.Traveling, Execution.Entering, Execution.Building {
    record Idle() implements Execution {
    }

    record Traveling(Goal terminalGoal, MacroPlan plan, MacroActionInstance action, List<MacroActionInstance> remaining, int ticks) implements Execution {
      public Traveling {
        remaining = List.copyOf(remaining);
      }

      private Traveling tick() {
        return new Traveling(terminalGoal, plan, action, remaining, ticks + 1);
      }
    }

    record Entering(Goal terminalGoal, MacroPlan plan, int sourceDimension, BetterBlockPos portalBlock, List<MacroActionInstance> remaining, int ticks, boolean entered) implements Execution {
      public Entering {
        remaining = List.copyOf(remaining);
      }

      private Entering tick() {
        return new Entering(terminalGoal, plan, sourceDimension, portalBlock, remaining, ticks + 1, entered);
      }

      private Entering markEntered() {
        return new Entering(terminalGoal, plan, sourceDimension, portalBlock, remaining, ticks, true);
      }
    }

    record Building(Goal terminalGoal, MacroPlan macroPlan, int sourceDimension, PortalTaskPlan.BuildPortal plan, List<MacroActionInstance> remaining, BuildPhase phase, int ticks,
      int ignitionCooldown) implements Execution {
      public Building {
        remaining = List.copyOf(remaining);
      }

      private Building tick() {
        return new Building(terminalGoal, macroPlan, sourceDimension, plan, remaining, phase, ticks + 1, ignitionCooldown);
      }

      private Building withPhase(BuildPhase phase) {
        return new Building(terminalGoal, macroPlan, sourceDimension, plan, remaining, phase, ticks, ignitionCooldown);
      }

      private Building withCooldown(int cooldown) {
        return new Building(terminalGoal, macroPlan, sourceDimension, plan, remaining, phase, ticks, cooldown);
      }

      private Building cooldown() {
        return new Building(terminalGoal, macroPlan, sourceDimension, plan, remaining, phase, ticks, Math.max(0, ignitionCooldown - 1));
      }
    }
  }

  private record EligibleAction(MacroPlan plan, MacroActionInstance action, List<MacroActionInstance> remaining) {
    private EligibleAction {
      if (plan == null) {
        throw new IllegalArgumentException("eligible portal action requires source macro plan");
      }
      remaining = List.copyOf(remaining);
    }
  }

  private record ActionKey(MacroActionKind kind, long fromNode, long toNode) {
    private ActionKey(MacroActionInstance action) {
      this(action.kind(), action.fromNode(), action.toNode());
    }
  }

  private record IgnitionClick(BlockHitResult hit, Rotation rotation) {
  }
}
