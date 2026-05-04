package baritone.planning;

import baritone.api.utils.input.Input;
import java.util.EnumSet;
import java.util.Set;

public record InputMask(Set<Input> down) {
  public static final InputMask NONE = new InputMask(Set.of());

  public InputMask {
    down = down.isEmpty() ? Set.of() : Set.copyOf(down);
  }

  public static InputMask of(Input first, Input... rest) {
    EnumSet<Input> inputs = EnumSet.of(first, rest);
    return new InputMask(inputs);
  }

  public boolean presses(Input input) {
    return down.contains(input);
  }
}
