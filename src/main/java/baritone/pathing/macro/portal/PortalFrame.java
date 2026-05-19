package baritone.pathing.macro.portal;

import baritone.api.utils.BetterBlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class PortalFrame {
  public static final int INNER_WIDTH = 2;
  public static final int INNER_HEIGHT = 3;
  public static final int MINIMAL_FRAME_BLOCKS = 10;
  private static final List<Offset> REQUIRED = requiredOffsets();

  private PortalFrame() {
  }

  public static FrameMatch evaluate(BetterBlockPos lowerLeftInterior, Direction.Axis axis, Predicate<BlockPos> obsidian, Predicate<BlockPos> openInterior) {
    int present = 0;
    for (Offset offset : REQUIRED) {
      if (obsidian.test(offset.apply(lowerLeftInterior, axis))) {
        present++;
      }
    }
    int open = 0;
    for (int width = 0; width < INNER_WIDTH; width++) {
      for (int height = 0; height < INNER_HEIGHT; height++) {
        if (openInterior.test(interior(lowerLeftInterior, axis, width, height))) {
          open++;
        }
      }
    }
    return new FrameMatch(lowerLeftInterior, axis, present, MINIMAL_FRAME_BLOCKS - present, open);
  }

  public static List<BetterBlockPos> candidateLowerLefts(BetterBlockPos obsidian, Direction.Axis axis) {
    ArrayList<BetterBlockPos> result = new ArrayList<>(REQUIRED.size());
    for (Offset offset : REQUIRED) {
      result.add(offset.unapply(obsidian, axis));
    }
    return result;
  }

  public static BetterBlockPos interior(BetterBlockPos lowerLeftInterior, Direction.Axis axis, int width, int height) {
    return switch (axis) {
      case X -> new BetterBlockPos(lowerLeftInterior.x + width, lowerLeftInterior.y + height, lowerLeftInterior.z);
      case Z -> new BetterBlockPos(lowerLeftInterior.x, lowerLeftInterior.y + height, lowerLeftInterior.z + width);
      default -> throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
    };
  }

  public static BetterBlockPos frameBlock(BetterBlockPos lowerLeftInterior, Direction.Axis axis, int horizontal, int vertical) {
    if (!requiredFrameOffset(horizontal, vertical)) {
      throw new IllegalArgumentException("not a required portal frame offset: horizontal=" + horizontal + " vertical=" + vertical);
    }
    return switch (axis) {
      case X -> new BetterBlockPos(lowerLeftInterior.x + horizontal, lowerLeftInterior.y + vertical, lowerLeftInterior.z);
      case Z -> new BetterBlockPos(lowerLeftInterior.x, lowerLeftInterior.y + vertical, lowerLeftInterior.z + horizontal);
      default -> throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
    };
  }

  public static boolean requiredFrameOffset(int horizontal, int vertical) {
    return vertical == -1 && horizontal >= 0 && horizontal < INNER_WIDTH || vertical == INNER_HEIGHT && horizontal >= 0 && horizontal < INNER_WIDTH
      || horizontal == -1 && vertical >= 0 && vertical < INNER_HEIGHT || horizontal == INNER_WIDTH && vertical >= 0 && vertical < INNER_HEIGHT;
  }

  public static boolean interiorOffset(int horizontal, int vertical) {
    return horizontal >= 0 && horizontal < INNER_WIDTH && vertical >= 0 && vertical < INNER_HEIGHT;
  }

  public static BetterBlockPos center(FrameMatch frame) {
    return frame.lowerLeftInterior();
  }

  private static List<Offset> requiredOffsets() {
    ArrayList<Offset> offsets = new ArrayList<>(MINIMAL_FRAME_BLOCKS);
    for (int horizontal = -1; horizontal <= INNER_WIDTH; horizontal++) {
      for (int vertical = -1; vertical <= INNER_HEIGHT; vertical++) {
        if (requiredFrameOffset(horizontal, vertical)) {
          offsets.add(new Offset(horizontal, vertical));
        }
      }
    }
    return List.copyOf(offsets);
  }

  private record Offset(int horizontal, int vertical) {
    private BetterBlockPos apply(BetterBlockPos lowerLeftInterior, Direction.Axis axis) {
      return switch (axis) {
        case X -> new BetterBlockPos(lowerLeftInterior.x + horizontal, lowerLeftInterior.y + vertical, lowerLeftInterior.z);
        case Z -> new BetterBlockPos(lowerLeftInterior.x, lowerLeftInterior.y + vertical, lowerLeftInterior.z + horizontal);
        default -> throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
      };
    }

    private BetterBlockPos unapply(BetterBlockPos frameBlock, Direction.Axis axis) {
      return switch (axis) {
        case X -> new BetterBlockPos(frameBlock.x - horizontal, frameBlock.y - vertical, frameBlock.z);
        case Z -> new BetterBlockPos(frameBlock.x, frameBlock.y - vertical, frameBlock.z - horizontal);
        default -> throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
      };
    }
  }

  public record FrameMatch(BetterBlockPos lowerLeftInterior, Direction.Axis axis, int presentObsidian, int missingObsidian, int openInterior) {
    public FrameMatch {
      if (lowerLeftInterior == null || axis == null) {
        throw new IllegalArgumentException("portal frame match requires lower-left interior and axis");
      }
      if (axis == Direction.Axis.Y) {
        throw new IllegalArgumentException("portal frame axis must be horizontal: " + axis);
      }
      if (presentObsidian < 0 || presentObsidian > MINIMAL_FRAME_BLOCKS || missingObsidian < 0 || missingObsidian > MINIMAL_FRAME_BLOCKS || presentObsidian + missingObsidian != MINIMAL_FRAME_BLOCKS) {
        throw new IllegalArgumentException("invalid portal frame obsidian counts: present=" + presentObsidian + " missing=" + missingObsidian);
      }
      if (openInterior < 0 || openInterior > INNER_WIDTH * INNER_HEIGHT) {
        throw new IllegalArgumentException("invalid portal interior count: " + openInterior);
      }
    }

    public boolean usableCandidate() {
      return openInterior == INNER_WIDTH * INNER_HEIGHT && presentObsidian > 0;
    }

    public PortalSiteKind kind() {
      if (missingObsidian == 0) {
        return PortalSiteKind.COMPLETE_FRAME;
      }
      return PortalSiteKind.PARTIAL_FRAME;
    }
  }
}
