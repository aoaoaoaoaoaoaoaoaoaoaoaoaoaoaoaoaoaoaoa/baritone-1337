package baritone.planning;

public interface LegController<P> {
  P initialPhase();

  LegTickResult<P> tick(P phase, ObservationSnapshot observation, LegMemory memory);
}
