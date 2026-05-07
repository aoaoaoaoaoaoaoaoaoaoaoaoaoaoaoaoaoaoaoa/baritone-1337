package baritone.pathing.macro.core;

import baritone.Baritone;
import baritone.pathing.movement.CalculationContext;

public record MacroCapabilities(boolean boatAvailable, int obsidianBlocks, int portalIgnitionUses) {
  public MacroCapabilities {
    if (obsidianBlocks < 0 || portalIgnitionUses < 0) {
      throw new IllegalArgumentException("portal resources must be nonnegative: obsidian=" + obsidianBlocks + " ignition=" + portalIgnitionUses);
    }
  }

  public static MacroCapabilities physical(CalculationContext context) {
    Baritone baritone = (Baritone) context.baritone;
    return new MacroCapabilities(context.waterTransport.boatAvailable() || context.waterTransport.boatMounted(), baritone.getInventoryBehavior().obsidianBlocks(),
      baritone.getInventoryBehavior().portalIgnitionUses());
  }

  public int netherKits() {
    return Math.min(obsidianBlocks / 10, portalIgnitionUses);
  }

  public boolean canBuildPortal() {
    return canCompletePortal(10);
  }

  public boolean canBuildPortalPair() {
    return canCompletePortalPair(10, 10);
  }

  public boolean canCompletePortal(int missingObsidian) {
    return missingObsidian >= 0 && obsidianBlocks >= missingObsidian && portalIgnitionUses >= 1;
  }

  public boolean canCompletePortalPair(int firstMissingObsidian, int secondMissingObsidian) {
    return firstMissingObsidian >= 0 && secondMissingObsidian >= 0 && obsidianBlocks >= firstMissingObsidian + secondMissingObsidian && portalIgnitionUses >= 2;
  }
}
