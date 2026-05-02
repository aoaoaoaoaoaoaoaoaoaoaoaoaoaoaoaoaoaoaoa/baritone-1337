package baritone.pathing.movement;

public sealed interface DestinationSpec permits DestinationSpec.Static, DestinationSpec.Dynamic {
  BlockOffset precheckOffset();

  boolean dynamicXZ();

  boolean dynamicY();

  record Static(BlockOffset precheckOffset) implements DestinationSpec {
    @Override
    public boolean dynamicXZ() {
      return false;
    }

    @Override
    public boolean dynamicY() {
      return false;
    }
  }

  record Dynamic(BlockOffset precheckOffset, boolean dynamicXZ, boolean dynamicY) implements DestinationSpec {}
}
