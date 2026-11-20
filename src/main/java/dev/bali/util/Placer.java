package dev.bali.util;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Places a list of blocks over ticks for one module, and backs off from spots that keep failing.
 *
 * A good place shows up client-side at once, so a spot that is still empty after a click failed (or
 * the server undid it). Each failure doubles the wait before that spot is clicked again: 2, 4, 8, 16,
 * then 20 ticks. Without it a placement that can never work sends a click every tick until kicked.
 */
public final class Placer {
    private record Retry(int fails, long next) {}

    private final Map<BlockPos, Retry> retries = new HashMap<>();
    private long tick;

    /** Call once per tick before placing; forgets spots the module no longer wants. */
    public void tick(Collection<BlockPos> wanted) {
        tick++;
        retries.keySet().retainAll(wanted);
    }

    public void clear() { retries.clear(); }

    /** The spot is filled: next time it empties it is clicked straight away. */
    public void filled(BlockPos pos) { retries.remove(pos); }

    /** Not waiting out a backoff. */
    public boolean ready(BlockPos pos) {
        Retry r = retries.get(pos);
        return r == null || tick >= r.next;
    }

    /** Run when the click for {@code pos} has really been sent. */
    public Runnable clicked(BlockPos pos) {
        Retry r = retries.get(pos);
        int fails = r == null ? 0 : r.fails + 1;
        Retry next = new Retry(fails, tick + Math.min(20, 2L << fails));
        return () -> retries.put(pos, next);
    }

    /**
     * Places up to {@code perTick} of {@code positions} in order (one with rotate, since a tick has
     * one rotation). Spots that are already solid are skipped.
     *
     * @return the positions still empty, for rendering
     */
    public List<BlockPos> placeAll(List<BlockPos> positions, int hotbarSlot, boolean rotate, boolean strict,
                                   double range, int perTick, int priority) {
        tick(positions);
        List<BlockPos> empty = new ArrayList<>();
        int placed = 0;
        for (BlockPos pos : positions) {
            if (!BlockUtil.isReplaceable(pos)) {
                filled(pos);
                continue;
            }
            empty.add(pos);
            if (placed >= perTick || rotate && placed >= 1 || !ready(pos)) continue;
            if (BlockUtil.place(pos, hotbarSlot, rotate, strict, range, priority, clicked(pos))) placed++;
        }
        return empty;
    }
}
