package dev.bali.module.client;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.InvUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** On-screen toasts for totem pops (yours and others') and deaths. */
public final class Notifications extends Module {
    public enum Position { Center, Top }

    private final BoolSetting selfPops = bool("Self Pops", true, "Show when you pop a totem");
    private final BoolSetting otherPops = bool("Other Pops", false, "Show when other players pop");
    private final BoolSetting deaths = bool("Deaths", false, "Show when a player dies after popping");
    private final BoolSetting sound = bool("Sound", false, "Extra ping when you pop");
    private final NumberSetting duration = number("Duration", 3, 1, 10, 0.5, "Seconds each toast stays up");
    private final EnumSetting<Position> position = mode("Position", Position.Center, "Above the crosshair or at the top");

    private static final int MAX = 5;
    private static final long FADE_MS = 250;

    /** self toasts render the live totem count, since the inventory update lands a tick after the pop packet */
    private record Toast(String text, int pops, boolean self, long start) {}

    private final List<Toast> toasts = new ArrayList<>();

    public Notifications() {
        super("Notifications", "On-screen totem pop alerts", Category.CLIENT);
        setDrawn(false);
        setEnabled(true);
    }

    public int active() { return toasts.size(); }

    @Override
    protected void onDisable() { toasts.clear(); }

    /** Toast from another module (e.g. BreakESP). Does nothing while Notifications is off. */
    public void alert(String text, boolean ping) {
        if (!isEnabled()) return;
        push(new Toast(text, 0, false, System.currentTimeMillis()));
        if (ping) mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 0.8f));
    }

    @EventHandler
    private void onPop(Events.TotemPop e) {
        boolean self = e.player == mc.player;
        if (self ? !selfPops.on() : !otherPops.on()) return;
        String text = self ? null : Formatting.WHITE + e.player.getGameProfile().name() + Formatting.GRAY + " popped "
            + Formatting.RED + e.pops + Formatting.GRAY + (e.pops == 1 ? " totem" : " totems");
        push(new Toast(text, e.pops, self, System.currentTimeMillis()));
        if (self && sound.on()) mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.2f));
    }

    @EventHandler
    private void onDeath(Events.Death e) {
        if (!deaths.on() || e.player == mc.player) return;
        String text = Formatting.WHITE + e.player.getGameProfile().name() + Formatting.GRAY + " died after "
            + Formatting.RED + e.pops + Formatting.GRAY + (e.pops == 1 ? " pop" : " pops");
        push(new Toast(text, e.pops, false, System.currentTimeMillis()));
    }

    @EventHandler
    private void onJoin(Events.Join e) { toasts.clear(); }

    private void push(Toast t) {
        // a newer self pop replaces the older one instead of stacking
        if (t.self) toasts.removeIf(Toast::self);
        toasts.add(0, t);
        while (toasts.size() > MAX) toasts.remove(toasts.size() - 1);
    }

    @EventHandler
    private void onRender(Events.Render2D e) {
        if (toasts.isEmpty() || mc.player == null) return;
        long now = System.currentTimeMillis();
        long life = (long) (duration.getD() * 1000);
        toasts.removeIf(t -> now - t.start > life);

        DrawContext ctx = e.context;
        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int accent = Bali.MODULES.clickGui().accent.argb() & 0xFFFFFF;
        boolean center = position.is(Position.Center);
        int y = center ? sh / 2 - 40 : 32;

        for (Toast t : toasts) {
            long age = now - t.start;
            float a = Math.min(1f, Math.min(age, life - age) / (float) FADE_MS);
            if (a <= 0.03f) continue;
            String text = t.self ? selfText(t.pops) : t.text;
            int w = tr.getWidth(text) + 10;
            int x = sw / 2 - w / 2;
            int alpha = (int) (a * 255) << 24;
            ctx.fill(x, y, x + w, y + 14, ((int) (a * 0xA0) << 24));
            ctx.fill(x, y, x + 2, y + 14, alpha | accent);
            ctx.drawTextWithShadow(tr, text, x + 6, y + 3, alpha | 0xAAAAAA);
            y += center ? -16 : 16;
        }
    }

    private String selfText(int pops) {
        int left = InvUtil.count(Items.TOTEM_OF_UNDYING);
        Formatting leftColor = left <= 1 ? Formatting.RED : left <= 3 ? Formatting.YELLOW : Formatting.GREEN;
        return Formatting.GRAY + "Popped " + Formatting.RED + pops + Formatting.GRAY + (pops == 1 ? " totem" : " totems")
            + Formatting.DARK_GRAY + " | " + leftColor + left + Formatting.GRAY + " left";
    }
}
