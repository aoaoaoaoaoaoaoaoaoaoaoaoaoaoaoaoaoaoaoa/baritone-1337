package baritone.pathing.meso;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.macro.core.MacroCostVector;
import java.util.List;

public interface ExecutableMesoTaskPlan {
  BetterBlockPos approachAnchor();

  MacroCostVector estimate();

  List<BetterBlockPos> renderPositions();
}
