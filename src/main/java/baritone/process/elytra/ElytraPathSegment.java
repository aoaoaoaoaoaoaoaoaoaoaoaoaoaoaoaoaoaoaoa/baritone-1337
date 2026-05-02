package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;
import dev.babbaj.pathfinder.PathSegment;
import java.util.Arrays;
import java.util.List;

public record ElytraPathSegment(boolean finished, List<BetterBlockPos> positions) {
  static ElytraPathSegment from(PathSegment segment) {
    return new ElytraPathSegment(segment.finished, Arrays.stream(segment.packed).mapToObj(BetterBlockPos::deserializeFromLong).toList());
  }
}
