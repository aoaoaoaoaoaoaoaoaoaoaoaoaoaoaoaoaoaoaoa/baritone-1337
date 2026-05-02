package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 */
public final class NetherPath extends AbstractList<BetterBlockPos> {

    private static final NetherPath EMPTY_PATH = new NetherPath(Collections.emptyList());

    private final List<BetterBlockPos> backing;

    NetherPath(List<BetterBlockPos> backing) {
        this.backing = backing;
    }

    @Override
    public BetterBlockPos get(int index) {
        return this.backing.get(index);
    }

    @Override
    public int size() {
        return this.backing.size();
    }

    /**
     * @return The last position in the path, or {@code null} if empty
     */
    public BetterBlockPos getLast() {
        return this.isEmpty() ? null : this.backing.get(this.backing.size() - 1);
    }

    public Vec3 getVec(int index) {
        final BetterBlockPos pos = this.get(index);
        return new Vec3(pos.x, pos.y, pos.z);
    }

    public static NetherPath emptyPath() {
        return EMPTY_PATH;
    }
}
