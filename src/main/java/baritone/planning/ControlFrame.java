package baritone.planning;

import java.util.List;
import java.util.Optional;

public record ControlFrame(InputMask inputs, Optional<RotationTarget> rotation, Optional<VehicleCommand> vehicle, List<WorldInteraction> interactions, Optional<InventoryRequest> inventory) {
  public static final ControlFrame EMPTY = new ControlFrame(InputMask.NONE, Optional.empty(), Optional.empty(), List.of(), Optional.empty());

  public ControlFrame {
    interactions = List.copyOf(interactions);
  }
}
