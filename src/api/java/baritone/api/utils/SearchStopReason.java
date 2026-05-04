package baritone.api.utils;

public sealed interface SearchStopReason permits SearchStopReason.GoalReached, SearchStopReason.EmptyChunkLimit, SearchStopReason.Timeout, SearchStopReason.ExhaustedOpenSet,
  SearchStopReason.Cancelled, SearchStopReason.ExceptionThrown, SearchStopReason.NoPath {

  record GoalReached(int nodesConsidered, int movementsConsidered) implements SearchStopReason {
  }

  record EmptyChunkLimit(int emptyChunkFetches, int limit, int nodesConsidered) implements SearchStopReason {
  }

  record Timeout(boolean failureTimeout, int nodesConsidered, int movementsConsidered) implements SearchStopReason {
  }

  record ExhaustedOpenSet(int nodesConsidered, int movementsConsidered) implements SearchStopReason {
  }

  record Cancelled() implements SearchStopReason {
  }

  record ExceptionThrown(String exception) implements SearchStopReason {
  }

  record NoPath(String detail) implements SearchStopReason {
  }
}
