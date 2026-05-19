package baritone.pathing.direct;

public interface DirectEdge<N> {
  N from();

  N to();

  double cost();
}
