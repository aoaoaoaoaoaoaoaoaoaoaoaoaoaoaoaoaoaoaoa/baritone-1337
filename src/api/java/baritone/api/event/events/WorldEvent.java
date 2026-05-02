package baritone.api.event.events;

import baritone.api.event.events.type.EventState;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * @author Brady
 * @since 8/4/2018
 */
public final class WorldEvent {

  /**
   * The new world that is being loaded. {@code null} if being unloaded.
   */
  private final ClientLevel world;

  /**
   * The state of the event
   */
  private final EventState state;

  public WorldEvent(ClientLevel world, EventState state) {
    this.world = world;
    this.state = state;
  }

  /**
   * @return The new world that is being loaded. {@code null} if being unloaded.
   */
  public final ClientLevel getWorld() { return this.world; }

  /**
   * @return The state of the event
   */
  public final EventState getState() { return this.state; }
}
