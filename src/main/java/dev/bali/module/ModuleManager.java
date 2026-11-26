package dev.bali.module;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.client.ClickGui;
import dev.bali.module.client.Hud;
import dev.bali.module.client.Notifications;
import dev.bali.module.client.Recorder;
import dev.bali.module.client.Rotations;
import dev.bali.module.combat.AutoArmor;
import dev.bali.module.combat.AutoCrystal;
import dev.bali.module.combat.AutoLog;
import dev.bali.module.combat.AutoTotem;
import dev.bali.module.combat.Criticals;
import dev.bali.module.combat.KillAura;
import dev.bali.module.combat.Surround;
import dev.bali.module.combat.Velocity;
import dev.bali.module.misc.AutoReconnect;
import dev.bali.module.misc.FakePlayer;
import dev.bali.module.misc.PopCounter;
import dev.bali.module.movement.AutoWalk;
import dev.bali.module.movement.Blink;
import dev.bali.module.render.BlockESP;
import dev.bali.module.render.Xray;
import dev.bali.module.render.StashFinder;
import dev.bali.module.render.ShulkerViewer;
import dev.bali.module.movement.ElytraFly;
import dev.bali.module.movement.NoFall;
import dev.bali.module.movement.NoSlow;
import dev.bali.module.movement.Speed;
import dev.bali.module.movement.Sprint;
import dev.bali.module.player.AntiHunger;
import dev.bali.module.player.AutoEat;
import dev.bali.module.player.AutoRespawn;
import dev.bali.module.player.AutoTool;
import dev.bali.module.player.FastPlace;
import dev.bali.module.player.MiddleClick;
import dev.bali.module.player.Replenish;
import dev.bali.module.render.ESP;
import dev.bali.module.render.Fullbright;
import dev.bali.module.render.HoleESP;
import dev.bali.module.render.Freecam;
import dev.bali.module.render.NewChunks;
import dev.bali.module.render.NoParticles;
import dev.bali.module.render.NoRender;
import dev.bali.module.render.StorageESP;
import dev.bali.module.render.Tracers;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ModuleManager {
    public static final int MOUSE_OFFSET = 1000;

    private final List<Module> modules = new ArrayList<>();
    private final Map<Class<? extends Module>, Module> byClass = new HashMap<>();
    private final ClickGui clickGui;

    public ModuleManager() {
        register(new KillAura());
        register(new AutoCrystal());
        register(new AutoTotem());
        register(new AutoArmor());
        register(new Criticals());
        register(new Velocity());
        register(new Surround());
        register(new dev.bali.module.combat.HoleFiller());
        register(new dev.bali.module.combat.AutoTrap());
        register(new AutoLog());
        register(new dev.bali.module.combat.ElytraMace());
        register(new dev.bali.module.combat.MaceDtap());
        register(new dev.bali.module.combat.AntiMace());
        register(new Sprint());
        register(new Speed());
        register(new NoSlow());
        register(new NoFall());
        register(new ElytraFly());
        register(new AutoWalk());
        register(new Blink());
        register(new dev.bali.module.movement.RocketControl());
        register(new dev.bali.module.movement.ElytraRecast());
        register(new dev.bali.module.movement.YawLock());
        register(new dev.bali.module.movement.Scaffold());
        register(new ESP());
        register(new Tracers());
        register(new dev.bali.module.render.Nametags());
        register(new StorageESP());
        register(new BlockESP());
        register(new Xray());
        register(new NewChunks());
        register(new StashFinder());
        register(new ShulkerViewer());
        register(new Freecam());
        register(new HoleESP());
        register(new dev.bali.module.render.FootESP());
        register(new dev.bali.module.render.BreakESP());
        register(new Fullbright());
        register(new NoRender());
        register(new NoParticles());
        register(new AutoEat());
        register(new AntiHunger());
        register(new AutoTool());
        register(new FastPlace());
        register(new AutoRespawn());
        register(new MiddleClick());
        register(new Replenish());
        register(new dev.bali.module.player.XPThrower());
        register(new FakePlayer());
        register(new PopCounter());
        register(new AutoReconnect());
        register(new dev.bali.module.misc.StashFinderBot());
        register(clickGui = new ClickGui());
        register(new Hud());
        register(new Notifications());
        register(new Rotations());
        register(new Recorder());

        modules.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
    }

    private void register(Module m) {
        modules.add(m);
        byClass.put(m.getClass(), m);
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> clazz) { return (T) byClass.get(clazz); }

    public Module get(String name) {
        for (Module m : modules) if (m.name.equalsIgnoreCase(name)) return m;
        return null;
    }

    public List<Module> all() { return Collections.unmodifiableList(modules); }

    public List<Module> inCategory(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) if (m.category == c) out.add(m);
        return out;
    }

    public ClickGui clickGui() { return clickGui; }

    @EventHandler
    private void onKey(Events.Key e) {
        if (e.action != 1) return;
        if (MinecraftClient.getInstance().currentScreen != null) return;
        int code = e.mouse ? MOUSE_OFFSET + e.key : e.key;
        for (Module m : modules) {
            if (m.getKey() == code && code != -1) m.toggleWithMessage();
        }
        if (MinecraftClient.getInstance().currentScreen != null) e.cancel();
    }
}
