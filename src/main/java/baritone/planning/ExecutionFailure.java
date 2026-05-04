package baritone.planning;

public sealed interface ExecutionFailure permits ExecutionFailure.SafetyViolation, ExecutionFailure.ProgressStall, ExecutionFailure.PreconditionLost, ExecutionFailure.ResourceMissing,
  ExecutionFailure.ControlDesync, ExecutionFailure.LocalPlannerFailure, ExecutionFailure.Timeout {

  LegId leg();

  PhaseKey phase();

  ObservationSnapshot observation();

  record SafetyViolation(LegId leg, PhaseKey phase, SafetyVerdict verdict, ObservationSnapshot observation) implements ExecutionFailure {
  }

  record ProgressStall(LegId leg, PhaseKey phase, ProgressSample lastProgress, ObservationSnapshot observation) implements ExecutionFailure {
  }

  record PreconditionLost(LegId leg, PhaseKey phase, String requirement, ObservationSnapshot observation) implements ExecutionFailure {
  }

  record ResourceMissing(LegId leg, PhaseKey phase, ResourceType resource, long quantity, ObservationSnapshot observation) implements ExecutionFailure {
  }

  record ControlDesync(LegId leg, PhaseKey phase, PlannedLocomotion planned, ActualMode actual, ObservationSnapshot observation) implements ExecutionFailure {
  }

  record LocalPlannerFailure(LegId leg, PhaseKey phase, String failure, ObservationSnapshot observation) implements ExecutionFailure {
  }

  record Timeout(LegId leg, PhaseKey phase, int ticks, ObservationSnapshot observation) implements ExecutionFailure {
  }
}
