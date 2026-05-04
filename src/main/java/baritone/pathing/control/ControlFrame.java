package baritone.pathing.control;

import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

public record ControlFrame(Map<Input, Boolean> inputStates, MovementTarget target, Integer selectedHotbarSlot, List<WorldInteraction> interactions, boolean dismount, VehicleCommand vehicleCommand) {

  public static final ControlFrame EMPTY = ControlFrame.builder().build();

  public ControlFrame {
    inputStates = Map.copyOf(inputStates);
    target = target == null ? new MovementTarget() : target.copy();
    interactions = List.copyOf(interactions);
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean input(Input input) {
    return inputStates.getOrDefault(input, false);
  }

  public Builder mutate() {
    Builder builder = builder();
    builder.status = MovementStatus.RUNNING;
    builder.target = target.copy();
    builder.inputState.putAll(inputStates);
    builder.selectedHotbarSlot = selectedHotbarSlot;
    builder.interactions.addAll(interactions);
    builder.dismount = dismount;
    builder.vehicleCommand = vehicleCommand;
    return builder;
  }

  public static final class Builder {
    private MovementStatus status = MovementStatus.PREPPING;
    private MovementTarget target = new MovementTarget();
    private final EnumMap<Input, Boolean> inputState = new EnumMap<>(Input.class);
    private Integer selectedHotbarSlot;
    private final ArrayList<WorldInteraction> interactions = new ArrayList<>();
    private boolean dismount;
    private VehicleCommand vehicleCommand;

    private Builder() {
    }

    public Builder beginTick() {
      inputState.clear();
      selectedHotbarSlot = null;
      interactions.clear();
      dismount = false;
      vehicleCommand = null;
      return this;
    }

    public Builder setStatus(MovementStatus status) {
      this.status = status;
      return this;
    }

    public MovementStatus getStatus() { return status; }

    public MovementTarget getTarget() { return target; }

    public Builder setTarget(MovementTarget target) {
      this.target = target == null ? new MovementTarget() : target;
      return this;
    }

    public Builder setInput(Input input, boolean forced) {
      inputState.put(input, forced);
      return this;
    }

    public Map<Input, Boolean> getInputStates() { return inputState; }

    public Builder selectHotbarSlot(int slot) {
      selectedHotbarSlot = slot;
      return this;
    }

    public Optional<Integer> selectedHotbarSlot() {
      return Optional.ofNullable(selectedHotbarSlot);
    }

    public Builder useItem(InteractionHand hand) {
      interactions.add(new WorldInteraction.ItemUse(hand, true));
      return this;
    }

    public Builder useEntity(Entity entity, InteractionHand hand) {
      interactions.add(new WorldInteraction.EntityUse(entity, hand, true));
      return this;
    }

    public Builder attackEntity(Entity entity) {
      interactions.add(new WorldInteraction.EntityAttack(entity, true));
      return this;
    }

    public Builder dismount() {
      dismount = true;
      return this;
    }

    public Builder vehicle(VehicleCommand command) {
      vehicleCommand = command;
      return this;
    }

    public ControlFrame build() {
      return new ControlFrame(inputState, target, selectedHotbarSlot, interactions, dismount, vehicleCommand);
    }
  }

  public static final class MovementTarget {
    /**
     * Yaw and pitch angles that must be matched.
     */
    public Rotation rotation;

    private boolean forceRotations;

    public MovementTarget() {
      this(null, false);
    }

    public MovementTarget(Rotation rotation, boolean forceRotations) {
      this.rotation = rotation;
      this.forceRotations = forceRotations;
    }

    public Optional<Rotation> getRotation() { return Optional.ofNullable(rotation); }

    public boolean hasToForceRotations() {
      return forceRotations;
    }

    private MovementTarget copy() {
      return new MovementTarget(rotation, forceRotations);
    }
  }

  public sealed interface WorldInteraction permits WorldInteraction.ItemUse, WorldInteraction.EntityUse, WorldInteraction.EntityAttack {
    record ItemUse(InteractionHand hand, boolean swing) implements WorldInteraction {
    }

    record EntityUse(Entity entity, InteractionHand hand, boolean swing) implements WorldInteraction {
    }

    record EntityAttack(Entity entity, boolean swing) implements WorldInteraction {
    }
  }

  public record VehicleCommand(boolean left, boolean right, boolean forward, boolean back) {
    public static final VehicleCommand IDLE = new VehicleCommand(false, false, false, false);
  }
}
