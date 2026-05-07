package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.pathing.goal.GoalTerminalPolicy;
import baritone.transport.TransportModeSelector;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/**
 * As set by ExampleBaritoneControl or something idk
 *
 * @author leijurv
 */
public final class CustomGoalProcess extends BaritoneProcessHelper implements ICustomGoalProcess {

  /**
   * The most recent goal. Not invalidated upon {@link #onLostControl()}
   */
  private Goal mostRecentGoal;

  private State state = new State.Idle();
  private int transportSuppressionTicks;

  public CustomGoalProcess(Baritone baritone) {
    super(baritone);
  }

  @Override
  public void setGoal(Goal goal) {
    this.mostRecentGoal = goal;
    if (baritone.getElytraProcess().isActive()) {
      baritone.getElytraProcess().pathTo(goal);
    }
    this.state = switch (this.state) {
      case State.Idle() -> new State.GoalSet(goal);
      case State.GoalSet(_) -> new State.GoalSet(goal);
      case State.PathRequested(_) -> new State.PathRequested(goal);
      case State.Executing(_) -> new State.PathRequested(goal);
    };
  }

  @Override
  public void path() {
    this.state = state.goal() == null ? state : new State.PathRequested(state.goal());
  }

  @Override
  public Goal getGoal() { return this.state.goal(); }

  @Override
  public Goal mostRecentGoal() {
    return this.mostRecentGoal;
  }

  @Override
  public boolean isActive() { return !(this.state instanceof State.Idle); }

  @Override
  public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
    if (transportSuppressionTicks > 0) {
      transportSuppressionTicks--;
    }
    return switch (this.state) {
      case State.Idle() -> throw new IllegalStateException("Inactive CustomGoalProcess tick");
      case State.GoalSet(var goal) -> new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
      case State.PathRequested(var goal) -> {
        // return FORCE_REVALIDATE_GOAL_AND_PATH just once
        this.state = new State.Executing(goal);
        if (tryTransportPromotion(goal)) {
          yield new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        yield new PathingCommand(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
      }
      case State.Executing(var goal) -> {
        if (calcFailed) {
          if (tryTransportPromotion(goal)) {
            yield new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
          }
          onLostControl();
          yield new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (GoalTerminalPolicy.satisfied(baritone, ctx, goal, ctx.playerFeet()) && GoalTerminalPolicy.satisfied(baritone, ctx, goal, baritone.getPathingBehavior().pathStart())) {
          onLostControl(); // we're there xd
          if (Baritone.settings().disconnectOnArrival.value) {
            if (ctx.world() instanceof ClientLevel clientLevel) {
              clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
            }
          }
          if (Baritone.settings().notificationOnPathComplete.value) {
            logNotification("Pathing complete", false);
          }
          yield new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (tryTransportPromotion(goal)) {
          yield new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        yield new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
      }
    };
  }

  private boolean tryTransportPromotion(Goal goal) {
    if (transportSuppressionTicks > 0) {
      return false;
    }
    TransportModeSelector.Decision decision = TransportModeSelector.select(baritone, goal);
    if (decision instanceof TransportModeSelector.Decision.Elytra elytra) {
      logDirect(String.format("Switching to elytra for %.0f block route", elytra.horizontalDistance()));
      baritone.getElytraProcess().pathTo(elytra.destination(), elytra.launchMode(), goal);
      this.state = new State.Idle();
      return true;
    }
    return false;
  }

  public void suppressTransportPromotion(int ticks) {
    transportSuppressionTicks = Math.max(transportSuppressionTicks, ticks);
  }

  @Override
  public void onLostControl() {
    this.state = new State.Idle();
  }

  @Override
  public String displayName0() {
    return "Custom Goal " + this.state.goal();
  }

  private sealed interface State {
    default Goal goal() {
      return null;
    }

    record Idle() implements State {
    }

    record GoalSet(Goal goal) implements State {
    }

    record PathRequested(Goal goal) implements State {
    }

    record Executing(Goal goal) implements State {
    }
  }
}
