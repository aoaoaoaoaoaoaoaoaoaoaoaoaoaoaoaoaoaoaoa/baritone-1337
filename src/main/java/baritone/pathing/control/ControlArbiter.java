package baritone.pathing.control;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import baritone.pathing.control.ControlFrame.VehicleCommand;
import baritone.pathing.control.ControlFrame.WorldInteraction;
import baritone.utils.InputOverrideHandler;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.EntityHitResult;

public final class ControlArbiter {
  private final Baritone baritone;
  private final IPlayerContext ctx;

  public ControlArbiter(Baritone baritone) {
    this.baritone = baritone;
    this.ctx = baritone.getPlayerContext();
  }

  public void clearPathingControls() {
    InputOverrideHandler input = baritone.getInputOverrideHandler();
    input.clearAllKeys();
    input.getBlockBreakHelper().stopBreakingBlock();
    applyVehicle(VehicleCommand.IDLE);
  }

  public void apply(ControlFrame frame) {
    apply(frame, ObservationSnapshot.capture(ctx));
  }

  public void apply(ControlFrame frame, ObservationSnapshot observation) {
    InputOverrideHandler input = baritone.getInputOverrideHandler();
    input.clearAllKeys();

    if (frame == null) {
      applyVehicle(VehicleCommand.IDLE);
      return;
    }

    Integer hotbarSlot = frame.selectedHotbarSlot();
    if (hotbarSlot != null && hotbarSlot >= 0 && hotbarSlot < 9) {
      ctx.player().getInventory().setSelectedSlot(hotbarSlot);
    }

    frame.target().getRotation().ifPresent(rotation -> baritone.getLookBehavior().updateTarget(rotation, frame.target().hasToForceRotations()));

    frame.inputStates().forEach((key, forced) -> input.setInputForceState(key, forced));

    if (!frame.input(Input.CLICK_LEFT)) {
      input.getBlockBreakHelper().stopBreakingBlock();
    }

    applyVehicle(frame.vehicleCommand() == null ? VehicleCommand.IDLE : frame.vehicleCommand());

    if (frame.dismount()) {
      ctx.player().stopRiding();
    }

    for (WorldInteraction interaction : frame.interactions()) {
      switch (interaction) {
        case WorldInteraction.ItemUse use -> {
          ctx.playerController().processRightClick(ctx.player(), ctx.world(), use.hand());
          if (use.swing()) {
            ctx.player().swing(use.hand());
          }
        }
        case WorldInteraction.EntityUse use -> {
          ctx.minecraft().gameMode.interact(ctx.player(), use.entity(), new EntityHitResult(use.entity()), use.hand());
          if (use.swing()) {
            ctx.player().swing(use.hand());
          }
        }
        case WorldInteraction.EntityAttack attack -> {
          ctx.minecraft().gameMode.attack(ctx.player(), attack.entity());
          if (attack.swing()) {
            ctx.player().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
          }
        }
      }
    }
  }

  private void applyVehicle(VehicleCommand command) {
    if (ctx.player() != null && ctx.player().getVehicle() instanceof AbstractBoat boat) {
      boat.setInput(command.left(), command.right(), command.forward(), command.back());
    }
  }
}
