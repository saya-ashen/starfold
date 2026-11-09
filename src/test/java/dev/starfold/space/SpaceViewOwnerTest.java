package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.WeakHashMap;

class SpaceViewOwnerTest {
    private record EntityId(int id) {}

    @Test
    void equalEntityIdsDoNotAuthorizeNewOwnerAndInvalidationIsIdempotent() {
        var oldPlayer = new EntityId(4);
        var newPlayer = new EntityId(4);
        assertEquals(oldPlayer, newPlayer);
        var owner = new SpaceViewOwner<>(oldPlayer);
        assertTrue(owner.owns(oldPlayer));
        assertFalse(owner.owns(newPlayer));
        owner.reference().clear();
        owner.reference().clear();
        assertFalse(owner.owns(oldPlayer));
        assertFalse(owner.owns(newPlayer));
    }

    @Test
    void delayedOldCleanupCannotOwnReplacementViewWithTheSameEntityId() {
        var oldPlayer = new EntityId(4);
        var newPlayer = new EntityId(4);
        var views = new WeakHashMap<EntityId, SpaceViewOwner<EntityId>>();
        views.put(oldPlayer, new SpaceViewOwner<>(oldPlayer));
        views.get(oldPlayer).reference().clear();
        views.remove(newPlayer);
        views.put(newPlayer, new SpaceViewOwner<>(newPlayer));
        assertSame(newPlayer, views.keySet().iterator().next());
        assertFalse(views.get(oldPlayer).owns(oldPlayer));
        assertTrue(views.get(newPlayer).owns(newPlayer));
        // The exact identity guard used by SpaceServer.removeView leaves this entry intact.
        if (views.get(oldPlayer).owns(oldPlayer)) views.remove(oldPlayer);
        assertEquals(1, views.size());
    }
}
