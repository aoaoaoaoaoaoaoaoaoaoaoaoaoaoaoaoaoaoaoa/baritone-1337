package baritone.pathing.macro.portal;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroNodeKey;

public final class PortalGeometry {
  public static final int NETHER_SCALE = 8;
  public static final int OVERWORLD_SEARCH_RADIUS = 128;
  public static final int NETHER_SEARCH_RADIUS = 16;

  private PortalGeometry() {
  }

  public static int pairedDimension(int dimensionId) {
    return switch (dimensionId) {
      case MacroNodeKey.DIMENSION_OVERWORLD -> MacroNodeKey.DIMENSION_NETHER;
      case MacroNodeKey.DIMENSION_NETHER -> MacroNodeKey.DIMENSION_OVERWORLD;
      default -> throw new IllegalArgumentException("portals do not connect dimension id " + dimensionId);
    };
  }

  public static boolean portalDimension(int dimensionId) {
    return dimensionId == MacroNodeKey.DIMENSION_OVERWORLD || dimensionId == MacroNodeKey.DIMENSION_NETHER;
  }

  public static BetterBlockPos transform(BetterBlockPos pos, int fromDimensionId, int toDimensionId) {
    if (fromDimensionId == toDimensionId) {
      return pos;
    }
    if (fromDimensionId == MacroNodeKey.DIMENSION_OVERWORLD && toDimensionId == MacroNodeKey.DIMENSION_NETHER) {
      return new BetterBlockPos(floorDiv(pos.x, NETHER_SCALE), pos.y, floorDiv(pos.z, NETHER_SCALE));
    }
    if (fromDimensionId == MacroNodeKey.DIMENSION_NETHER && toDimensionId == MacroNodeKey.DIMENSION_OVERWORLD) {
      return new BetterBlockPos(saturatingScale(pos.x, NETHER_SCALE), pos.y, saturatingScale(pos.z, NETHER_SCALE));
    }
    throw new IllegalArgumentException("unsupported portal transform " + fromDimensionId + " -> " + toDimensionId);
  }

  public static int existingPortalSearchRadius(int destinationDimensionId) {
    return destinationDimensionId == MacroNodeKey.DIMENSION_OVERWORLD ? OVERWORLD_SEARCH_RADIUS : NETHER_SEARCH_RADIUS;
  }

  public static boolean wouldSnapToExistingPortal(BetterBlockPos desired, BetterBlockPos existing, int destinationDimensionId) {
    int radius = existingPortalSearchRadius(destinationDimensionId);
    int dx = desired.x - existing.x;
    int dz = desired.z - existing.z;
    return dx * dx + dz * dz <= radius * radius;
  }

  public static boolean wouldSourcePortalRelink(BetterBlockPos sourcePortal, int sourceDimensionId, BetterBlockPos existingDestinationPortal) {
    int destinationDimensionId = pairedDimension(sourceDimensionId);
    return wouldSnapToExistingPortal(transform(sourcePortal, sourceDimensionId, destinationDimensionId), existingDestinationPortal, destinationDimensionId);
  }

  public static int axisSeparationBeyondSearchRadius(int sourceDimensionId, int destinationDimensionId) {
    int destinationRadius = existingPortalSearchRadius(destinationDimensionId);
    if (sourceDimensionId == destinationDimensionId) {
      return destinationRadius + 1;
    }
    if (sourceDimensionId == MacroNodeKey.DIMENSION_NETHER && destinationDimensionId == MacroNodeKey.DIMENSION_OVERWORLD) {
      return Math.floorDiv(destinationRadius, NETHER_SCALE) + 1;
    }
    if (sourceDimensionId == MacroNodeKey.DIMENSION_OVERWORLD && destinationDimensionId == MacroNodeKey.DIMENSION_NETHER) {
      return destinationRadius * NETHER_SCALE + 1;
    }
    throw new IllegalArgumentException("unsupported portal separation " + sourceDimensionId + " -> " + destinationDimensionId);
  }

  public static int targetCellX(int sourceCellX, int fromDimensionId, int toDimensionId) {
    return sourceCell(fromDimensionId, toDimensionId, sourceCellX);
  }

  public static int targetCellZ(int sourceCellZ, int fromDimensionId, int toDimensionId) {
    return sourceCell(fromDimensionId, toDimensionId, sourceCellZ);
  }

  private static int sourceCell(int fromDimensionId, int toDimensionId, int sourceCell) {
    if (fromDimensionId == toDimensionId) {
      return sourceCell;
    }
    if (fromDimensionId == MacroNodeKey.DIMENSION_OVERWORLD && toDimensionId == MacroNodeKey.DIMENSION_NETHER) {
      return floorDiv(sourceCell, NETHER_SCALE);
    }
    if (fromDimensionId == MacroNodeKey.DIMENSION_NETHER && toDimensionId == MacroNodeKey.DIMENSION_OVERWORLD) {
      return saturatingScale(sourceCell, NETHER_SCALE);
    }
    throw new IllegalArgumentException("unsupported portal cell transform " + fromDimensionId + " -> " + toDimensionId);
  }

  private static int floorDiv(int value, int divisor) {
    return Math.floorDiv(value, divisor);
  }

  private static int saturatingScale(int value, int scale) {
    long scaled = (long) value * scale;
    if (scaled > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    if (scaled < Integer.MIN_VALUE) {
      return Integer.MIN_VALUE;
    }
    return (int) scaled;
  }
}
