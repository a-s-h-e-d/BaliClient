package dev.bali.module.client;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.InvUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class Hud extends Module {
    private final BoolSetting watermark = bool("Watermark", true, "Client name");
    private final BoolSetting arrayList = bool("ArrayList", true, "Enabled modules");
    private final BoolSetting coords = bool("Coords", true, "Coordinates (+ nether coords)");
    private final BoolSetting fps = bool("FPS", true, "Frames per second");
    private final BoolSetting ping = bool("Ping", true, "Latency");
    private final BoolSetting tps = bool("TPS", true, "Server TPS");
    private final BoolSetting speed = bool("Speed", true, "Speed in km/h");
    private final BoolSetting totems = bool("Totems", true, "Totem count");
    private final BoolSetting pops = bool("Pops", true, "Totems you popped this life");
    private final BoolSetting crystals = bool("Crystals/s", true, "Crystals you broke per second (shown while crystalling)");
    private final BoolSetting armor = bool("Armor", true, "Armor above the hotbar");
    private final BoolSetting lag = bool("Lag Notifier", true, "Warn when the server stops responding");
    private final BoolSetting setbacks = bool("Setbacks", false, "Count server setbacks (useful when tuning vs Grim)");
    private final NumberSetting scale = number("Scale", 1.0, 0.5, 2.0, 0.05, "Size of the HUD");

    private double lastX, lastZ, kmh;

    // crystals/s: a crystal counts when the server removes one we hit (so misses/ghost hits don't count)
    private final java.util.Map<Integer, Long> hitCrystals = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> breaks = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final long WINDOW_MS = 2000;

    @EventHandler
    private void onSend(Events.PacketSend e) {
        if (!crystals.on() || !(e.packet instanceof net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket p) || mc.world == null) return;
        if (mc.world.getEntityById(p.entityId) instanceof net.minecraft.entity.decoration.EndCrystalEntity)
            hitCrystals.put(p.entityId, System.currentTimeMillis());
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (!(e.packet instanceof net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket p) || hitCrystals.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (int id : p.getEntityIds()) if (hitCrystals.remove(id) != null) breaks.add(now);
    }

    /** Broken crystals per second over the last 2 seconds; -1 when idle for 10 s. */
    public double crystalsPerSecond() {
        long now = System.currentTimeMillis();
        hitCrystals.values().removeIf(t -> now - t > 3000);
        Long last = breaks.peekLast();
        if (last == null || now - last > 10_000) {
            breaks.clear();
            return -1;
        }
        while (!breaks.isEmpty() && now - breaks.peekFirst() > WINDOW_MS) breaks.pollFirst();
        return breaks.size() * 1000.0 / WINDOW_MS;
    }

    public Hud() {
        super("HUD", "On-screen info", Category.CLIENT);
        setDrawn(false);
        setEnabled(true);
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        double dx = mc.player.getX() - lastX, dz = mc.player.getZ() - lastZ;
        kmh = kmh * 0.7 + Math.sqrt(dx * dx + dz * dz) * 20 * 3.6 * 0.3;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
    }

    @EventHandler
    private void onRender(Events.Render2D e) {
        if (mc.getDebugHud().shouldShowDebugHud()) return;
        DrawContext ctx = e.context;
        float s = scale.getF();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(s, s);
        try {
            draw(ctx, (int) (ctx.getScaledWindowWidth() / s), (int) (ctx.getScaledWindowHeight() / s));
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    private void draw(DrawContext ctx, int sw, int sh) {
        TextRenderer tr = mc.textRenderer;
        int accent = Bali.MODULES.clickGui().accent.argb() | 0xFF000000;

        if (watermark.on()) {
            ctx.drawTextWithShadow(tr, Bali.NAME + Formatting.GRAY + " " + Bali.VERSION, 2, 2, accent);
        }

        if (arrayList.on()) {
            List<String> lines = new ArrayList<>();
            for (Module m : Bali.MODULES.all()) {
                if (!m.isEnabled() || !m.isDrawn()) continue;
                String info = m.getInfo();
                lines.add(info == null ? m.name : m.name + Formatting.GRAY + " [" + Formatting.WHITE + info + Formatting.GRAY + "]");
            }
            lines.sort(Comparator.comparingInt((String s) -> tr.getWidth(s)).reversed());
            int y = 2;
            for (String s : lines) {
                int w = tr.getWidth(s);
                ctx.fill(sw - w - 4, y - 1, sw, y + 9, 0x70000000);
                ctx.fill(sw - 1, y - 1, sw, y + 9, accent);
                ctx.drawTextWithShadow(tr, s, sw - w - 2, y, accent);
                y += 10;
            }
        }

        // bottom-right info stack
        List<String> info = new ArrayList<>();
        if (setbacks.on()) info.add("Setbacks " + Formatting.WHITE + Bali.SERVER.setbacks());
        if (crystals.on()) {
            double cps = crystalsPerSecond();
            if (cps >= 0) info.add("Crystals " + Formatting.WHITE + String.format(Locale.ROOT, "%.1f", cps) + "/s");
        }
        if (totems.on()) info.add("Totems " + Formatting.WHITE + InvUtil.count(Items.TOTEM_OF_UNDYING));
        if (pops.on()) info.add("Popped " + Formatting.WHITE + Bali.POPS.get(mc.player));
        if (speed.on()) info.add("Speed " + Formatting.WHITE + String.format(Locale.ROOT, "%.1f", kmh) + " km/h");
        if (tps.on()) info.add("TPS " + Formatting.WHITE + String.format(Locale.ROOT, "%.1f", Bali.SERVER.tps()));
        if (ping.on()) info.add("Ping " + Formatting.WHITE + Bali.SERVER.ping() + "ms");
        if (fps.on()) info.add("FPS " + Formatting.WHITE + mc.getCurrentFps());
        int y = sh - 10 - (mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen ? 14 : 0);
        for (String s : info) {
            ctx.drawTextWithShadow(tr, s, sw - tr.getWidth(s) - 2, y, accent);
            y -= 10;
        }

        if (coords.on()) {
            boolean nether = mc.world.getRegistryKey() == net.minecraft.world.World.NETHER;
            double f = nether ? 8 : 0.125;
            String c = String.format(Locale.ROOT, "XYZ " + Formatting.WHITE + "%.1f %.1f %.1f" + Formatting.GRAY + " [" + Formatting.WHITE + "%.1f %.1f" + Formatting.GRAY + "]",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getX() * f, mc.player.getZ() * f);
            int cy = sh - 10 - (mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen ? 14 : 0);
            ctx.drawTextWithShadow(tr, c, 2, cy, accent);
        }

        if (armor.on() && !mc.player.isCreative()) {
            int x = sw / 2 + 12;
            int ay = sh - 56 - (mc.player.isSubmergedInWater() ? 10 : 0);
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (EquipmentSlot slot : slots) {
                ItemStack s = mc.player.getEquippedStack(slot);
                if (!s.isEmpty()) {
                    ctx.drawItem(s, x, ay);
                    ctx.drawStackOverlay(tr, s, x, ay);
                    if (s.isDamageable()) {
                        int pct = Math.round(100f * (s.getMaxDamage() - s.getDamage()) / s.getMaxDamage());
                        int col = pct > 50 ? 0xFF55FF55 : pct > 25 ? 0xFFFFFF55 : 0xFFFF5555;
                        String p = pct + "%";
                        ctx.drawTextWithShadow(tr, p, x + 8 - tr.getWidth(p) / 2, ay - 9, col);
                    }
                }
                x += 18;
            }
        }

        if (lag.on() && mc.getNetworkHandler() != null) {
            long since = Bali.SERVER.sinceLastPacket();
            if (since > 1500) {
                String s = "Server not responding " + String.format(Locale.ROOT, "%.1f", since / 1000.0) + "s";
                ctx.drawTextWithShadow(tr, s, sw / 2 - tr.getWidth(s) / 2, 20, 0xFFFF5555);
            }
        }
    }
}
