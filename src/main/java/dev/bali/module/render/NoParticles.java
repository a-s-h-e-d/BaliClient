package dev.bali.module.render;

import dev.bali.Bali;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;

public final class NoParticles extends Module {
    public final BoolSetting all = bool("All", false, "Hide every particle");
    public final BoolSetting explosions = bool("Explosions", true, "Crystal / TNT explosion flash and smoke");
    public final BoolSetting totem = bool("Totem", true, "Totem pop particles");
    public final BoolSetting weather = bool("Weather", true, "Rain and snow (and rain splashes)");
    public final BoolSetting blockBreak = bool("Block Break", false, "Block breaking / landing dust");
    public final BoolSetting crits = bool("Crits", false, "Critical and sharpness hit particles");
    public final BoolSetting potions = bool("Potions", false, "Potion effect swirls");
    public final BoolSetting campfire = bool("Campfire", true, "Campfire smoke columns");

    public NoParticles() {
        super("NoParticles", "Hides particles (crystals, rain, ...)", Category.RENDER);
    }

    private static NoParticles get() { return Bali.MODULES == null ? null : Bali.MODULES.get(NoParticles.class); }

    public static boolean hides(ParticleEffect effect) {
        NoParticles m = get();
        if (m == null || !m.isEnabled()) return false;
        if (m.all.on()) return true;
        ParticleType<?> t = effect.getType();
        if (m.explosions.on() && (t == ParticleTypes.EXPLOSION || t == ParticleTypes.EXPLOSION_EMITTER)) return true;
        if (m.totem.on() && t == ParticleTypes.TOTEM_OF_UNDYING) return true;
        if (m.weather.on() && t == ParticleTypes.RAIN) return true;
        if (m.crits.on() && (t == ParticleTypes.CRIT || t == ParticleTypes.ENCHANTED_HIT)) return true;
        if (m.potions.on() && t == ParticleTypes.ENTITY_EFFECT) return true;
        if (m.campfire.on() && (t == ParticleTypes.CAMPFIRE_COSY_SMOKE || t == ParticleTypes.CAMPFIRE_SIGNAL_SMOKE)) return true;
        return false;
    }

    public static boolean hides(Particle particle) {
        NoParticles m = get();
        if (m == null || !m.isEnabled()) return false;
        return m.all.on() || m.blockBreak.on() && particle instanceof BlockDustParticle;
    }

    public static boolean hidesExplosionSmoke() {
        NoParticles m = get();
        return m != null && m.isEnabled() && (m.all.on() || m.explosions.on());
    }

    public static boolean hidesWeather() {
        NoParticles m = get();
        return m != null && m.isEnabled() && m.weather.on();
    }
}
