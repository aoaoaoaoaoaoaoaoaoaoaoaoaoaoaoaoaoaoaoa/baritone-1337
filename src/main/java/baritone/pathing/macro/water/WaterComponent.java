package baritone.pathing.macro.water;

import baritone.pathing.transport.TransportMode;
import it.unimi.dsi.fastutil.longs.LongArrayList;

public record WaterComponent(int id, TransportMode mode, int surfaceY, LongArrayList cells, LongArrayList boundaryCells, LongArrayList launchCells, LongArrayList landingCells,
  LongArrayList frontierCells, LongArrayList chokeCells) {
}
