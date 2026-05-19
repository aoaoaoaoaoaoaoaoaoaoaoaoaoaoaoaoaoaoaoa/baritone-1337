package baritone.pathing.meso;

import baritone.api.pathing.goals.Goal;

public sealed interface MesoTaskResult<P extends ExecutableMesoTaskPlan> permits MesoTaskResult.Solved, MesoTaskResult.NeedSurvey, MesoTaskResult.RefinedTooExpensive, MesoTaskResult.Impossible {
  record Solved<P extends ExecutableMesoTaskPlan>(P plan, MesoTaskQuote quote) implements MesoTaskResult<P> {
  }

  record NeedSurvey<P extends ExecutableMesoTaskPlan>(Goal surveyGoal, MesoTaskQuote quote) implements MesoTaskResult<P> {
  }

  record RefinedTooExpensive<P extends ExecutableMesoTaskPlan>(MesoTaskQuote quote) implements MesoTaskResult<P> {
  }

  record Impossible<P extends ExecutableMesoTaskPlan>(MesoTaskFailure failure, MesoTaskQuote quote) implements MesoTaskResult<P> {
  }
}
