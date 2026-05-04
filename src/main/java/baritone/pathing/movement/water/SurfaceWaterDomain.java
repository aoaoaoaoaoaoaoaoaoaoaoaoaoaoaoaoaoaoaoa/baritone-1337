package baritone.pathing.movement.water;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.route.SurfaceLineLeg;
import baritone.pathing.transport.TransportMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SurfaceWaterDomain {
  private static final double MIN_BOAT_CHAIN_COS = 0.8191520442889918D; // cos(35°)
  private static final int SWIM_CANDIDATE_SCAN_MOVEMENTS = 192;
  private static final int TERMINAL_EXIT_SCAN_MOVEMENTS = 24;
  private static final SurfaceWaterMode.Boat BOAT = new SurfaceWaterMode.Boat();

  private SurfaceWaterDomain() {
  }

  public static List<SurfaceLineLeg> route(CalculationContext context, List<Movement> movements) {
    if (movements.isEmpty()) {
      return List.of();
    }
    WaterLineProfile swim = WaterLineProfile.swim(context.costs.waterMoveCost());
    ArrayList<SurfaceLineLeg> legs = new ArrayList<>();
    TransportMode mode = context.waterTransport.boatMounted() ? TransportMode.BOAT : TransportMode.PEDESTRIAN;
    BoatHeading previousBoat =
      context.waterTransport.boatMounted() && context.waterTransport.hasBoatHeading() ? new BoatHeading(context.waterTransport.boatHeadingX(), context.waterTransport.boatHeadingZ()) : null;

    for (int i = 0; i < movements.size();) {
      int boatRunEnd = boatRunEnd(context, movements, i);
      Optional<Choice> mountedBoatRun = mode == TransportMode.BOAT && boatRunEnd > i ? chooseMountedBoat(context, movements, i, boatRunEnd, previousBoat) : Optional.empty();
      if (mountedBoatRun.isPresent()) {
        legs.add(new SurfaceLineLeg(i, mountedBoatRun.get().endExclusive(), mountedBoatRun.get().segment()));
        i = mountedBoatRun.get().endExclusive();
        mode = mountedBoatRun.get().segment().leg().nextMode();
        previousBoat = mode == TransportMode.BOAT ? BoatHeading.of(mountedBoatRun.get().segment()) : null;
        continue;
      }
      Optional<Choice> boatEntry = mode == TransportMode.PEDESTRIAN && context.waterTransport.boatAvailable() ? chooseBoatEntry(context, movements, i) : Optional.empty();
      if (boatEntry.isPresent()) {
        legs.add(new SurfaceLineLeg(i, boatEntry.get().endExclusive(), boatEntry.get().segment()));
        i = boatEntry.get().endExclusive();
        mode = boatEntry.get().segment().leg().nextMode();
        previousBoat = mode == TransportMode.BOAT ? BoatHeading.of(boatEntry.get().segment()) : null;
        continue;
      }
      Optional<Choice> waterBoatEntry =
        mode == TransportMode.PEDESTRIAN && context.waterTransport.boatAvailable() && boatRunEnd > i ? chooseBoatFromWater(context, movements, i, boatRunEnd) : Optional.empty();
      if (waterBoatEntry.isPresent()) {
        legs.add(new SurfaceLineLeg(i, waterBoatEntry.get().endExclusive(), waterBoatEntry.get().segment()));
        i = waterBoatEntry.get().endExclusive();
        mode = waterBoatEntry.get().segment().leg().nextMode();
        previousBoat = mode == TransportMode.BOAT ? BoatHeading.of(waterBoatEntry.get().segment()) : null;
        continue;
      }
      Optional<Choice> choice = chooseSwim(context, movements, i, swim);
      if (choice.isPresent()) {
        legs.add(new SurfaceLineLeg(i, choice.get().endExclusive(), choice.get().segment()));
        i = choice.get().endExclusive();
        mode = choice.get().segment().leg().nextMode();
        previousBoat = null;
      } else {
        Movement movement = movements.get(i++);
        boolean stillBoating = mode == TransportMode.BOAT && boatSurfaceMovement(context, movement);
        mode = stillBoating ? TransportMode.BOAT : TransportMode.PEDESTRIAN;
        if (!stillBoating) {
          previousBoat = null;
        }
      }
    }

    return List.copyOf(legs);
  }

  private static Optional<Choice> chooseBoatEntry(CalculationContext context, List<Movement> movements, int entryIndex) {
    if (entryIndex + 1 >= movements.size()) {
      return Optional.empty();
    }
    Movement entry = movements.get(entryIndex);
    BetterBlockPos launch = entry.getSrc();
    BetterBlockPos waterStart = entry.getDest();
    if (!boatLaunchCandidate(context, launch, waterStart)) {
      return Optional.empty();
    }
    int runStart = entryIndex + 1;
    int runEnd = boatRunEnd(context, movements, runStart);
    if (runEnd - runStart < 2) {
      return Optional.empty();
    }
    for (int end = runEnd; end >= runStart + 2; end--) {
      BetterBlockPos waterEnd = movements.get(end - 1).getDest();
      boolean terminal = end == runEnd;
      int choiceEnd = terminal ? terminalExitEnd(context, movements, runEnd) : end;
      BetterBlockPos dest = choiceEnd > end ? movements.get(choiceEnd - 1).getDest() : waterEnd;
      Optional<WaterLineSegment> segment = WaterLineKernel.connect(context, launch, waterStart, waterEnd, dest, WaterLineProfile.boatLaunch(context.waterTransport, terminal), terminal);
      if (segment.isPresent() && boatTransitIsSane(context, movements, choiceEnd, runEnd, segment.get())) {
        return Optional.of(new Choice(choiceEnd, segment.get()));
      }
    }
    return Optional.empty();
  }

  private static Optional<Choice> chooseBoatFromWater(CalculationContext context, List<Movement> movements, int start, int runEnd) {
    BetterBlockPos src = movements.get(start).getSrc();
    if (!BOAT.legal(context, src.x, src.y, src.z)) {
      return Optional.empty();
    }
    for (int end = runEnd; end >= start + 1; end--) {
      BetterBlockPos waterEnd = movements.get(end - 1).getDest();
      boolean terminal = end == runEnd;
      int choiceEnd = terminal ? terminalExitEnd(context, movements, runEnd) : end;
      BetterBlockPos dest = choiceEnd > end ? movements.get(choiceEnd - 1).getDest() : waterEnd;
      Optional<WaterLineSegment> segment = WaterLineKernel.connect(context, src, src, waterEnd, dest, WaterLineProfile.boatLaunch(context.waterTransport, terminal), terminal);
      if (segment.isPresent() && boatTransitIsSane(context, movements, choiceEnd, runEnd, segment.get())) {
        return Optional.of(new Choice(choiceEnd, segment.get()));
      }
    }
    return Optional.empty();
  }

  private static boolean boatLaunchCandidate(CalculationContext context, BetterBlockPos launch, BetterBlockPos waterStart) {
    int dx = waterStart.x - launch.x;
    int dz = waterStart.z - launch.z;
    if (dx * dx + dz * dz > 2 || dx == 0 && dz == 0) {
      return false;
    }
    if (MovementHelper.isWater(context.get(launch.x, launch.y, launch.z)) || !MovementHelper.canWalkOn(context, launch.x, launch.y - 1, launch.z)) {
      return false;
    }
    if (MovementHelper.isDeepWater(context, launch.x, launch.y, launch.z)) {
      return false;
    }
    if (!MovementHelper.canMoveThrough(context, launch.x, launch.y, launch.z, context.get(launch.x, launch.y, launch.z))
      || !MovementHelper.canMoveThrough(context, launch.x, launch.y + 1, launch.z, context.get(launch.x, launch.y + 1, launch.z))) {
      return false;
    }
    return BOAT.legal(context, waterStart.x, waterStart.y, waterStart.z);
  }

  private static Optional<Choice> chooseSwim(CalculationContext context, List<Movement> movements, int start, WaterLineProfile swim) {
    Optional<SwimEntry> maybeEntry = swimEntry(context, movements, start);
    if (maybeEntry.isEmpty()) {
      return Optional.empty();
    }
    SwimEntry entry = maybeEntry.get();
    int limit = Math.min(movements.size(), start + SWIM_CANDIDATE_SCAN_MOVEMENTS);
    for (int end = limit; end >= start + 2; end--) {
      BetterBlockPos waterEnd = movements.get(end - 1).getDest();
      if (waterEnd.y != entry.waterStart().y || !swimSurfaceNode(context, waterEnd)) {
        continue;
      }
      int terminalExit = terminalExitEnd(context, movements, end);
      boolean terminal = terminalExit > end && !hasFutureSurfaceNode(context, movements, end);
      int choiceEnd = terminal ? terminalExit : end;
      BetterBlockPos dest = choiceEnd > end ? movements.get(choiceEnd - 1).getDest() : waterEnd;
      Optional<WaterLineSegment> segment = WaterLineKernel.connect(context, entry.src(), entry.waterStart(), waterEnd, dest, swim, terminal);
      if (segment.isPresent()) {
        return Optional.of(new Choice(choiceEnd, segment.get()));
      }
    }
    return Optional.empty();
  }

  private static Optional<SwimEntry> swimEntry(CalculationContext context, List<Movement> movements, int start) {
    Movement movement = movements.get(start);
    BetterBlockPos src = movement.getSrc();
    if (swimSurfaceNode(context, src)) {
      return Optional.of(new SwimEntry(src, src));
    }
    BetterBlockPos dest = movement.getDest();
    return swimSurfaceNode(context, dest) && src.y >= dest.y ? Optional.of(new SwimEntry(src, dest)) : Optional.empty();
  }

  private static Optional<Choice> chooseMountedBoat(CalculationContext context, List<Movement> movements, int start, int runEnd, BoatHeading previousBoat) {
    Optional<Choice> strict = chooseMountedBoat(context, movements, start, runEnd, previousBoat, true);
    if (strict.isPresent()) {
      return strict;
    }
    Optional<Choice> sharpTurn = chooseMountedBoat(context, movements, start, runEnd, previousBoat, false);
    return sharpTurn.isPresent() ? sharpTurn : chooseMountedBoat(context, movements, start, runEnd, null, false);
  }

  private static Optional<Choice> chooseMountedBoat(CalculationContext context, List<Movement> movements, int start, int runEnd, BoatHeading previousBoat, boolean requireSmoothContinuation) {
    BetterBlockPos src = movements.get(start).getSrc();
    for (int end = runEnd; end >= start + 1; end--) {
      BetterBlockPos waterEnd = movements.get(end - 1).getDest();
      boolean terminal = end == runEnd;
      int choiceEnd = terminal ? terminalExitEnd(context, movements, runEnd) : end;
      BetterBlockPos dest = choiceEnd > end ? movements.get(choiceEnd - 1).getDest() : waterEnd;
      Optional<WaterLineSegment> segment = WaterLineKernel.connect(context, src, src, waterEnd, dest, WaterLineProfile.mountedBoat(context.waterTransport, terminal), terminal);
      if (segment.isPresent() && boatHeadingCompatible(previousBoat, segment.get()) && (!requireSmoothContinuation || boatTransitIsSane(context, movements, choiceEnd, runEnd, segment.get()))) {
        return Optional.of(new Choice(choiceEnd, segment.get()));
      }
    }
    return Optional.empty();
  }

  private static boolean boatTransitIsSane(CalculationContext context, List<Movement> movements, int end, int runEnd, WaterLineSegment segment) {
    if (segment.terminal()) {
      return true;
    }
    for (int nextEnd = runEnd; nextEnd >= end + 1; nextEnd--) {
      BetterBlockPos dest = movements.get(nextEnd - 1).getDest();
      boolean terminal = nextEnd == runEnd;
      Optional<WaterLineSegment> continuation = WaterLineKernel.connect(context, segment.waterEnd(), dest, WaterLineProfile.mountedBoat(context.waterTransport, terminal), terminal);
      if (continuation.isPresent() && boatHeadingCompatible(BoatHeading.of(segment), continuation.get())) {
        return true;
      }
    }
    return false;
  }

  private static boolean boatHeadingCompatible(BoatHeading previous, WaterLineSegment next) {
    if (previous == null) {
      return true;
    }
    double ax = previous.x;
    double az = previous.z;
    double bx = next.waterEnd().x - next.waterStart().x;
    double bz = next.waterEnd().z - next.waterStart().z;
    double denom = Math.hypot(ax, az) * Math.hypot(bx, bz);
    return denom == 0D || (ax * bx + az * bz) / denom >= MIN_BOAT_CHAIN_COS;
  }

  private record BoatHeading(double x, double z) {
    private static BoatHeading of(WaterLineSegment segment) {
      return new BoatHeading(segment.waterEnd().x - segment.waterStart().x, segment.waterEnd().z - segment.waterStart().z);
    }
  }

  private static int boatRunEnd(CalculationContext context, List<Movement> movements, int start) {
    int i = start;
    while (i < movements.size() && boatSurfaceMovement(context, movements.get(i))) {
      i++;
    }
    return i;
  }

  private static boolean boatSurfaceMovement(CalculationContext context, Movement movement) {
    BetterBlockPos src = movement.getSrc();
    BetterBlockPos dest = movement.getDest();
    return src.y == dest.y && (src.x != dest.x || src.z != dest.z) && boatSurfaceNode(context, src) && boatSurfaceNode(context, dest);
  }

  private static boolean swimSurfaceNode(CalculationContext context, BetterBlockPos pos) {
    return MovementHelper.surfaceSwimCell(context, pos.x, pos.y, pos.z);
  }

  private static boolean boatSurfaceNode(CalculationContext context, BetterBlockPos pos) {
    return BOAT.legal(context, pos.x, pos.y, pos.z);
  }

  private static int terminalExitEnd(CalculationContext context, List<Movement> movements, int runEnd) {
    int limit = Math.min(movements.size(), runEnd + TERMINAL_EXIT_SCAN_MOVEMENTS);
    for (int i = runEnd; i < limit; i++) {
      BetterBlockPos dest = movements.get(i).getDest();
      if (dryStableNode(context, dest)) {
        return i + 1;
      }
    }
    return runEnd;
  }

  private static boolean hasFutureSurfaceNode(CalculationContext context, List<Movement> movements, int start) {
    for (int i = start; i < movements.size(); i++) {
      if (swimSurfaceNode(context, movements.get(i).getDest())) {
        return true;
      }
    }
    return false;
  }

  private static boolean dryStableNode(CalculationContext context, BetterBlockPos pos) {
    return !MovementHelper.isWater(context.get(pos.x, pos.y, pos.z)) && MovementHelper.canWalkOn(context, pos.x, pos.y - 1, pos.z)
      && MovementHelper.canMoveThrough(context, pos.x, pos.y, pos.z, context.get(pos.x, pos.y, pos.z))
      && MovementHelper.canMoveThrough(context, pos.x, pos.y + 1, pos.z, context.get(pos.x, pos.y + 1, pos.z));
  }

  private record Choice(int endExclusive, WaterLineSegment segment) {
  }

  private record SwimEntry(BetterBlockPos src, BetterBlockPos waterStart) {
  }
}
