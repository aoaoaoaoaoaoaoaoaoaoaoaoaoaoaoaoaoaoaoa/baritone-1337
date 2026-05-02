package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;
import baritone.transport.TransportKind;
import baritone.transport.TransportRoute;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public final class ElytraPath extends AbstractList<BetterBlockPos> implements TransportRoute {
  private static final ElytraPath EMPTY_PATH = new ElytraPath(Collections.emptyList(), true);

  private final List<BetterBlockPos> backing;
  private final boolean complete;

  ElytraPath(List<BetterBlockPos> backing, boolean complete) {
    this.backing = List.copyOf(backing);
    this.complete = complete;
  }

  @Override
  public BetterBlockPos get(int index) {
    return backing.get(index);
  }

  @Override
  public int size() {
    return backing.size();
  }

  /**
   * @return The last position in the path, or {@code null} if empty
   */
  public BetterBlockPos getLast() { return isEmpty() ? null : backing.get(backing.size() - 1); }

  public Vec3 getVec(int index) {
    BetterBlockPos pos = get(index);
    return new Vec3(pos.x, pos.y, pos.z);
  }

  @Override
  public TransportKind kind() {
    return TransportKind.ELYTRA;
  }

  @Override
  public List<BetterBlockPos> waypoints() {
    return backing;
  }

  @Override
  public boolean complete() {
    return complete;
  }

  public static ElytraPath emptyPath() {
    return EMPTY_PATH;
  }
}
