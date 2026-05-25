package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.movement.CalculationContext;
import java.util.Objects;
import java.util.Optional;

/**
 * Any pathfinding algorithm that keeps track of nodes recursively by their cost (e.g. A*, dijkstra)
 *
 * @author leijurv
 */
public abstract class AbstractNodeCostSearch implements ActivePathCalculation {
  protected final BetterBlockPos realStart;
  protected final int startX;
  protected final int startY;
  protected final int startZ;

  protected final Goal goal;

  protected final CalculationContext context;
  private final PathingIncumbentPolicy incumbentPolicy;

  protected PathingProfiler.Active profile;

  private volatile boolean isFinished;

  protected boolean cancelRequested;

  private PathPublicationSink publicationSink = PathPublicationSink.IGNORE;

  /**
   * This is really complicated and hard to explain. I wrote a comment in the old version of MineBot but it was so
   * long it was easier as a Google Doc (because I could insert charts).
   *
   * @see <a href="https://docs.google.com/document/d/1WVHHXKXFdCR1Oz__KtK8sFqyvSwJN_H4lftkHFgmzlc/edit">here</a>
   */
  protected static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};

  /**
   * If a path goes less than 5 blocks and doesn't make it to its goal, it's not worth considering.
   */
  protected static final double MIN_DIST_PATH = 5;

  /**
   * there are floating point errors caused by random combinations of traverse and diagonal over a flat area
   * that means that sometimes there's a cost improvement of like 10 ^ -16
   * it's not worth the time to update the costs, decrease-key the heap, potentially repropagate, etc
   * <p>
   * who cares about a hundredth of a tick? that's half a millisecond for crying out loud!
   */
  protected static final double MIN_IMPROVEMENT = 0.01;

  AbstractNodeCostSearch(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, CalculationContext context, PathingIncumbentPolicy incumbentPolicy) {
    this.realStart = realStart;
    this.startX = startX;
    this.startY = startY;
    this.startZ = startZ;
    this.goal = goal;
    this.context = context;
    this.incumbentPolicy = Objects.requireNonNull(incumbentPolicy);
  }

  public void cancel() {
    cancelRequested = true;
  }

  public void setPublicationSink(PathPublicationSink publicationSink) { this.publicationSink = publicationSink == null ? PathPublicationSink.IGNORE : publicationSink; }

  protected boolean hasPublicationSink() {
    return publicationSink != PathPublicationSink.IGNORE;
  }

  protected PathingIncumbentPolicy incumbentPolicy() {
    return incumbentPolicy;
  }

  @Override
  public synchronized PathCalculationResult calculate(long primaryTimeout, long failureTimeout) {
    if (isFinished) {
      throw new IllegalStateException("Path finder cannot be reused!");
    }
    cancelRequested = false;
    PathCalculationResult result = null;
    profile = context.pathingProfiler.begin(realStart, startX, startY, startZ, goal, primaryTimeout, failureTimeout);
    try {
      Optional<IPath> rawPath = calculate0(primaryTimeout, failureTimeout);
      if (cancelRequested) {
        result = new PathCalculationResult(PathCalculationResult.Type.CANCELLATION);
        return result;
      }
      result = materialize(rawPath, true, true);
      return result;
    } catch (Exception e) {
      PathingLog.direct("Pathing exception: " + e);
      e.printStackTrace();
      result = new PathCalculationResult(PathCalculationResult.Type.EXCEPTION);
      return result;
    } finally {
      if (profile != null && result != null) {
        profile.finish(result);
        profile = null;
      }
      // this is run regardless of what exception may or may not be raised by calculate0
      isFinished = true;
    }
  }

  protected abstract Optional<IPath> calculate0(long primaryTimeout, long failureTimeout);

  protected PathCalculationResult materialize(Optional<IPath> rawPath, boolean profilePhases, boolean logPhases) {
    long postProcessNanos = 0;
    long liveChunkCutoffNanos = 0;
    long staticCutoffNanos = 0;
    IPath path = null;
    if (rawPath.isPresent()) {
      long phaseStart = profilePhases && profile != null ? System.nanoTime() : 0;
      path = rawPath.get().postProcess();
      if (profilePhases && profile != null) {
        postProcessNanos = System.nanoTime() - phaseStart;
      }
    }
    if (path == null) {
      return new PathCalculationResult(PathCalculationResult.Type.FAILURE);
    }
    int previousLength = path.length();
    long phaseStart = profilePhases && profile != null ? System.nanoTime() : 0;
    path = path.cutoffAtLiveChunks(context.bsi);
    if (profilePhases && profile != null) {
      liveChunkCutoffNanos = System.nanoTime() - phaseStart;
    }
    if (logPhases) {
      if (path.length() < previousLength) {
        logDebug("Cutting off path at edge of live chunks");
        logDebug("Length decreased by " + (previousLength - path.length()));
      } else {
        logDebug("Path ends within live chunks");
      }
    }
    previousLength = path.length();
    phaseStart = profilePhases && profile != null ? System.nanoTime() : 0;
    path = path.staticCutoff(goal);
    if (profilePhases && profile != null) {
      staticCutoffNanos = System.nanoTime() - phaseStart;
      profile.finishPathPhases(postProcessNanos, liveChunkCutoffNanos, staticCutoffNanos);
    }
    if (logPhases && path.length() < previousLength) {
      logDebug("Static cutoff " + previousLength + " to " + path.length());
    }
    return new PathCalculationResult(goal.isInGoal(path.getDest()) ? PathCalculationResult.Type.SUCCESS_TO_GOAL : PathCalculationResult.Type.SUCCESS_SEGMENT, path);
  }

  protected void publishPath(IPath rawPath) {
    if (!hasPublicationSink() || cancelRequested || rawPath == null) {
      return;
    }
    try {
      PathCalculationResult result = materialize(Optional.of(rawPath), false, false);
      if (result.getPath().isPresent()) {
        publicationSink.publish(result);
      }
    } catch (Exception e) {
      PathingLog.direct("Incumbent path publication failed: " + e);
      e.printStackTrace();
    }
  }

  @Override
  public Optional<IPath> pathToMostRecentNodeConsidered() {
    return Optional.empty();
  }

  @Override
  public Optional<IPath> bestPathSoFar() {
    return Optional.empty();
  }

  @Override
  public final boolean isFinished() { return isFinished; }

  @Override
  public final Goal getGoal() { return goal; }

  public BetterBlockPos getStart() { return new BetterBlockPos(startX, startY, startZ); }

  protected void logDebug(String message) {
    PathingLog.debug(message);
  }

  protected void logNotification(String message, boolean error) {
    PathingLog.notification(message, error);
  }
}
