package baritone.api.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

public record GeofenceBox(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  private static final Pattern SERIALIZED = Pattern.compile("\\s*([^@,]+)@(-?\\d+)/(-?\\d+)/(-?\\d+)\\.\\.(-?\\d+)/(-?\\d+)/(-?\\d+)\\s*");
  private static final Pattern COORDINATES_ONLY = Pattern.compile("\\s*(-?\\d+)/(-?\\d+)/(-?\\d+)\\.\\.(-?\\d+)/(-?\\d+)/(-?\\d+)\\s*");

  public GeofenceBox {
    dimension = java.util.Objects.requireNonNull(dimension);
    int ax = Math.min(minX, maxX);
    int ay = Math.min(minY, maxY);
    int az = Math.min(minZ, maxZ);
    int bx = Math.max(minX, maxX);
    int by = Math.max(minY, maxY);
    int bz = Math.max(minZ, maxZ);
    minX = ax;
    minY = ay;
    minZ = az;
    maxX = bx;
    maxY = by;
    maxZ = bz;
  }

  public static GeofenceBox between(String dimension, BetterBlockPos a, BetterBlockPos b) {
    return new GeofenceBox(dimension, a.x, a.y, a.z, b.x, b.y, b.z);
  }

  public static GeofenceBox parse(String raw) {
    Matcher matcher = SERIALIZED.matcher(raw);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Expected dimension@x/y/z..x/y/z geofence box, got " + raw);
    }
    return new GeofenceBox(matcher.group(1), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
        Integer.parseInt(matcher.group(6)), Integer.parseInt(matcher.group(7)));
  }

  public static GeofenceBox parse(String raw, String defaultDimension) {
    Matcher serialized = SERIALIZED.matcher(raw);
    if (serialized.matches()) {
      return parse(raw);
    }
    Matcher coordinates = COORDINATES_ONLY.matcher(raw);
    if (!coordinates.matches()) {
      throw new IllegalArgumentException("Expected x/y/z..x/y/z geofence box, got " + raw);
    }
    return new GeofenceBox(defaultDimension, Integer.parseInt(coordinates.group(1)), Integer.parseInt(coordinates.group(2)), Integer.parseInt(coordinates.group(3)),
        Integer.parseInt(coordinates.group(4)), Integer.parseInt(coordinates.group(5)), Integer.parseInt(coordinates.group(6)));
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }

  public boolean contains(BlockPos pos) {
    return contains(pos.getX(), pos.getY(), pos.getZ());
  }

  public String serialized() {
    return dimension + "@" + minX + "/" + minY + "/" + minZ + ".." + maxX + "/" + maxY + "/" + maxZ;
  }

  public String describe() {
    return dimension + " (" + minX + ", " + minY + ", " + minZ + ") → (" + maxX + ", " + maxY + ", " + maxZ + ")";
  }

  @Override
  public String toString() {
    return serialized();
  }
}
