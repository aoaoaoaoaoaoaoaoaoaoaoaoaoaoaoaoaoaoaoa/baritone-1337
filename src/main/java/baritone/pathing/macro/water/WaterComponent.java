package baritone.pathing.macro.water;

import baritone.pathing.transport.TransportMode;
import it.unimi.dsi.fastutil.longs.LongArrayList;

public record WaterComponent(int id, TransportMode mode, int surfaceY, LongArrayList cells, LongArrayList boundaryCells, LongArrayList launchCells, LongArrayList landingCells,
  LongArrayList frontierCells, LongArrayList chokeCells) {
  public WaterComponent {
    cells = new LongArrayList(cells);
    boundaryCells = new LongArrayList(boundaryCells);
    launchCells = new LongArrayList(launchCells);
    landingCells = new LongArrayList(landingCells);
    frontierCells = new LongArrayList(frontierCells);
    chokeCells = new LongArrayList(chokeCells);
  }
}
