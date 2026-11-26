package dev.bali.util;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Placer {
    private record Retry(int fails, long next) {}

    private final Map<BlockPos, Retry> retries = new HashMap<>();
    private long tick;

    public void tick(Collection<BlockPos> wanted) {
        tick++;
        retries.keySet().retainAll(wanted);
    }

    public void clear() { retries.clear(); }

    public void filled(BlockPos pos) { retries.remove(pos); }

    public boolean ready(BlockPos pos) {
        Retry r = retries.get(pos);
        return r == null || tick >= r.next;
    }

    public Runnable clicked(BlockPos pos) {
        Retry r = retries.get(pos);
        int fails = r == null ? 0 : r.fails + 1;
        Retry next = new Retry(fails, tick + Math.min(20, 2L << fails));
        return () -> retries.put(pos, next);
    }

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
