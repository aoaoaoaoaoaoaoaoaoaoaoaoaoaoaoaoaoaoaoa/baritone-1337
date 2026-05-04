package baritone.planning;

public sealed interface SafetyInvariant
  permits SafetyInvariant.AirReserve, SafetyInvariant.ElytraDurability, SafetyInvariant.FireworkReserve, SafetyInvariant.VehicleOwnership, SafetyInvariant.Geofence {

  record AirReserve(int minimumAir, RecoveryPolicy recovery) implements SafetyInvariant {
  }

  record ElytraDurability(int minimumDurability) implements SafetyInvariant {
  }

  record FireworkReserve(int minimumFireworks) implements SafetyInvariant {
  }

  record VehicleOwnership(VehicleKind kind) implements SafetyInvariant {
  }

  record Geofence() implements SafetyInvariant {
  }
}
