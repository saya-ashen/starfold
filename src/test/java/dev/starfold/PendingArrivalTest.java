package dev.starfold;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PendingArrivalTest {
    @AfterEach
    void clear() {
        PlanetPlayers.clear();
    }

    @Test
    void cancellationStopAndUnloadDiscardPendingArrival() {
        var id = UUID.randomUUID();
        var arrival = new PlanetPlayers.Arrival(null, null, null, null, 0);
        var pending = new PlanetPlayers.Pending(null, arrival);
        PlanetPlayers.joining.put(id, pending);
        PlanetPlayers.cancel(id);
        assertNull(PlanetPlayers.consume(id, null));
        PlanetPlayers.joining.put(id, pending);
        PlanetPlayers.clear();
        assertNull(PlanetPlayers.consume(id, null));
        PlanetPlayers.joining.put(id, pending);
        PlanetPlayers.release(null);
        assertNull(PlanetPlayers.consume(id, null));
    }

    @Test
    void normalArrivalIsConsumedOnce() {
        var id = UUID.randomUUID();
        var arrival = new PlanetPlayers.Arrival(null, null, null, null, 0);
        PlanetPlayers.joining.put(id, new PlanetPlayers.Pending(null, arrival));
        assertSame(arrival, PlanetPlayers.consume(id, null));
        assertNull(PlanetPlayers.consume(id, null));
    }
}
