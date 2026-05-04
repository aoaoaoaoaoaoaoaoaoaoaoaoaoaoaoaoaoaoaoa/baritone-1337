package baritone.planning;

public sealed interface VehicleCommand permits VehicleCommand.BoatInput {
  record BoatInput(boolean left, boolean right, boolean forward, boolean back) implements VehicleCommand {
  }
}
