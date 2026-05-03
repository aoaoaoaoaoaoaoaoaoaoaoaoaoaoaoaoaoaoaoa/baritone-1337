package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.process.ElytraLaunchMode;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.accessor.IFireworkRocketEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class ElytraBehavior implements ElytraPathManager.Host, ElytraSolver.CollisionProbe {
  private final Baritone baritone;
  private final IPlayerContext ctx;

  public final ElytraPathfinderContext context;
  public final ElytraPathManager pathManager;
  private final ElytraProcess process;
  private final ElytraFlightPolicy policy;
  private final ElytraLaunchMode launchMode;
  private final ElytraRenderer renderer;
  private final ElytraSolver angleSolver;
  private final ElytraGlideController glideController;
  private final ElytraTelemetry telemetry;

  /**
   * Remaining cool-down ticks between firework usage
   */
  private int remainingFireworkTicks;

  /**
   * Remaining cool-down ticks after the player's position and rotation are reset by the server
   */
  private int remainingSetBackTicks;

  public boolean landingMode;
  public boolean conserveFireworks;

  /**
   * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
   * <p>
   * Updated every time a firework is automatically used
   */
  private int minimumBoostTicks;

  private boolean deployedFireworkLastTick;
  private final int[] nextTickBoostCounter;

  private BlockStateInterface bsi;
  public final BetterBlockPos destination;
  private final boolean appendDestination;

  private final ExecutorService solverExecutor;
  private Future<ElytraSolution> solver;
  private ElytraSolution pendingSolution;
  private boolean solveNextTick;
  private boolean launchFireworkArmed;
  private ElytraPath lastTelemetryPath;

  private long timeLastCacheCull = 0L;

  // auto swap
  private int invTickCountdown = 0;
  private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

  public ElytraBehavior(Baritone baritone, ElytraProcess process, BlockPos destination, boolean appendDestination, ElytraLaunchMode launchMode) {
    this.baritone = baritone;
    this.ctx = baritone.getPlayerContext();
    this.process = process;
    this.destination = new BetterBlockPos(destination);
    this.appendDestination = appendDestination;
    this.launchMode = launchMode;
    this.launchFireworkArmed = launchMode.launchFirework();
    this.renderer = new ElytraRenderer();
    this.glideController = new ElytraGlideController();
    this.solverExecutor = Executors.newSingleThreadExecutor();
    this.nextTickBoostCounter = new int[2];

    this.policy = ElytraFlightPolicy.capture(ctx.world());
    this.telemetry = ElytraTelemetry.open(baritone.getDirectory().resolve("profiles"), ctx.playerFeet(), destination, launchMode, policy);
    if (telemetry != null) {
      logDirect("Elytra telemetry: " + telemetry.output());
    }
    this.context = policy.createPathfinderContext(ctx);
    this.pathManager = new ElytraPathManager(this);
    this.angleSolver = new ElytraSolver(ctx, context, renderer, this);
  }

  public void onRenderPass(RenderEvent event) {
    this.renderer.render(ctx, event);
  }

  public void onChunkEvent(ChunkEvent event) {
    if (event.isPostPopulate() && this.context != null) {
      final LevelChunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
      this.context.queueForPacking(chunk);
    }
  }

  public void onBlockChange(BlockChangeEvent event) {
    this.context.queueBlockUpdate(event);
  }

  public void onReceivePacket(PacketEvent event) {
    if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
      ctx.minecraft().execute(() -> {
        this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
      });
    }
  }

  public void pathTo(ElytraLaunchMode launchMode) {
    if (!launchMode.autoJump() || ctx.player().isFallFlying()) {
      this.pathManager.pathToDestination();
    }
  }

  public void destroy() {
    if (this.solver != null) {
      this.solver.cancel(true);
    }
    if (telemetry != null) {
      telemetry.close();
    }
    this.solverExecutor.shutdown();
    try {
      while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    this.context.destroy();
  }

  public void repackChunks() {
    if (!context.usesPackedChunks()) {
      return;
    }
    ChunkSource chunkProvider = ctx.world().getChunkSource();

    BetterBlockPos playerPos = ctx.playerFeet();

    int playerChunkX = playerPos.getX() >> 4;
    int playerChunkZ = playerPos.getZ() >> 4;

    int minX = playerChunkX - 40;
    int minZ = playerChunkZ - 40;
    int maxX = playerChunkX + 40;
    int maxZ = playerChunkZ + 40;

    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        LevelChunk chunk = chunkProvider.getChunk(x, z, false);

        if (chunk != null && !chunk.isEmpty()) {
          this.context.queueForPacking(chunk);
        }
      }
    }
  }

  public void telemetryEvent(String type, Object... fields) {
    if (telemetry != null) {
      telemetry.event(type, fields);
    }
  }

  public boolean hasPath() {
    return !this.pathManager.getPath().isEmpty();
  }

  public void launchFireworkNow(String reason) {
    tickLaunchFirework(ctx.playerFeetAsVec(), reason);
  }

  public void onTick() {
    synchronized (this.context.lock()) {
      this.onTick0();
    }
    final long now = System.currentTimeMillis();
    if ((now - this.timeLastCacheCull) / 1000 > Baritone.settings().elytraTimeBetweenCacheCullSecs.value) {
      this.context.queueCacheCulling(ctx.player().chunkPosition().x(), ctx.player().chunkPosition().z(), Baritone.settings().elytraCacheCullDistance.value);
      this.timeLastCacheCull = now;
    }
  }

  private void onTick0() {
    // Fetch the previous solution, regardless of if it's going to be used
    this.pendingSolution = null;
    if (this.solver != null) {
      try {
        this.pendingSolution = this.solver.get();
      } catch (Exception ignored) {
        // it doesn't matter if get() fails since the solution can just be recalculated synchronously
      } finally {
        this.solver = null;
      }
    }

    tickInventoryTransactions();

    if (this.remainingFireworkTicks > 0) {
      this.remainingFireworkTicks--;
    }
    if (this.remainingSetBackTicks > 0) {
      this.remainingSetBackTicks--;
    }
    if (!this.getAttachedFirework().isPresent()) {
      this.minimumBoostTicks = 0;
    }

    this.renderer.resetTick();

    final List<BetterBlockPos> path = this.pathManager.getPath();
    if (path.isEmpty()) {
      return;
    } else if (this.destination == null) {
      this.pathManager.clear();
      return;
    }

    this.bsi = new BlockStateInterface(ctx);
    this.pathManager.tick();
    if (telemetry != null && this.pathManager.getPath() != lastTelemetryPath) {
      lastTelemetryPath = this.pathManager.getPath();
      telemetry.path(lastTelemetryPath, this.pathManager.isComplete());
    }

    final int playerNear = this.pathManager.getNear();
    this.renderer.visiblePath(path.subList(Math.max(playerNear - 30, 0), Math.min(playerNear + 100, path.size())));
  }

  /**
   * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
   */
  public void tick() {
    if (this.pathManager.getPath().isEmpty()) {
      if (launchFireworkArmed && !conserveFireworks && ctx.player().isFallFlying()) {
        tickLaunchFirework(ctx.playerFeetAsVec(), "path_not_ready");
      }
      return;
    }

    ensureBlockStateInterface();
    trySwapElytra();

    if (ctx.player().horizontalCollision) {
      logVerbose("hbonk");
      if (telemetry != null) telemetry.event("horizontal_collision", "position", ctx.playerFeetAsVec(), "motion", ctx.playerMotion());
    }
    if (ctx.player().verticalCollision) {
      logVerbose("vbonk");
      if (telemetry != null) telemetry.event("vertical_collision", "position", ctx.playerFeetAsVec(), "motion", ctx.playerMotion());
    }

    final ElytraSolverContext solverContext = this.solverContext(false);
    this.solveNextTick = true;
    final boolean forceLaunchFirework = launchFireworkArmed && !conserveFireworks;

    // If there's no previously calculated solution to use, or the context used at the end of last tick doesn't match this tick
    final ElytraSolution solution;
    if (this.pendingSolution == null || !this.pendingSolution.context().equals(solverContext)) {
      solution = this.angleSolver.solve(solverContext, landingMode);
    } else {
      solution = this.pendingSolution;
    }

    if (this.deployedFireworkLastTick) {
      this.nextTickBoostCounter[solverContext.boost.isBoosted() ? 1 : 0]++;
      this.deployedFireworkLastTick = false;
    }

    final boolean inLava = ctx.player().isInLava();
    if (inLava) {
      baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
    }

    if (solution == null) {
      logVerbose("no solution");
      if (telemetry != null) telemetry.event("no_solution", "near", solverContext.playerNear, "position", solverContext.start, "motion", solverContext.motion);
      debugOverlay(solverContext, null);
      if (forceLaunchFirework) tickLaunchFirework(solverContext.start, "no_solution_launch");
      return;
    }
    if (telemetry != null) telemetry.tick(solverContext, solution, landingMode);
    debugOverlay(solverContext, solution);

    baritone.getLookBehavior().updateTarget(solution.rotation(), false);

    if (!solution.solvedPitch()) {
      logVerbose("no safe pitch solution");
      if (telemetry != null) telemetry.event("no_safe_pitch", "near", solverContext.playerNear, "position", solverContext.start, "target", solution.goingTo());
      if (forceLaunchFirework) tickLaunchFirework(solverContext.start, "no_safe_pitch_launch");
      return;
    } else {
      this.renderer.aim(solution.goingTo());
    }

    launchFireworkArmed = false;
    this.tickUseFireworks(solution.context().start, solution.goingTo(), solution.context().boost.isBoosted(),
      forceLaunchFirework ? FireworkUse.LAUNCH : solution.forceUseFirework() ? FireworkUse.RECOVERY.withDetail(solution.fireworkReason()) : inLava ? FireworkUse.LAVA : FireworkUse.ROUTINE);
  }

  public void onPostTick(TickEvent event) {
    if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
      if (!context.usesPackedChunks()) {
        this.solveNextTick = false;
        return;
      }
      // We're at the end of the tick, the player's position likely updated and the closest path node could've
      // changed. Updating it now will avoid unnecessary recalculation on the main thread.
      this.pathManager.updatePlayerNear();

      final ElytraSolverContext context = this.solverContext(true);
      this.solver = this.solverExecutor.submit(() -> this.angleSolver.solve(context, landingMode));
      this.solveNextTick = false;
    }
  }

  private ElytraSolverContext solverContext(boolean async) {
    final Integer fireworkTicksExisted;
    if (async && this.deployedFireworkLastTick) {
      final int[] counter = this.nextTickBoostCounter;
      fireworkTicksExisted = counter[1] > counter[0] ? 0 : null;
    } else {
      fireworkTicksExisted = this.getAttachedFirework().map(e -> e.tickCount).orElse(null);
    }
    ITickableAimProcessor aim = this.baritone.getLookBehavior().getAimProcessor().fork();
    if (async) {
      aim.advance(1);
    }
    ElytraFireworkBoost boost = new ElytraFireworkBoost(fireworkTicksExisted, this.minimumBoostTicks);
    ElytraPath path = this.pathManager.getPath();
    int near = this.pathManager.getNear();
    Vec3 start = ctx.playerFeetAsVec();
    Vec3 motion = ctx.playerMotion();
    ElytraControlDecision control = glideController.decide(policy, path, near, start, motion, boost, landingMode, recoveryFloorY(path, near, start), !async);
    return new ElytraSolverContext(path, near, start, motion, ctx.player().getBoundingBox(), ctx.player().isInLava(), boost, aim, control);
  }

  private double recoveryFloorY(ElytraPath path, int near, Vec3 start) {
    if (policy.dimension() == Level.NETHER) {
      return Double.NaN;
    }
    int ceiling = policy.minY();
    ceiling = Math.max(ceiling, corridorCeiling((int) Math.floor(start.x), (int) Math.floor(start.z)));
    for (int i = near; i <= Math.min(path.size() - 1, near + 8); i++) {
      BetterBlockPos point = path.get(i);
      ceiling = Math.max(ceiling, corridorCeiling(point.x, point.z));
    }
    return Math.min(policy.maxYExclusive() - 24, ceiling + 48);
  }

  private int corridorCeiling(int x, int z) {
    int ceiling = policy.minY();
    for (int dx = -16; dx <= 16; dx += 8) {
      for (int dz = -16; dz <= 16; dz += 8) {
        int px = x + dx;
        int pz = z + dz;
        if (ctx.world().getChunkSource().hasChunk(px >> 4, pz >> 4)) {
          ceiling = Math.max(ceiling, ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz));
        }
      }
    }
    return ceiling;
  }

  private void debugOverlay(ElytraSolverContext context, ElytraSolution solution) {
    if (!Baritone.settings().elytraDebugOverlay.value || ctx.player() == null) {
      return;
    }
    ElytraControlDecision c = context.control;
    String pitch = solution == null || !solution.solvedPitch() ? "none" : String.format(Locale.ROOT, "%.1f", solution.rotation().getPitch());
    String yaw = solution == null || !solution.solvedPitch() ? "none" : String.format(Locale.ROOT, "%.0f", solution.rotation().getYaw());
    String source = solution == null ? "none" : solution.pitchSource();
    String firework = conserveFireworks ? "reserve" : solution != null && solution.forceUseFirework() ? solution.fireworkReason() : c.firework() ? c.fireworkReason() : "hold";
    String message = String.format(Locale.ROOT, "elytra %s[%d] src=%s p=%s yaw=%s y=%.1f→%.0f floor=%.0f clear=%.0f h=%.2f v=%.2f fw=%s cd=%d boost=%d near=%d/%d", c.mode(), c.phaseTicks(), source,
      pitch, yaw, c.y(), c.targetY(), c.floorY(), c.clearance(), c.horizontalSpeed(), c.verticalSpeed(), firework, remainingFireworkTicks, context.boost.guaranteedBoostTicks(), context.playerNear,
      context.path.size());
    ctx.player().sendOverlayMessage(Component.literal(message));
  }

  private void tickLaunchFirework(Vec3 start, String reason) {
    launchFireworkArmed = false;
    Vec3 goingTo = launchAssistTarget(start);
    baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(start, goingTo, ctx.playerRotations()), false);
    tickUseFireworks(start, goingTo, getAttachedFirework().isPresent(), FireworkUse.LAUNCH.withDetail(reason));
  }

  private Vec3 launchAssistTarget(Vec3 start) {
    Vec3 flat = Vec3.atCenterOf(destination).subtract(start).multiply(1, 0, 1);
    if (flat.lengthSqr() < 1e-6) {
      flat = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations()).multiply(1, 0, 1);
    }
    return start.add(flat.normalize().scale(32)).add(0, 18, 0);
  }

  private void tickUseFireworks(final Vec3 start, final Vec3 goingTo, final boolean isBoosted, FireworkUse use) {
    if (this.remainingSetBackTicks > 0) {
      logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
      return;
    }
    if (this.conserveFireworks) {
      return;
    }
    if (this.landingMode) {
      return;
    }
    if (isBoosted && !use.stackableWhileBoosted()) {
      return;
    }
    final boolean forceUseFirework = use.forced();
    final boolean allowed = forceUseFirework ? policy.fireworkPolicy().forcedBoosts() : policy.fireworkPolicy().routineBoosts();
    if (!allowed) {
      return;
    }
    final double currentSpeed = new Vec3(ctx.player().getDeltaMovement().x,
      // ignore y component if we are BOTH below where we want to be AND descending
      ctx.player().position().y < goingTo.y ? Math.max(0, ctx.player().getDeltaMovement().y) : ctx.player().getDeltaMovement().y, ctx.player().getDeltaMovement().z).lengthSqr();

    final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
    if (this.remainingFireworkTicks <= 0
      && (forceUseFirework || (!isBoosted && (ctx.player().position().y < goingTo.y - 5 || start.distanceTo(new Vec3(goingTo.x + 0.5, ctx.player().position().y, goingTo.z + 0.5)) > 5)
        && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))) {
      // Prioritize boosting fireworks over regular ones
      // TODO: Take the minimum boost time into account?
      InteractionHand fireworkHand = selectFirework();
      if (fireworkHand == null) {
        logDirect("no fireworks");
        if (telemetry != null) telemetry.event("no_fireworks", "forced", forceUseFirework);
        return;
      }
      ItemStack firework = ctx.player().getItemInHand(fireworkHand).copy();
      int boostTicks = 10 * (1 + ElytraFireworks.boost(firework).orElse(0));
      logVerbose("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
      if (telemetry != null)
        telemetry.event("firework", "forced", forceUseFirework, "reason", use.detail(), "position", ctx.playerFeetAsVec(), "target", goingTo, "cooldown", use.cooldownTicks(boostTicks));
      ctx.playerController().processRightClick(ctx.player(), ctx.world(), fireworkHand);
      this.minimumBoostTicks = boostTicks;
      this.remainingFireworkTicks = use.cooldownTicks(boostTicks);
      this.deployedFireworkLastTick = true;
    }
  }

  private record FireworkUse(String detail, boolean forced, int minimumCooldown, int boostCooldownMargin, boolean stackableWhileBoosted) {
    static final FireworkUse LAUNCH = new FireworkUse("launch", true, 55, 35, false);
    static final FireworkUse RECOVERY = new FireworkUse("recovery", true, 70, 50, false);
    static final FireworkUse LAVA = new FireworkUse("lava", true, 20, 10, true);
    static final FireworkUse ROUTINE = new FireworkUse("routine", false, 30, 12, false);

    FireworkUse withDetail(String detail) {
      return new FireworkUse(detail, forced, minimumCooldown, boostCooldownMargin, stackableWhileBoosted);
    }

    int cooldownTicks(int boostTicks) {
      return Math.max(minimumCooldown, boostTicks + boostCooldownMargin);
    }
  }

  private InteractionHand selectFirework() {
    InteractionHand hand = selectFirework(ElytraFireworks::isBoosting);
    return hand != null ? hand : selectFirework(ElytraFireworks::isPlain);
  }

  private InteractionHand selectFirework(Predicate<ItemStack> predicate) {
    if (predicate.test(ctx.player().getItemInHand(InteractionHand.MAIN_HAND))) {
      return InteractionHand.MAIN_HAND;
    }
    NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
    for (int i = 0; i < 9; i++) {
      if (predicate.test(inv.get(i))) {
        ctx.player().getInventory().setSelectedSlot(i);
        return InteractionHand.MAIN_HAND;
      }
    }
    if (predicate.test(ctx.player().getItemInHand(InteractionHand.OFF_HAND))) {
      return InteractionHand.OFF_HAND;
    }
    for (int i = 9; i < 36; i++) {
      if (predicate.test(inv.get(i))) {
        int hotbarSlot = 7;
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, i, hotbarSlot, ContainerInput.SWAP, ctx.player());
        ctx.player().getInventory().setSelectedSlot(hotbarSlot);
        return InteractionHand.MAIN_HAND;
      }
    }
    return null;
  }

  private Optional<FireworkRocketEntity> getAttachedFirework() {
    return ctx.entitiesStream().filter(x -> x instanceof FireworkRocketEntity).filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
      .map(x -> (FireworkRocketEntity) x).findFirst();
  }

  @Override
  public IPlayerContext ctx() {
    return ctx;
  }

  @Override
  public ElytraPathfinderContext pathfinderContext() {
    return context;
  }

  public ElytraFlightPolicy policy() {
    return policy;
  }

  @Override
  public BetterBlockPos destination() {
    return destination;
  }

  @Override
  public boolean appendDestination() {
    return appendDestination;
  }

  @Override
  public ElytraProcess process() {
    return process;
  }

  @Override
  public boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava) {
    final boolean clear;
    if (!ignoreLava) {
      clear = start.equals(dest) || this.context.raytrace(start, dest);
    } else {
      clear = ctx.world().clip(new ClipContext(start, dest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
    }

    if (Baritone.settings().elytraRenderRaytraces.value) {
      this.renderer.rayTrace(start, dest, clear);
    }
    return clear;
  }

  @Override
  public boolean passable(int x, int y, int z, boolean ignoreLava) {
    return context.passable(ensureBlockStateInterface(), x, y, z, ignoreLava);
  }

  private BlockStateInterface ensureBlockStateInterface() {
    if (bsi == null) {
      bsi = new BlockStateInterface(ctx);
    }
    return bsi;
  }

  private void tickInventoryTransactions() {
    if (invTickCountdown <= 0) {
      Runnable r = invTransactionQueue.poll();
      if (r != null) {
        r.run();
        invTickCountdown = Baritone.settings().ticksBetweenInventoryMoves.value;
      }
    }
    if (invTickCountdown > 0) invTickCountdown--;
  }

  private void queueWindowClick(int windowId, int slotId, int button, ContainerInput type) {
    invTransactionQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, type, ctx.player()));
  }

  private int findGoodElytra() {
    NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
    for (int i = 0; i < invy.size(); i++) {
      ItemStack slot = invy.get(i);
      if (slot.getItem() == Items.ELYTRA && (slot.getMaxDamage() - slot.getDamageValue()) > Baritone.settings().elytraMinimumDurability.value) {
        return i;
      }
    }
    return -1;
  }

  private void trySwapElytra() {
    if (!Baritone.settings().elytraAutoSwap.value || !invTransactionQueue.isEmpty()) {
      return;
    }

    ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
    if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
      return;
    }

    int goodElytraSlot = findGoodElytra();
    if (goodElytraSlot != -1) {
      final int CHEST_SLOT = 6;
      final int slotId = goodElytraSlot < 9 ? goodElytraSlot + 36 : goodElytraSlot;
      queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.PICKUP);
      queueWindowClick(ctx.player().inventoryMenu.containerId, CHEST_SLOT, 0, ContainerInput.PICKUP);
      queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.PICKUP);
    }
  }

  @Override
  public void logVerbose(String message) {
    if (Baritone.settings().elytraChatSpam.value) {
      logDebug(message);
    }
  }
}
