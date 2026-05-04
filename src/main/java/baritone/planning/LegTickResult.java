package baritone.planning;

public sealed interface LegTickResult<P> permits LegTickResult.Continue, LegTickResult.Advance, LegTickResult.Complete, LegTickResult.Recover, LegTickResult.Fail {
  record Continue<P>(P phase, ControlFrame frame, ProgressSample progress) implements LegTickResult<P> {
  }

  record Advance<P>(P nextPhase, ControlFrame frame) implements LegTickResult<P> {
  }

  record Complete<P>(AgentState observedEnd) implements LegTickResult<P> {
  }

  record Recover<P>(RecoveryPolicy recovery, ControlFrame frame) implements LegTickResult<P> {
  }

  record Fail<P>(ExecutionFailure failure) implements LegTickResult<P> {
  }
}
