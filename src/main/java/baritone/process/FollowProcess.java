package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IFollowProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Follow an entity
 *
 * @author leijurv
 */
public final class FollowProcess extends BaritoneProcessHelper implements IFollowProcess {

  private State state = new State.Idle();

  public FollowProcess(Baritone baritone) {
    super(baritone);
  }

  @Override
  public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
    State.Active active = scanWorld();
    Goal goal = new GoalComposite(active.cache().stream().map(entity -> towards(entity, active.mode())).toArray(Goal[]::new));
    return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
  }

  private Goal towards(Entity following, Mode mode) {
    BlockPos pos;
    if (Baritone.settings().followOffsetDistance.value == 0 || mode == Mode.INTO) {
      pos = following.blockPosition();
    } else {
      GoalXZ g = GoalXZ.fromDirection(following.position(), Baritone.settings().followOffsetDirection.value, Baritone.settings().followOffsetDistance.value);
      pos = new BetterBlockPos(g.getX(), following.position().y, g.getZ());
    }
    if (mode == Mode.INTO) {
      return new GoalBlock(pos);
    }
    return new GoalNear(pos, Baritone.settings().followRadius.value);
  }

  private boolean followable(Entity entity) {
    if (entity == null) {
      return false;
    }
    if (!entity.isAlive()) {
      return false;
    }
    if (entity.equals(ctx.player())) {
      return false;
    }
    int maxDist = Baritone.settings().followTargetMaxDistance.value;
    if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > maxDist * maxDist) {
      return false;
    }
    return ctx.entitiesStream().anyMatch(entity::equals);
  }

  private State.Active scanWorld() {
    State.Active active = active();
    List<Entity> cache = ctx.entitiesStream().filter(this::followable).filter(active.filter()).distinct().collect(Collectors.toList());
    active = active.withCache(cache);
    state = active;
    return active;
  }

  @Override
  public boolean isActive() {
    if (!(state instanceof State.Active)) {
      return false;
    }
    return !scanWorld().cache().isEmpty();
  }

  @Override
  public void onLostControl() {
    state = new State.Idle();
  }

  @Override
  public String displayName0() {
    return "Following " + following();
  }

  @Override
  public void follow(Predicate<Entity> filter) {
    this.state = new State.Active(filter, Mode.NEAR, Collections.emptyList());
  }

  @Override
  public void pickup(Predicate<ItemStack> filter) {
    this.state = new State.Active(e -> e instanceof ItemEntity item && filter.test(item.getItem()), Mode.INTO, Collections.emptyList());
  }

  @Override
  public List<Entity> following() {
    return state instanceof State.Active active ? active.cache() : Collections.emptyList();
  }

  @Override
  public Predicate<Entity> currentFilter() {
    return state instanceof State.Active active ? active.filter() : null;
  }

  private State.Active active() {
    if (state instanceof State.Active active) {
      return active;
    }
    throw new IllegalStateException("Inactive FollowProcess tick");
  }

  private enum Mode {
    NEAR, INTO
  }

  private sealed interface State {
    record Idle() implements State {
    }

    record Active(Predicate<Entity> filter, Mode mode, List<Entity> cache) implements State {
      Active withCache(List<Entity> cache) {
        return new Active(filter, mode, cache);
      }
    }
  }
}
