package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.accessor.IFireworkRocketEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ElytraBehavior implements ElytraPathManager.Host, ElytraSolver.CollisionProbe {
    private final Baritone baritone;
    private final IPlayerContext ctx;

    public final ElytraPathfinderContext context;
    public final ElytraPathManager pathManager;
    private final ElytraProcess process;
    private final ElytraFlightPolicy policy;
    private final ElytraRenderer renderer;
    private final ElytraSolver angleSolver;

    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;

    public boolean landingMode;

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

    private long timeLastCacheCull = 0L;

    // auto swap
    private int invTickCountdown = 0;
    private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

    public ElytraBehavior(Baritone baritone, ElytraProcess process, BlockPos destination, boolean appendDestination) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.process = process;
        this.destination = new BetterBlockPos(destination);
        this.appendDestination = appendDestination;
        this.renderer = new ElytraRenderer();
        this.solverExecutor = Executors.newSingleThreadExecutor();
        this.nextTickBoostCounter = new int[2];

        this.policy = ElytraFlightPolicy.capture(ctx.world());
        this.context = policy.createPathfinderContext(ctx);
        this.pathManager = new ElytraPathManager(this);
        this.angleSolver = new ElytraSolver(ctx, context, policy, renderer, this);
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

    public void pathTo() {
        if (!Baritone.settings().elytraAutoJump.value || ctx.player().isFallFlying()) {
            this.pathManager.pathToDestination();
        }
    }

    public void destroy() {
        if (this.solver != null) {
            this.solver.cancel(true);
        }
        this.solverExecutor.shutdown();
        try {
            while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
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

        final int playerNear = this.pathManager.getNear();
        this.renderer.visiblePath(path.subList(
                Math.max(playerNear - 30, 0),
                Math.min(playerNear + 100, path.size())
        ));
    }

    /**
     * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    public void tick() {
        if (this.pathManager.getPath().isEmpty()) {
            return;
        }

        trySwapElytra();

        if (ctx.player().horizontalCollision) {
            logVerbose("hbonk");
        }
        if (ctx.player().verticalCollision) {
            logVerbose("vbonk");
        }

        final ElytraSolverContext solverContext = this.solverContext(false);
        this.solveNextTick = true;

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
            return;
        }

        baritone.getLookBehavior().updateTarget(solution.rotation(), false);

        if (!solution.solvedPitch()) {
            logVerbose("no safe pitch solution");
            return;
        } else {
            this.renderer.aim(solution.goingTo());
        }

        this.tickUseFireworks(
                solution.context().start,
                solution.goingTo(),
                solution.context().boost.isBoosted(),
                solution.forceUseFirework() || inLava
        );
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
        return new ElytraSolverContext(
                this.pathManager.getPath(),
                this.pathManager.getNear(),
                ctx.playerFeetAsVec(),
                ctx.playerMotion(),
                ctx.player().getBoundingBox(),
                ctx.player().isInLava(),
                new ElytraFireworkBoost(fireworkTicksExisted, this.minimumBoostTicks),
                aim
        );
    }

    private void tickUseFireworks(final Vec3 start, final Vec3 goingTo, final boolean isBoosted, final boolean forceUseFirework) {
        if (this.remainingSetBackTicks > 0) {
            logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
            return;
        }
        if (this.landingMode) {
            return;
        }
        final boolean allowed = forceUseFirework ? policy.fireworkPolicy().forcedBoosts() : policy.fireworkPolicy().routineBoosts();
        if (!allowed) {
            return;
        }
        final double currentSpeed = new Vec3(
                ctx.player().getDeltaMovement().x,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().position().y < goingTo.y ? Math.max(0, ctx.player().getDeltaMovement().y) : ctx.player().getDeltaMovement().y,
                ctx.player().getDeltaMovement().z
        ).lengthSqr();

        final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        if (this.remainingFireworkTicks <= 0 && (forceUseFirework || (!isBoosted
                && (ctx.player().position().y < goingTo.y - 5 || start.distanceTo(new Vec3(goingTo.x + 0.5, ctx.player().position().y, goingTo.z + 0.5)) > 5)
                && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))
        ) {
            // Prioritize boosting fireworks over regular ones
            // TODO: Take the minimum boost time into account?
            if (!baritone.getInventoryBehavior().throwaway(true, ElytraFireworks::isBoosting) &&
                    !baritone.getInventoryBehavior().throwaway(true, ElytraFireworks::isPlain)) {
                logDirect("no fireworks");
                return;
            }
            logVerbose("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            this.minimumBoostTicks = 10 * (1 + ElytraFireworks.boost(ctx.player().getItemInHand(InteractionHand.MAIN_HAND)).orElse(0));
            this.remainingFireworkTicks = 10;
            this.deployedFireworkLastTick = true;
        }
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return ctx.entitiesStream()
                .filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
                .map(x -> (FireworkRocketEntity) x)
                .findFirst();
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
        return context.passable(bsi, x, y, z, ignoreLava);
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
        if (chest.getItem() != Items.ELYTRA
                || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
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
