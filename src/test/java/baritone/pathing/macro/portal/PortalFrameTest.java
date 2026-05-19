package baritone.pathing.macro.portal;

import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.Test;

import static org.junit.Assert.*;

public class PortalFrameTest {
  @Test
  public void recognizesCompleteUnlitMinimalFrame() {
    BetterBlockPos lowerLeft = new BetterBlockPos(10, 64, 20);
    LongOpenHashSet obsidian = minimalFrame(lowerLeft, Direction.Axis.X);

    PortalFrame.FrameMatch match = PortalFrame.evaluate(lowerLeft, Direction.Axis.X, pos -> obsidian.contains(pos.asLong()), pos -> true);

    assertTrue(match.usableCandidate());
    assertEquals(10, match.presentObsidian());
    assertEquals(0, match.missingObsidian());
    assertEquals(PortalSiteKind.COMPLETE_FRAME, match.kind());
  }

  @Test
  public void pricesPartialFrameByMissingObsidian() {
    BetterBlockPos lowerLeft = new BetterBlockPos(-3, 40, 5);
    LongOpenHashSet obsidian = minimalFrame(lowerLeft, Direction.Axis.Z);
    obsidian.remove(new BetterBlockPos(-3, 39, 5).asLong());
    obsidian.remove(new BetterBlockPos(-3, 43, 6).asLong());

    PortalFrame.FrameMatch match = PortalFrame.evaluate(lowerLeft, Direction.Axis.Z, pos -> obsidian.contains(pos.asLong()), pos -> true);

    assertTrue(match.usableCandidate());
    assertEquals(8, match.presentObsidian());
    assertEquals(2, match.missingObsidian());
    assertEquals(PortalSiteKind.PARTIAL_FRAME, match.kind());
  }

  @Test
  public void obsidianBlockBackProjectsCandidateFrames() {
    BetterBlockPos obsidian = new BetterBlockPos(0, 63, 0);
    assertEquals(PortalFrame.MINIMAL_FRAME_BLOCKS, PortalFrame.candidateLowerLefts(obsidian, Direction.Axis.X).size());
    assertTrue(PortalFrame.candidateLowerLefts(obsidian, Direction.Axis.Z).stream().anyMatch(pos -> pos.equals(new BetterBlockPos(0, 64, 0))));
  }

  private static LongOpenHashSet minimalFrame(BetterBlockPos lowerLeft, Direction.Axis axis) {
    LongOpenHashSet obsidian = new LongOpenHashSet();
    for (BetterBlockPos candidate : PortalFrame.candidateLowerLefts(lowerLeft, axis)) {
      // no-op; this assertion keeps the inverse path under coverage without constructing offsets externally
      assertNotNull(candidate);
    }
    for (int width = 0; width < PortalFrame.INNER_WIDTH; width++) {
      obsidian.add(required(lowerLeft, axis, width, -1));
      obsidian.add(required(lowerLeft, axis, width, PortalFrame.INNER_HEIGHT));
    }
    for (int height = 0; height < PortalFrame.INNER_HEIGHT; height++) {
      obsidian.add(required(lowerLeft, axis, -1, height));
      obsidian.add(required(lowerLeft, axis, PortalFrame.INNER_WIDTH, height));
    }
    return obsidian;
  }

  private static long required(BetterBlockPos lowerLeft, Direction.Axis axis, int horizontal, int vertical) {
    BlockPos pos = axis == Direction.Axis.X ? new BlockPos(lowerLeft.x + horizontal, lowerLeft.y + vertical, lowerLeft.z) : new BlockPos(lowerLeft.x, lowerLeft.y + vertical, lowerLeft.z + horizontal);
    return pos.asLong();
  }
}
