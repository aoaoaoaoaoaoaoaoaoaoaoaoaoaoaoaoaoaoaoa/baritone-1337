package baritone.pathing.movement;

import baritone.api.utils.GeofenceBox;
import java.util.List;

public final class ModificationGeofence {
  private static final ModificationGeofence EMPTY = new ModificationGeofence(new GeofenceBox[0], 0, 0, 0, 0, 0, 0);

  private final GeofenceBox[] boxes;
  private final int minX;
  private final int minY;
  private final int minZ;
  private final int maxX;
  private final int maxY;
  private final int maxZ;

  private ModificationGeofence(GeofenceBox[] boxes, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    this.boxes = boxes;
    this.minX = minX;
    this.minY = minY;
    this.minZ = minZ;
    this.maxX = maxX;
    this.maxY = maxY;
    this.maxZ = maxZ;
  }

  public static ModificationGeofence snapshot(String dimension, List<GeofenceBox> boxes) {
    GeofenceBox[] copy = boxes.stream().filter(box -> box.dimension().equals(dimension)).toArray(GeofenceBox[]::new);
    if (copy.length == 0) {
      return EMPTY;
    }
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (GeofenceBox box : copy) {
      minX = Math.min(minX, box.minX());
      minY = Math.min(minY, box.minY());
      minZ = Math.min(minZ, box.minZ());
      maxX = Math.max(maxX, box.maxX());
      maxY = Math.max(maxY, box.maxY());
      maxZ = Math.max(maxZ, box.maxZ());
    }
    return new ModificationGeofence(copy, minX, minY, minZ, maxX, maxY, maxZ);
  }

  public boolean forbids(int x, int y, int z) {
    if (boxes.length == 0 || x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
      return false;
    }
    for (GeofenceBox box : boxes) {
      if (box.contains(x, y, z)) {
        return true;
      }
    }
    return false;
  }
}
