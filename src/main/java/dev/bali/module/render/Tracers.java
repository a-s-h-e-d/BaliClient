package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.EntityUtil;
import dev.bali.util.Render3D;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

public final class Tracers extends Module {
    public enum Target { Body, Head, Feet }

    private final EnumSetting<Target> target = mode("Target", Target.Body, "Where the line ends");
    private final NumberSetting width = number("Width", 1.5, 0.5, 5, 0.1, "Line width");
    private final BoolSetting players = bool("Players", true, "Players");
    private final BoolSetting hostiles = bool("Hostiles", false, "Hostile mobs");
    private final BoolSetting passives = bool("Passives", false, "Passive mobs");
    private final BoolSetting distanceColor = bool("Distance Color", true, "Red when close, green when far (players)");
    private final ColorSetting playerColor = color("Player Color", 0xFFFF4040, "Players");
    private final ColorSetting friendColor = color("Friend Color", 0xFF40FFFF, "Friends");
    private final ColorSetting mobColor = color("Mob Color", 0xFFFFA020, "Mobs");

    public Tracers() {
        super("Tracers", "Lines to entities", Category.RENDER);
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d start = dev.bali.util.Render3D.crosshair();
        for (Entity ent : mc.world.getEntities()) {
            if (ent == mc.player || !EntityUtil.isAliveTarget(ent)) continue;
            int color;
            if (ent instanceof PlayerEntity p) {
                if (!players.on()) continue;
                if (EntityUtil.isFriend(p)) color = friendColor.argb();
                else if (distanceColor.on()) color = distColor(mc.player.distanceTo(p));
                else color = playerColor.argb();
            } else {
                var kind = EntityUtil.kind(ent);
                if (kind == EntityUtil.Kind.HOSTILE ? !hostiles.on() : !passives.on()) continue;
                color = mobColor.argb();
            }
            Vec3d p = ent.getLerpedPos(e.tickDelta);
            double y = switch (target.get()) {
                case Head -> ent.getStandingEyeHeight();
                case Body -> ent.getHeight() / 2;
                case Feet -> 0;
            };
            Render3D.lineFromCamera(start, p.add(0, y, 0), color, width.getF());
        }
    }

    private static int distColor(float dist) {
        float t = Math.min(dist / 60f, 1f);
        int r = (int) (255 * (1 - t)), g = (int) (255 * t);
        return 0xFF000000 | (r << 16) | (g << 8) | 0x20;
    }
}
