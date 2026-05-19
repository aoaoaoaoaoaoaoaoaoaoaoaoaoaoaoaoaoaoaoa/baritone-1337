package baritone.pathing.meso;

public interface MesoTaskDomain<I extends MesoTaskIntent, P extends ExecutableMesoTaskPlan> {
  MesoTaskQuote quoteFast(MesoTaskRequest<I> request);

  default MesoTaskQuote quoteBounded(MesoTaskRequest<I> request, MesoTaskBudget budget) {
    return quoteFast(request);
  }

  MesoTaskResult<P> solve(MesoTaskRequest<I> request, MesoTaskBudget budget);
}
