package baritone.process.elytra;

import baritone.api.Settings;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Pair;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static baritone.utils.BaritoneMath.fastCeil;
import static baritone.utils.BaritoneMath.fastFloor;

final class ElytraSolver {

  interface CollisionProbe {
    boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava);

    boolean passable(int x, int y, int z, boolean ignoreLava);
  }

  private final IPlayerContext ctx;
  private final ElytraPathfinderContext pathfinder;
  private final ElytraFlightPolicy policy;
  private final ElytraGlideController glideController = new ElytraGlideController();
  private final ElytraRenderer renderer;
  private final CollisionProbe collision;

  ElytraSolver(IPlayerContext ctx, ElytraPathfinderContext pathfinder, ElytraFlightPolicy policy, ElytraRenderer renderer, CollisionProbe collision) {
    this.ctx = ctx;
    this.pathfinder = pathfinder;
    this.policy = policy;
    this.renderer = renderer;
    this.collision = collision;
  }

  ElytraSolution solve(ElytraSolverContext context, boolean landingMode) {
    ElytraPath path = context.path;
    int playerNear = landingMode ? path.size() - 1 : context.playerNear;
    Vec3 start = context.start;
    ElytraSolution solution = null;

    for (int relaxation = 0; relaxation < 3; relaxation++) {
      int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0};
      int lookahead = relaxation == 0 ? 2 : 3;
      int minStep = playerNear;

      for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
        List<Pair<Vec3, Integer>> candidates = candidates(path, i, minStep, heights, relaxation);

        for (Pair<Vec3, Integer> candidate : candidates) {
          int augment = candidate.second();
          Vec3 dest = candidate.first().add(0, augment, 0);
          if (landingMode) {
            dest = dest.add(0.5, 0.5, 0.5);
          }

          if (augment != 0 && !canAugment(start, path, dest, i, lookahead, augment)) {
            continue;
          }

          double minAvoidance = baritone.Baritone.settings().elytraMinimumAvoidance.value;
          Double growth = relaxation == 2 ? null : relaxation == 0 ? 2 * minAvoidance : minAvoidance;
          if (!isHitboxClear(context, dest, growth)) {
            continue;
          }

          float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();
          Pair<Float, Boolean> pitch = solvePitch(context, dest, relaxation, landingMode);
          if (pitch == null) {
            solution = new ElytraSolution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
            continue;
          }
          return new ElytraSolution(context, new Rotation(yaw, pitch.first()), dest, true, pitch.second());
        }
      }
    }
    return solution;
  }

  private List<Pair<Vec3, Integer>> candidates(ElytraPath path, int i, int minStep, int[] heights, int relaxation) {
    List<Pair<Vec3, Integer>> candidates = new ArrayList<>();
    for (int dy : heights) {
      if (relaxation == 0 || i == minStep) {
        candidates.add(new Pair<>(path.getVec(i), dy));
      } else if (relaxation == 1) {
        for (double interp : new double[]{1.0, 0.75, 0.5, 0.25}) {
          Vec3 dest = interp == 1.0 ? path.getVec(i) : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
          candidates.add(new Pair<>(dest, dy));
        }
      } else {
        Vec3 delta = path.getVec(i).subtract(path.getVec(i - 1));
        int steps = fastFloor(delta.length());
        Vec3 step = delta.normalize();
        Vec3 stepped = path.getVec(i);
        for (int interp = 0; interp < steps; interp++) {
          candidates.add(new Pair<>(stepped, dy));
          stepped = stepped.subtract(step);
        }
      }
    }
    return candidates;
  }

  private boolean canAugment(Vec3 start, ElytraPath path, Vec3 dest, int i, int lookahead, int augment) {
    if (i + lookahead >= path.size()) {
      return false;
    }
    if (start.distanceTo(dest) < 40) {
      return collision.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false) && collision.clearView(dest, path.getVec(i + lookahead), false);
    }
    return collision.clearView(dest, path.getVec(i), false);
  }

  private boolean isHitboxClear(ElytraSolverContext context, Vec3 dest, Double growAmount) {
    Vec3 start = context.start;
    if (!collision.clearView(start, dest, context.ignoreLava)) {
      return false;
    }
    if (growAmount == null) {
      return true;
    }

    AABB bb = context.boundingBox.inflate(growAmount);
    double ox = dest.x - start.x;
    double oy = dest.y - start.y;
    double oz = dest.z - start.z;

    double[] src = {bb.minX, bb.minY, bb.minZ, bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.minZ, bb.minX, bb.maxY, bb.maxZ, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY,
      bb.minZ, bb.maxX, bb.maxY, bb.maxZ,};
    double[] dst = {bb.minX + ox, bb.minY + oy, bb.minZ + oz, bb.minX + ox, bb.minY + oy, bb.maxZ + oz, bb.minX + ox, bb.maxY + oy, bb.minZ + oz, bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
      bb.maxX + ox, bb.minY + oy, bb.minZ + oz, bb.maxX + ox, bb.minY + oy, bb.maxZ + oz, bb.maxX + ox, bb.maxY + oy, bb.minZ + oz, bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,};

    if (baritone.Baritone.settings().elytraRenderHitboxRaytraces.value) {
      boolean clear = true;
      for (int i = 0; i < 8; i++) {
        Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
        Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
        if (!collision.clearView(s, d, false)) {
          clear = false;
        }
      }
      return clear;
    }

    return pathfinder.raytrace(8, src, dst, ElytraPathfinderContext.Visibility.ALL);
  }

  private Pair<Float, Boolean> solvePitch(ElytraSolverContext context, Vec3 goal, int relaxation, boolean landingMode) {
    boolean desperate = relaxation == 2;
    float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
    FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

    List<IntTriple> tests = new ArrayList<>();
    if (context.boost.isBoosted()) {
      int guaranteed = context.boost.guaranteedBoostTicks();
      if (guaranteed == 0) {
        tests.add(new IntTriple(Math.max(4, 10 - context.boost.maximumRemainingBoostTicks()), 1, 0));
      } else if (guaranteed <= 5) {
        tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
      } else {
        tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
      }
    }

    Settings settings = baritone.Baritone.settings();
    int ticks = desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.guaranteedBoostTicks()) : settings.elytraSimulationTicks.value;
    tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

    Float glidePitch = glideController.pitch(policy, context, landingMode);
    if (glidePitch != null) {
      FloatArrayList glidePitches = new FloatArrayList(1);
      glidePitches.add(glidePitch);
      Optional<PitchResult> glide = tests.stream().map(i -> solvePitch(context, goal, relaxation, glidePitches.iterator(), i.ticks, i.ticksBoosted, i.ticksBoostDelay, landingMode))
        .filter(Objects::nonNull).filter(result -> advancesToward(goal.subtract(context.start), result)).findFirst();
      if (glide.isPresent()) {
        return new Pair<>(glide.get().pitch, false);
      }
    }

    Optional<PitchResult> result =
      tests.stream().map(i -> solvePitch(context, goal, relaxation, pitches.iterator(), i.ticks, i.ticksBoosted, i.ticksBoostDelay, landingMode)).filter(Objects::nonNull).findFirst();
    if (result.isPresent()) {
      return new Pair<>(result.get().pitch, false);
    }

    if (desperate) {
      Optional<PitchResult> resultBoost = List.of(new IntTriple(ticks, 10, 3), new IntTriple(ticks, 10, 2), new IntTriple(ticks, 10, 1)).stream()
        .map(i -> solvePitch(context, goal, relaxation, pitches.iterator(), i.ticks, i.ticksBoosted, i.ticksBoostDelay, landingMode)).filter(Objects::nonNull).findFirst();
      if (resultBoost.isPresent()) {
        return new Pair<>(resultBoost.get().pitch, true);
      }
    }

    return null;
  }

  private static boolean advancesToward(Vec3 goalDelta, PitchResult result) {
    Vec3 displacement = result.steps.get(result.steps.size() - 1);
    return displacement.lengthSqr() > 0.01 && goalDelta.normalize().dot(displacement.normalize()) > 0.55;
  }

  private PitchResult solvePitch(ElytraSolverContext context, Vec3 goal, int relaxation, FloatIterator pitches, int ticks, int ticksBoosted, int ticksBoostDelay, boolean landingMode) {
    Vec3 goalDelta = goal.subtract(context.start);
    Vec3 goalDirection = goalDelta.normalize();
    Deque<PitchResult> bestResults = new ArrayDeque<>();

    while (pitches.hasNext()) {
      float pitch = pitches.nextFloat();
      List<Vec3> displacement = simulate(context, goalDelta, pitch, ticks, ticksBoosted, ticksBoostDelay);
      if (displacement == null) {
        continue;
      }
      Vec3 last = displacement.get(displacement.size() - 1);
      double goodness = goalDirection.dot(last.normalize());
      if (landingMode) {
        goodness = -goalDelta.subtract(last).length();
      }
      PitchResult bestSoFar = bestResults.peek();
      if (bestSoFar == null || goodness > bestSoFar.dot) {
        bestResults.push(new PitchResult(pitch, goodness, displacement));
      }
    }

    outer : for (PitchResult result : bestResults) {
      if (relaxation < 2) {
        for (int i = result.steps.size() - 1; i >= 1; i--) {
          if (!collision.clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava)) {
            continue outer;
          }
        }
      } else if (!collision.clearView(context.start.add(result.steps.get(result.steps.size() - 1)), goal, context.ignoreLava)) {
        continue;
      }

      renderer.simulation(result.steps);
      return result;
    }
    return null;
  }

  private List<Vec3> simulate(ElytraSolverContext context, Vec3 goalDelta, float pitch, int ticks, int ticksBoosted, int ticksBoostDelay) {
    var aimProcessor = context.aimProcessor.fork();
    Vec3 delta = goalDelta;
    Vec3 motion = context.motion;
    AABB hitbox = context.boundingBox;
    List<Vec3> displacement = new ArrayList<>(ticks + 1);
    displacement.add(Vec3.ZERO);
    int remainingTicksBoosted = ticksBoosted;

    for (int i = 0; i < ticks; i++) {
      if (delta.lengthSqr() < 1) {
        break;
      }
      Rotation rotation = aimProcessor.nextRotation(RotationUtils.calcRotationFromVec3d(Vec3.ZERO, delta, ctx.playerRotations()).withPitch(pitch));
      Vec3 lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

      motion = step(motion, lookDirection, rotation.getPitch());
      delta = delta.subtract(motion);

      AABB inMotion = hitbox.inflate(motion.x, motion.y, motion.z).inflate(0.01);
      int xmin = fastFloor(inMotion.minX);
      int xmax = fastCeil(inMotion.maxX);
      int ymin = fastFloor(inMotion.minY);
      int ymax = fastCeil(inMotion.maxY);
      int zmin = fastFloor(inMotion.minZ);
      int zmax = fastCeil(inMotion.maxZ);
      for (int x = xmin; x < xmax; x++) {
        for (int y = ymin; y < ymax; y++) {
          for (int z = zmin; z < zmax; z++) {
            if (!collision.passable(x, y, z, context.ignoreLava)) {
              return null;
            }
          }
        }
      }

      hitbox = hitbox.move(motion);
      displacement.add(displacement.get(displacement.size() - 1).add(motion));

      if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
        motion = motion.add(lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motion.x) * 0.5, lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motion.y) * 0.5,
          lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motion.z) * 0.5);
      }
    }

    return displacement;
  }

  private static FloatArrayList pitchesToSolveFor(float goodPitch, boolean desperate) {
    Settings settings = baritone.Baritone.settings();
    float minPitch = desperate ? -90 : Math.max(goodPitch - settings.elytraPitchRange.value, -89);
    float maxPitch = desperate ? 90 : Math.min(goodPitch + settings.elytraPitchRange.value, 89);

    FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
    for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
      pitchValues.add(pitch);
    }
    for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
      pitchValues.add(pitch);
    }

    return pitchValues;
  }

  private static Vec3 step(Vec3 motion, Vec3 lookDirection, float pitch) {
    double motionX = motion.x;
    double motionY = motion.y;
    double motionZ = motion.z;

    float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
    double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
    double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
    double thisIsAlwaysOne = lookDirection.length();
    float pitchBase3 = Mth.cos(pitchRadians);
    pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
    motionY += -0.08 + (double) pitchBase3 * 0.06;
    if (motionY < 0 && pitchBase2 > 0) {
      double speedModifier = motionY * -0.1 * (double) pitchBase3;
      motionY += speedModifier;
      motionX += lookDirection.x * speedModifier / pitchBase2;
      motionZ += lookDirection.z * speedModifier / pitchBase2;
    }
    if (pitchRadians < 0) {
      double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
      motionY += anotherSpeedModifier * 3.2;
      motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
      motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
    }
    if (pitchBase2 > 0) {
      motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
      motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
    }
    return new Vec3(motionX * 0.99f, motionY * 0.98f, motionZ * 0.99f);
  }

  private record PitchResult(float pitch, double dot, List<Vec3> steps) {
  }

  private record IntTriple(int ticks, int ticksBoosted, int ticksBoostDelay) {
  }
}
