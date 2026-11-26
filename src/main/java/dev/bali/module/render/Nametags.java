package dev.bali.module.render;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.Render3D;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Nametags extends Module {
    private final NumberSetting scale = number("Scale", 1.0, 0.5, 3.0, 0.05, "Tag size");
    private final BoolSetting distanceScale = bool("Distance Scale", true, "Shrink far tags (down to half size)");
    private final BoolSetting health = bool("Health", true, "Health + absorption");
    private final BoolSetting ping = bool("Ping", true, "Latency from the tab list");
    private final BoolSetting pops = bool("Pops", true, "Totems popped this life");
    private final BoolSetting distance = bool("Distance", false, "Blocks away");
    private final BoolSetting armor = bool("Armor", true, "Armor and held items");
    private final BoolSetting durability = bool("Durability", true, "Durability % above armor").visibleWhen(() -> armor != null && armor.on());
    private final BoolSetting self = bool("Self", false, "Your own tag in third person / freecam");
    private final NumberSetting background = number("Background", 120, 0, 255, 1, "Background opacity");
    private final ColorSetting friendColor = color("Friend", 0xFF55FFFF, "Friend name color");
    private final NumberSetting range = number("Range", 256, 16, 512, 8, "Max distance");

    public Nametags() {
        super("Nametags", "Better player name tags with health, armor and pops", Category.RENDER);
    }

    public static boolean active() {
        if (Bali.MODULES == null) return false;
        Nametags n = Bali.MODULES.get(Nametags.class);
        return n != null && n.isEnabled();
    }

    private record Tag(PlayerEntity player, Vec3d screen, double dist) {}

    @EventHandler
    private void onRender2D(Events.Render2D e) {
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;
        DrawContext ctx = e.context;
        int gw = ctx.getScaledWindowWidth(), gh = ctx.getScaledWindowHeight();
        Vec3d camPos = mc.gameRenderer.getCamera().getCameraPos();
        boolean showSelf = self.on() && (!mc.options.getPerspective().isFirstPerson() || Freecam.active() != null);
        double max = range.getD();

        List<Tag> tags = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player && !showSelf) continue;
            if (p.isRemoved() || p.getId() == dev.bali.module.movement.Blink.GHOST_ID) continue;
            Vec3d pos = p.getLerpedPos(e.tickDelta);
            double d = pos.distanceTo(camPos);
            if (d > max) continue;
            Vec3d s = Render3D.project(pos.add(0, p.getHeight() + 0.35, 0), gw, gh);
            if (s == null) continue;
            tags.add(new Tag(p, s, d));
        }
        tags.sort((a, b) -> Double.compare(b.dist, a.dist));
        for (Tag t : tags) draw(ctx, t);
    }

    private void draw(DrawContext ctx, Tag t) {
        TextRenderer tr = mc.textRenderer;
        PlayerEntity p = t.player;
        float s = scale.getF();
        if (distanceScale.on()) s *= (float) MathHelper.clamp(12.0 / Math.max(t.dist, 0.1), 0.5, 1.0);

        StringBuilder sb = new StringBuilder();
        if (ping.on()) {
            PlayerListEntry entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(p.getUuid()) : null;
            if (entry != null) sb.append(Formatting.GRAY).append(entry.getLatency()).append("ms ");
        }
        boolean friend = Bali.FRIENDS.isFriend(p);
        sb.append(friend ? "" : p.isSneaking() ? Formatting.GOLD : Formatting.WHITE).append(p.getName().getString());
        if (health.on()) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            Formatting c = hp > 15 ? Formatting.GREEN : hp > 8 ? Formatting.YELLOW : Formatting.RED;
            sb.append(' ').append(c).append(hp % 1 == 0 ? String.valueOf((int) hp) : String.format(Locale.ROOT, "%.1f", hp));
        }
        if (pops.on()) {
            int n = Bali.POPS.get(p);
            if (n > 0) sb.append(' ').append(Formatting.GOLD).append('-').append(n);
        }
        if (distance.on()) sb.append(' ').append(Formatting.GRAY).append((int) t.dist).append('m');
        String text = sb.toString();
        int tw = tr.getWidth(text);
        int nameColor = friend ? (friendColor.argb() | 0xFF000000) : 0xFFFFFFFF;

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) t.screen.x, (float) t.screen.y);
        m.scale(s, s);
        int bg = background.getI() << 24;
        if (bg != 0) ctx.fill(-tw / 2 - 2, -11, tw / 2 + 2, 0, bg);
        ctx.drawTextWithShadow(tr, text, -tw / 2, -10, nameColor);

        if (armor.on()) {
            List<ItemStack> items = new ArrayList<>(6);
            for (ItemStack st : new ItemStack[]{p.getMainHandStack(), p.getEquippedStack(EquipmentSlot.HEAD),
                p.getEquippedStack(EquipmentSlot.CHEST), p.getEquippedStack(EquipmentSlot.LEGS),
                p.getEquippedStack(EquipmentSlot.FEET), p.getOffHandStack()})
                if (!st.isEmpty()) items.add(st);
            if (!items.isEmpty()) {
                int x = -items.size() * 16 / 2, y = -11 - 17;
                for (ItemStack st : items) {
                    ctx.drawItem(st, x, y);
                    ctx.drawStackOverlay(tr, st, x, y);
                    if (durability.on() && st.isDamageable()) {
                        int pct = Math.round(100f * (st.getMaxDamage() - st.getDamage()) / st.getMaxDamage());
                        int col = 0xFF000000 | MathHelper.hsvToRgb(pct / 300f, 1f, 1f);
                        String ds = pct + "%";
                        m.pushMatrix();
                        m.translate(x + 8, y - 5);
                        m.scale(0.5f, 0.5f);
                        ctx.drawTextWithShadow(tr, ds, -tr.getWidth(ds) / 2, 0, col);
                        m.popMatrix();
                    }
                    x += 16;
                }
            }
        }
        m.popMatrix();
    }
}
