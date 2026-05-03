package baritone.process.elytra;

import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.control.RotationGovernor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

final class ElytraSolverContext {

  final ElytraPath path;
  final int playerNear;
  final Vec3 start;
  final Vec3 motion;
  final AABB boundingBox;
  final boolean ignoreLava;
  final ElytraFireworkBoost boost;
  final ITickableAimProcessor aimProcessor;
  final RotationGovernor rotationGovernor;
  final ElytraControlDecision control;

  ElytraSolverContext(ElytraPath path, int playerNear, Vec3 start, Vec3 motion, AABB boundingBox, boolean ignoreLava, ElytraFireworkBoost boost, ITickableAimProcessor aimProcessor,
    RotationGovernor rotationGovernor, ElytraControlDecision control) {
    this.path = path;
    this.playerNear = playerNear;
    this.start = start;
    this.motion = motion;
    this.boundingBox = boundingBox;
    this.ignoreLava = ignoreLava;
    this.boost = boost;
    this.aimProcessor = aimProcessor;
    this.rotationGovernor = rotationGovernor;
    this.control = control;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ElytraSolverContext other)) {
      return false;
    }
    return path == other.path && playerNear == other.playerNear && ignoreLava == other.ignoreLava && Objects.equals(start, other.start) && Objects.equals(motion, other.motion)
      && Objects.equals(boundingBox, other.boundingBox) && Objects.equals(boost, other.boost) && Objects.equals(control, other.control);
  }

  @Override
  public int hashCode() {
    return Objects.hash(System.identityHashCode(path), playerNear, start, motion, boundingBox, ignoreLava, boost, control);
  }
}
