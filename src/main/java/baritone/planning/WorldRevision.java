package baritone.planning;

public record WorldRevision(long chunkRevision, long blockRevision, long inventoryRevision) {
  public static WorldRevision observed(InventorySnapshot inventory) {
    return new WorldRevision(0L, 0L, inventory.revision());
  }
}
