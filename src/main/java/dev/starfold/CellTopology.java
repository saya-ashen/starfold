package dev.starfold;

/** Storage chart routing shared by the accepted strip and the six-face atlas.
 * turns(query) rotates horizontal state/direction from query chart into canonical chart.
 * Read views apply the inverse rotation; writes/notifications apply the forward rotation. */
public interface CellTopology {
    StripTopology.Cell canonical(StripTopology.Cell query);
    StripTopology.Notification notification(StripTopology.Cell target,StripTopology.Cell source);
    StripTopology.Cell real(int u,int y,int z);
    default int turns(StripTopology.Cell query) { return 0; }
}
