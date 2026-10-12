package dev.starfold.ship;

/** The seat controls the ship, not the world or inventory. Release it before manipulating items. */
public final class HelmActions {
    public enum Action {
        ATTACK,
        USE,
        PICK,
        DROP,
        SWAP,
        INVENTORY,
        FLIGHT,
        CHAT,
        MENU
    }

    public static boolean allowed(boolean piloting, Action action) {
        return !piloting
                || switch (action) {
                    case FLIGHT, CHAT, MENU -> true;
                    default -> false;
                };
    }

    private HelmActions() {}
}
