package baritone.profile;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.MocapBehavior;
import baritone.pathing.calc.PathingProfiler;
import java.time.Instant;

public final class PathProfileController {
  private final PathingProfiler pathingProfiler;
  private final AsyncProfilerController asyncProfiler;
  private final MocapBehavior mocap;
  private boolean pending;
  private boolean active;
  private Instant startedAt;
  private String lastSummary = "No unified path profile has been recorded yet";

  public PathProfileController(PathingProfiler pathingProfiler, AsyncProfilerController asyncProfiler, MocapBehavior mocap) {
    this.pathingProfiler = pathingProfiler;
    this.asyncProfiler = asyncProfiler;
    this.mocap = mocap;
  }

  public synchronized String toggle() {
    if (active) {
      return finish("manual stop");
    }
    if (pending) {
      pending = false;
      return "Disarmed unified profile for the next path";
    }
    pending = true;
    return "Armed unified profile for the next path. The next path attempt will record async-profiler HTML, mocap JSONL, and per-calculation path JSON.";
  }

  public synchronized String status() {
    if (active) {
      return "Unified profile active since " + startedAt + "\n" + artifacts();
    }
    if (pending) {
      return "Unified profile armed for the next path";
    }
    return lastSummary;
  }

  public synchronized boolean active() {
    return active;
  }

  public synchronized boolean pending() {
    return pending;
  }

  public synchronized void beginIfArmed(BetterBlockPos start, Goal goal) {
    if (!pending || active) {
      return;
    }
    pending = false;
    active = true;
    startedAt = Instant.now();
    pathingProfiler.beginSession();
    String async = asyncProfiler.start(null);
    String motion = mocap.start();
    lastSummary = "Unified profile started at " + startedAt + " from " + start + " to " + goal + "\n" + async + "\n" + motion;
  }

  public synchronized String finish(String reason) {
    if (!active) {
      pending = false;
      return lastSummary;
    }
    active = false;
    pathingProfiler.endSession();
    String motion = mocap.stop();
    String async = asyncProfiler.stop(null);
    lastSummary = "Unified profile finished (" + reason + ")\n" + async + "\n" + motion + "\n" + artifacts();
    startedAt = null;
    return lastSummary;
  }

  private String artifacts() {
    return asyncProfiler.last() + "\n" + mocap.output().map(path -> "Last mocap: " + path.toAbsolutePath()).orElse("No mocap has been saved yet") + "\n"
      + pathingProfiler.lastOutput().map(path -> "Last path microprofile: " + path.toAbsolutePath()).orElse("No path microprofile has been saved yet");
  }
}
