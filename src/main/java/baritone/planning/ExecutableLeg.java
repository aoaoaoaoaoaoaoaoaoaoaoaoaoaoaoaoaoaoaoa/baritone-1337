package baritone.planning;

import java.util.Optional;

public sealed interface ExecutableLeg permits WalkPathLeg, SurfaceLineLeg {
  LegId id();

  StableBoundary start();

  StableBoundary end();

  SafetyContract safety();

  ProgressContract progress();

  Optional<SplicePoint> splicePoint(ObservationSnapshot observation);
}
