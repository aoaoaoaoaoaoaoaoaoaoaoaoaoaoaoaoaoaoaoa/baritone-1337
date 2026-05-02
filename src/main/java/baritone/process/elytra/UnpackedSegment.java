package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Brady
 */
public final class UnpackedSegment {
  private final Stream<BetterBlockPos> path;
  private final boolean finished;

  public UnpackedSegment(Stream<BetterBlockPos> path, boolean finished) {
    this.path = path;
    this.finished = finished;
  }

  public UnpackedSegment append(Stream<BetterBlockPos> other, boolean otherFinished) {
    return new UnpackedSegment(Stream.concat(this.path, other), otherFinished);
  }

  public UnpackedSegment prepend(Stream<BetterBlockPos> other) {
    return new UnpackedSegment(Stream.concat(other, this.path), this.finished);
  }

  public List<BetterBlockPos> collect() {
    final List<BetterBlockPos> path = this.path.collect(Collectors.toList());

    final Map<BetterBlockPos, Integer> positionFirstSeen = new HashMap<>();
    for (int i = 0; i < path.size(); i++) {
      BetterBlockPos pos = path.get(i);
      if (positionFirstSeen.containsKey(pos)) {
        int j = positionFirstSeen.get(pos);
        while (i > j) {
          path.remove(i);
          i--;
        }
      } else {
        positionFirstSeen.put(pos, i);
      }
    }

    return path;
  }

  public boolean isFinished() {
    return this.finished;
  }

  public static UnpackedSegment from(ElytraPathSegment segment) {
    return new UnpackedSegment(segment.positions().stream(), segment.finished());
  }
}
