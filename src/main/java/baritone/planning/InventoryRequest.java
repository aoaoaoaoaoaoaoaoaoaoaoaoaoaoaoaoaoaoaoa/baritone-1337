package baritone.planning;

public sealed interface InventoryRequest permits InventoryRequest.SelectHotbarSlot {
  record SelectHotbarSlot(int slot) implements InventoryRequest {
  }
}
