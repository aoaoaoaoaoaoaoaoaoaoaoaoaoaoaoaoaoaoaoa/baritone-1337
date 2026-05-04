package baritone.pathing.transport;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import java.util.Locale;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

public record TransportSnapshot(TransportMode actual, TransportSnapshot.Executor current, TransportSnapshot.Executor next, BetterBlockPos planningStart, TransportControl control) {
  private static final int SEQUENCE_LIMIT = 10;

  public static TransportSnapshot capture(Baritone baritone, IPlayerContext ctx, PathExecutor current, PathExecutor next, BetterBlockPos planningStart) {
    return new TransportSnapshot(actual(baritone, ctx), Executor.capture(ctx, current, SEQUENCE_LIMIT), Executor.capture(ctx, next, SEQUENCE_LIMIT), planningStart,
      current == null ? null : current.transportControl());
  }

  public String overlayLine() {
    return String.format(Locale.ROOT, "tr actual=%s cur=%s next=%s calc=%s", actual, current.overlay(), next.overlay(),
      planningStart == null ? "-" : planningStart.x + "," + planningStart.y + "," + planningStart.z);
  }

  private static TransportMode actual(Baritone baritone, IPlayerContext ctx) {
    if (baritone.getElytraProcess().isActive() || ctx.player().isFallFlying()) {
      return TransportMode.ELYTRA;
    }
    if (ctx.player().getVehicle() instanceof AbstractBoat) {
      return TransportMode.BOAT;
    }
    if (MovementHelper.isWater(ctx, ctx.playerFeet()) || ctx.player().isSwimming()) {
      return TransportMode.SWIM;
    }
    return TransportMode.PEDESTRIAN;
  }

  private static Plan plan(IPlayerContext ctx, IMovement movement) {
    if (movement == null) {
      return null;
    }
    Plan plan = movement instanceof Movement concrete ? concrete.transportPlan() : Plan.pedestrian(movement.getClass().getSimpleName(), movement.getSrc(), movement.getDest());
    return plan.mode == TransportMode.PEDESTRIAN && legacyWater(ctx, movement) ? Plan.legacyWater(movement.getClass().getSimpleName(), movement.getSrc(), movement.getDest()) : plan;
  }

  private static boolean legacyWater(IPlayerContext ctx, IMovement movement) {
    return MovementHelper.isWater(ctx, movement.getSrc()) || MovementHelper.isWater(ctx, movement.getDest());
  }

  public record Executor(int position, int size, Plan current, String sequence) {
    private static Executor absent() {
      return new Executor(-1, -1, null, null);
    }

    private static Executor done(int position, int size) {
      return new Executor(position, size, null, "");
    }

    private static Executor capture(IPlayerContext ctx, PathExecutor executor, int sequenceLimit) {
      if (executor == null) {
        return absent();
      }
      IPath path = executor.getPath();
      int size = path.movements().size();
      int position = Math.max(0, Math.min(executor.getPosition(), size));
      if (position >= size) {
        return done(position, size);
      }
      return new Executor(position, size, plan(ctx, path.movements().get(position)), sequence(ctx, path, position, sequenceLimit));
    }

    public boolean present() {
      return position >= 0;
    }

    public boolean done() {
      return present() && position >= size;
    }

    public String overlay() {
      if (!present()) {
        return "-";
      }
      if (done()) {
        return "done";
      }
      return position + "/" + size + " " + current.overlay() + " seq=" + sequence;
    }

    private static String sequence(IPlayerContext ctx, IPath path, int position, int limit) {
      StringBuilder sequence = new StringBuilder(limit * 2);
      int end = Math.min(path.movements().size(), position + limit);
      for (int i = position; i < end; i++) {
        if (i != position) {
          sequence.append('>');
        }
        sequence.append(plan(ctx, path.movements().get(i)).token());
      }
      return sequence.toString();
    }
  }

  public record Plan(TransportMode mode, String movement, BetterBlockPos src, BetterBlockPos dest, String phase, boolean terminal, BetterBlockPos entry, Double progress) {
    public static Plan pedestrian(String movement, BetterBlockPos src, BetterBlockPos dest) {
      return new Plan(TransportMode.PEDESTRIAN, movement, src, dest, null, false, null, null);
    }

    public static Plan legacyWater(String movement, BetterBlockPos src, BetterBlockPos dest) {
      return new Plan(TransportMode.LEGACY_WATER, movement, src, dest, null, false, null, null);
    }

    public static Plan transport(TransportMode mode, String movement, BetterBlockPos src, BetterBlockPos dest, String phase, boolean terminal, BetterBlockPos entry, double progress) {
      return new Plan(mode, movement, src, dest, phase, terminal, entry, progress);
    }

    public char token() {
      return switch (mode) {
        case PEDESTRIAN -> 'P';
        case LEGACY_WATER -> 'w';
        case SWIM -> 'S';
        case BOAT -> terminal ? 'B' : 'b';
        case ELYTRA -> 'E';
      };
    }

    public String overlay() {
      if (mode == TransportMode.LEGACY_WATER) {
        return "LEGACY_WATER[" + movement + "]";
      }
      if (phase != null) {
        return String.format(Locale.ROOT, "%s[%s,%s,p=%.2f]", mode, phase, terminal ? "term" : "trans", progress);
      }
      return mode.name();
    }
  }
}
