package baritone.planning;

public sealed interface SafetyVerdict permits SafetyVerdict.Safe, SafetyVerdict.Unsafe {
  record Safe() implements SafetyVerdict {
  }

  record Unsafe(String reason, RecoveryPolicy recovery) implements SafetyVerdict {
  }
}
