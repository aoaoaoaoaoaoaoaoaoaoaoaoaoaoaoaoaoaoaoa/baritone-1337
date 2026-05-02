package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;

/**
 * @author Brady
 */
public final class NullElytraProcess extends BaritoneProcessHelper implements IElytraProcess {

    public NullElytraProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void repackChunks() {
        throw new UnsupportedOperationException("Called repackChunks() on NullElytraBehavior");
    }

    @Override
    public BlockPos currentDestination() {
        return null;
    }

    @Override
    public void pathTo(BlockPos destination) {
        throw new UnsupportedOperationException("Called pathTo() on NullElytraBehavior");
    }

    @Override
    public void pathTo(Goal destination) {
        throw new UnsupportedOperationException("Called pathTo() on NullElytraBehavior");
    }

    @Override
    public void resetState() {

    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        throw new UnsupportedOperationException("Called onTick on NullElytraProcess");
    }

    @Override
    public void onLostControl() {

    }

    @Override
    public String displayName0() {
        return "NullElytraProcess";
    }

    @Override
    public boolean isLoaded() {
        return false;
    }

    @Override
    public boolean isSafeToCancel() {
        return true;
    }
}
