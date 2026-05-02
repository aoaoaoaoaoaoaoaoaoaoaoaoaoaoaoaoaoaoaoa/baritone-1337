package baritone.utils.pathing;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.calc.BlockKey;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

public final class Favoring {

    private final Long2DoubleOpenHashMap favorings;

    public Favoring(IPlayerContext ctx, IPath previous, CalculationContext context) {
        this(previous, context);
        for (Avoidance avoid : Avoidance.create(ctx)) {
            avoid.applySpherical(favorings);
        }
        Helper.HELPER.logDebug("Favoring size: " + favorings.size());
    }

    public Favoring(IPath previous, CalculationContext context) { // create one just from previous path, no mob avoidances
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        double coeff = context.costs.backtrackFavoringCoefficient();
        if (coeff != 1D && previous != null) {
            previous.positions().forEach(pos -> favorings.put(BlockKey.pack(pos.getX(), pos.getY(), pos.getZ()), coeff));
        }
    }

    public boolean isEmpty() {
        return favorings.isEmpty();
    }

    public double calculate(long hash) {
        return favorings.get(hash);
    }
}
