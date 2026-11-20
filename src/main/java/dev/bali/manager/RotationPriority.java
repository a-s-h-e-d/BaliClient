package dev.bali.manager;

/**
 * Who gets the tick's one silent rotation when several modules want it. Highest wins.
 *
 * Staying alive comes first, then keeping your own hole sealed, then locking the enemy down, and
 * only then attacking: a missed crystal costs a tick, a gap in your surround costs a totem.
 */
public final class RotationPriority {
    private RotationPriority() {}

    // survival
    public static final int NO_FALL = 100;
    /** Shielding toward, or rocketing away from, an incoming mace dive. */
    public static final int ANTI_MACE = 98;

    // defence
    /** Refilling your own surround, including breaking a crystal that sits in a gap. */
    public static final int SURROUND = 95;

    // control
    public static final int AUTO_TRAP = 86;
    public static final int HOLE_FILLER = 85;

    // offence
    public static final int AUTO_CRYSTAL = 80;
    public static final int XP_THROWER = 60;
    public static final int ELYTRA_MACE = 60;
    public static final int MACE_DTAP = 55;
    public static final int KILL_AURA = 50;

    // movement
    public static final int ROCKET_CONTROL = 40;
    public static final int ELYTRA_FLY = 5;
}
