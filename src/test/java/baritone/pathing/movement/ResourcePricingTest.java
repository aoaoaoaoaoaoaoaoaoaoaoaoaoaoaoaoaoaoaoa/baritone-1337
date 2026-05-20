package baritone.pathing.movement;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ResourcePricingTest {
  private static final ResourcePricing.Parameters PARAMS = new ResourcePricing.Parameters(true, false, 16, 96, 2D, 0.35D, 0.25D, 0.5D, 4D, 0.08D, 100);

  @Test
  public void abundantBlocksMakePlacementCheap() {
    ResourcePricing.Epoch epoch = ResourcePricing.classify(new ResourcePricing.Snapshot(128, 0, 0.5D), PARAMS, ResourcePricing.Epoch.BASE);
    ResourcePricing.Prices prices = ResourcePricing.prices(20D, 2D, epoch, PARAMS);
    assertEquals(ResourcePricing.BlockSupplyBand.ABUNDANT, epoch.blocks());
    assertEquals(7D, prices.placementPenalty(), 0D);
    assertEquals(2D, prices.breakAdditionalPenalty(), 0D);
  }

  @Test
  public void scarceBlocksMakePlacementDearAndBreakingCheap() {
    ResourcePricing.Epoch epoch = ResourcePricing.classify(new ResourcePricing.Snapshot(4, 0, 0.5D), PARAMS, ResourcePricing.Epoch.BASE);
    ResourcePricing.Prices prices = ResourcePricing.prices(20D, 2D, epoch, PARAMS);
    assertEquals(ResourcePricing.BlockSupplyBand.SCARCE, epoch.blocks());
    assertEquals(40D, prices.placementPenalty(), 0D);
    assertEquals(0.5D, prices.breakAdditionalPenalty(), 0D);
  }

  @Test
  public void fragilePickMakesPlacementCheapAndBreakingDear() {
    ResourcePricing.Epoch epoch = ResourcePricing.classify(new ResourcePricing.Snapshot(64, 0, 0.03D), PARAMS, ResourcePricing.Epoch.BASE);
    ResourcePricing.Prices prices = ResourcePricing.prices(20D, 2D, epoch, PARAMS);
    assertEquals(ResourcePricing.ToolEnduranceBand.FRAGILE, epoch.pick());
    assertEquals(10D, prices.placementPenalty(), 0D);
    assertEquals(8D, prices.breakAdditionalPenalty(), 0D);
  }

  @Test
  public void inventoryBlocksOnlyMatterWhenPolicyCountsThem() {
    ResourcePricing.Epoch hotbarOnly = ResourcePricing.classify(new ResourcePricing.Snapshot(4, 128, 0.5D), PARAMS, ResourcePricing.Epoch.BASE);
    ResourcePricing.Parameters countsInventory = new ResourcePricing.Parameters(true, true, 16, 96, 2D, 0.35D, 0.25D, 0.5D, 4D, 0.08D, 100);
    ResourcePricing.Epoch allInventory = ResourcePricing.classify(new ResourcePricing.Snapshot(4, 128, 0.5D), countsInventory, ResourcePricing.Epoch.BASE);
    assertEquals(ResourcePricing.BlockSupplyBand.SCARCE, hotbarOnly.blocks());
    assertEquals(ResourcePricing.BlockSupplyBand.ABUNDANT, allInventory.blocks());
  }

  @Test
  public void stateDelaysEpochAdoptionUntilThereIsHeadroomAndTickBudget() {
    ResourcePricingState state = new ResourcePricingState();
    ResourcePricing.Prices scarce = state.prices(new ResourcePricing.Snapshot(4, 0, 0.5D), PARAMS, 20D, 2D, 0, false);
    assertEquals(ResourcePricing.BlockSupplyBand.ORDINARY, scarce.epoch().blocks());
    assertEquals(ResourcePricing.BlockSupplyBand.SCARCE, state.pendingEpoch().blocks());

    ResourcePricing.Prices adopted = state.prices(new ResourcePricing.Snapshot(4, 0, 0.5D), PARAMS, 20D, 2D, 50, true);
    assertEquals(ResourcePricing.BlockSupplyBand.SCARCE, adopted.epoch().blocks());

    ResourcePricing.Prices tooSoonToReturnToOrdinary = state.prices(new ResourcePricing.Snapshot(64, 0, 0.5D), PARAMS, 20D, 2D, 100, true);
    assertEquals(ResourcePricing.BlockSupplyBand.SCARCE, tooSoonToReturnToOrdinary.epoch().blocks());

    ResourcePricing.Prices ordinary = state.prices(new ResourcePricing.Snapshot(64, 0, 0.5D), PARAMS, 20D, 2D, 150, true);
    assertEquals(ResourcePricing.BlockSupplyBand.ORDINARY, ordinary.epoch().blocks());
  }

  @Test
  public void blockBandsHaveHysteresis() {
    ResourcePricing.Epoch scarce = ResourcePricing.classify(new ResourcePricing.Snapshot(4, 0, 0.5D), PARAMS, ResourcePricing.Epoch.BASE);
    ResourcePricing.Epoch stillScarce = ResourcePricing.classify(new ResourcePricing.Snapshot(20, 0, 0.5D), PARAMS, scarce);
    ResourcePricing.Epoch escapedScarce = ResourcePricing.classify(new ResourcePricing.Snapshot(24, 0, 0.5D), PARAMS, scarce);
    assertEquals(ResourcePricing.BlockSupplyBand.SCARCE, stillScarce.blocks());
    assertEquals(ResourcePricing.BlockSupplyBand.ORDINARY, escapedScarce.blocks());
  }
}
