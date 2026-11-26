package dev.bali.module.render;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Notifications;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.Render3D;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class BreakESP extends Module {
    private final NumberSetting range = number("Range", 32, 4, 64, 1, "Max distance to show breaks (the server sends up to 32)");
    private final ColorSetting startColor = color("Start", 0x4040FF40, "Color when the break starts");
    private final ColorSetting endColor = color("End", 0x60FF3030, "Color when the block is about to break");
    private final BoolSetting outline = bool("Outline", true, "Outline the growing box");
    private final BoolSetting blockOutline = bool("Block Outline", true, "Faint outline of the whole block");
    private final BoolSetting names = bool("Names", true, "Show who is breaking and how far along");
    private final BoolSetting alert = bool("Alert", true, "Notification when someone breaks a block next to you (needs Notifications)");

    private static final long TIMEOUT_MS = 20_000;

    private static final class Break {
        final int entityId;
        BlockPos pos;
        int stage;
        long stageStart;
        long stageMs = -1;
        long lastUpdate;
        String name;
        boolean alerted;

        Break(int entityId) { this.entityId = entityId; }
    }

    private final Map<Integer, Break> breaks = new HashMap<>();

    public BreakESP() {
        super("BreakESP", "Shows blocks other players are mining", Category.RENDER);
    }

    @Override
    protected void onDisable() { breaks.clear(); }

    @EventHandler
    private void onJoin(Events.Join e) { breaks.clear(); }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (!(e.packet instanceof BlockBreakingProgressS2CPacket p)) return;
        int id = p.getEntityId(), stage = p.getProgress();
        BlockPos pos = p.getPos().toImmutable();
        mc.execute(() -> update(id, pos, stage, System.currentTimeMillis()));
    }

    public void update(int id, BlockPos pos, int stage, long now) {
        if (mc.world == null) return;
        if (stage < 0 || stage > 9) {
            breaks.remove(id);
            return;
        }
        Break b = breaks.get(id);
        if (b == null || !b.pos.equals(pos)) {
            b = new Break(id);
            b.pos = pos;
            b.stage = stage;
            b.stageStart = now;
            breaks.put(id, b);
        } else if (stage > b.stage) {
            b.stageMs = (now - b.stageStart) / (stage - b.stage);
            b.stage = stage;
            b.stageStart = now;
        } else if (stage < b.stage) {
            b.stage = stage;
            b.stageStart = now;
            b.stageMs = -1;
        }
        b.lastUpdate = now;
        Entity ent = mc.world.getEntityById(id);
        b.name = ent instanceof PlayerEntity pl ? pl.getGameProfile().name() : ent != null ? ent.getName().getString() : "?";
        if (!b.alerted && alert.on() && nearMe(pos)) {
            b.alerted = true;
            Bali.MODULES.get(Notifications.class).alert(Formatting.WHITE + b.name + Formatting.RED + " is breaking "
                + Formatting.GRAY + mc.world.getBlockState(pos).getBlock().getName().getString() + " next to you", true);
        }
    }

    private boolean nearMe(BlockPos pos) {
        return mc.player != null && mc.player.getBoundingBox().expand(1.0, 1.0, 1.0).intersects(new Box(pos));
    }

    public int active() { return breaks.size(); }

    private static double progress(Break b, long now) {
        double partial = b.stageMs > 0 ? Math.min(1.0, (now - b.stageStart) / (double) b.stageMs) : 0;
        return MathHelper.clamp((b.stage + partial) / 10.0, 0, 1);
    }

    private void prune(long now) {
        double r = range.getD();
        for (Iterator<Break> it = breaks.values().iterator(); it.hasNext(); ) {
            Break b = it.next();
            if (now - b.lastUpdate > TIMEOUT_MS || mc.world.getBlockState(b.pos).isAir()
                || mc.player.squaredDistanceTo(Vec3d.ofCenter(b.pos)) > r * r) it.remove();
        }
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (breaks.isEmpty()) return;
        long now = System.currentTimeMillis();
        prune(now);
        for (Break b : breaks.values()) {
            double p = progress(b, now);
            int col = lerpColor(startColor.argb(), endColor.argb(), p);
            double half = 0.5 * Math.max(0.06, p);
            Vec3d c = Vec3d.ofCenter(b.pos);
            Box box = new Box(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half);
            Render3D.boxFill(box, col);
            if (outline.on()) Render3D.boxOutline(box, col | 0xFF000000, 1.5f);
            if (blockOutline.on()) Render3D.boxOutline(new Box(b.pos), (col & 0xFFFFFF) | 0x50000000, 1f);
        }
    }

    @EventHandler
    private void onRender2D(Events.Render2D e) {
        if (!names.on() || breaks.isEmpty()) return;
        long now = System.currentTimeMillis();
        TextRenderer tr = mc.textRenderer;
        int gw = e.context.getScaledWindowWidth(), gh = e.context.getScaledWindowHeight();
        for (Break b : breaks.values()) {
            Vec3d s = Render3D.project(Vec3d.ofCenter(b.pos), gw, gh);
            if (s == null) continue;
            String t = b.name + Formatting.GRAY + " " + Math.round(progress(b, now) * 100) + "%";
            int x = (int) s.x - tr.getWidth(t) / 2, y = (int) s.y - 4;
            e.context.fill(x - 2, y - 1, x + tr.getWidth(t) + 2, y + 9, 0x80000000);
            e.context.drawTextWithShadow(tr, t, x, y, 0xFFFFFFFF);
        }
    }

    private static int lerpColor(int a, int b, double t) {
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            r |= ((int) Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return r;
    }

    @Override
    public String getInfo() { return breaks.isEmpty() ? null : String.valueOf(breaks.size()); }
}
