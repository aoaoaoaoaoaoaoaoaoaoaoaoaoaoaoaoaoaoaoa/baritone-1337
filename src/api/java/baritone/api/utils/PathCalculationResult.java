package baritone.api.utils;

import baritone.api.pathing.calc.IPath;

import java.util.Objects;
import java.util.Optional;

public class PathCalculationResult {

  private final IPath path;
  private final Type type;
  private final SearchStopReason stopReason;

  public PathCalculationResult(Type type) {
    this(type, null, defaultStopReason(type));
  }

  public PathCalculationResult(Type type, IPath path) {
    this(type, path, defaultStopReason(type));
  }

  public PathCalculationResult(Type type, SearchStopReason stopReason) {
    this(type, null, stopReason);
  }

  public PathCalculationResult(Type type, IPath path, SearchStopReason stopReason) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(stopReason);
    this.path = path;
    this.type = type;
    this.stopReason = stopReason;
  }

  public final Optional<IPath> getPath() { return Optional.ofNullable(this.path); }

  public final Type getType() { return this.type; }

  public final SearchStopReason getStopReason() { return stopReason; }

  private static SearchStopReason defaultStopReason(Type type) {
    return switch (type) {
      case SUCCESS_TO_GOAL -> new SearchStopReason.GoalReached(0, 0);
      case SUCCESS_SEGMENT, FAILURE -> new SearchStopReason.NoPath("legacy_result_without_reason");
      case CANCELLATION -> new SearchStopReason.Cancelled();
      case EXCEPTION -> new SearchStopReason.ExceptionThrown("legacy_exception_without_detail");
    };
  }

  public enum Type {
    SUCCESS_TO_GOAL, SUCCESS_SEGMENT, FAILURE, CANCELLATION, EXCEPTION,
  }
}
