package baritone.planning;

import java.util.List;

public record SafetyContract(List<SafetyInvariant> invariants, RecoveryPolicy recovery) {
  public static final SafetyContract NONE = new SafetyContract(List.of(), RecoveryPolicy.NONE);

  public SafetyContract {
    invariants = List.copyOf(invariants);
  }
}
