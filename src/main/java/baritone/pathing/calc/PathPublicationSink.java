package baritone.pathing.calc;

import baritone.api.utils.PathCalculationResult;

@FunctionalInterface
public interface PathPublicationSink {
  PathPublicationSink IGNORE = result -> {
  };

  void publish(PathCalculationResult result);
}
