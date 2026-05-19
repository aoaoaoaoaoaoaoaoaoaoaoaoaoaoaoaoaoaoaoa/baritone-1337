package baritone.pathing.direct;

import java.util.Optional;

@FunctionalInterface
public interface DirectEdgeOracle<N, E extends DirectEdge<N>> {
  Optional<E> certify(N from, N to);
}
