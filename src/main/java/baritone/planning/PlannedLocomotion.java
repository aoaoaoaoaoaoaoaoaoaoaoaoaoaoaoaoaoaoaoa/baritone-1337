package baritone.planning;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public sealed interface PlannedLocomotion
  permits PlannedLocomotion.Pedestrian, PlannedLocomotion.SurfaceSwim, PlannedLocomotion.MountedBoat, PlannedLocomotion.ElytraCruise, PlannedLocomotion.PortalTransit {

  ModeKind kind();

  record Pedestrian() implements PlannedLocomotion {
    @Override
    public ModeKind kind() {
      return ModeKind.PEDESTRIAN;
    }
  }

  record SurfaceSwim() implements PlannedLocomotion {
    @Override
    public ModeKind kind() {
      return ModeKind.SWIM;
    }
  }

  record MountedBoat(BoatLease lease) implements PlannedLocomotion {
    @Override
    public ModeKind kind() {
      return ModeKind.BOAT;
    }
  }

  record ElytraCruise() implements PlannedLocomotion {
    @Override
    public ModeKind kind() {
      return ModeKind.ELYTRA;
    }
  }

  record PortalTransit(ResourceKey<Level> targetDimension) implements PlannedLocomotion {
    @Override
    public ModeKind kind() {
      return ModeKind.PORTAL;
    }
  }
}
