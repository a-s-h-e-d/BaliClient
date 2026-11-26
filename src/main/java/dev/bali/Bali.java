package dev.bali;

import dev.bali.command.CommandManager;
import dev.bali.event.EventBus;
import dev.bali.event.events.Events;
import dev.bali.manager.ConfigManager;
import dev.bali.manager.FriendManager;
import dev.bali.manager.PopManager;
import dev.bali.manager.RotationManager;
import dev.bali.manager.ServerManager;
import dev.bali.module.ModuleManager;
import dev.bali.util.Render3D;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Bali implements ClientModInitializer {
    public static final String NAME = "Bali";
    public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("bali")
        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static final EventBus EVENTS = new EventBus();
    public static ModuleManager MODULES;
    public static RotationManager ROTATIONS;
    public static FriendManager FRIENDS;
    public static CommandManager COMMANDS;
    public static ConfigManager CONFIG;
    public static ServerManager SERVER;
    public static PopManager POPS;

    @Override
    public void onInitializeClient() {
        Render3D.init();
        ROTATIONS = new RotationManager();
        FRIENDS = new FriendManager();
        SERVER = new ServerManager();
        POPS = new PopManager();
        MODULES = new ModuleManager();
        COMMANDS = new CommandManager();
        CONFIG = new ConfigManager();

        EVENTS.subscribe(ROTATIONS);
        EVENTS.subscribe(dev.bali.util.GrimTick.INSTANCE);
        EVENTS.subscribe(SERVER);
        EVENTS.subscribe(POPS);
        EVENTS.subscribe(MODULES);

        CONFIG.load();

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (mc.player != null && mc.world != null) EVENTS.post(Events.TickPre.INSTANCE);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player != null && mc.world != null) EVENTS.post(Events.TickPost.INSTANCE);
        });

        WorldRenderEvents.END_MAIN.register(ctx -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null) return;
            float delta = mc.getRenderTickCounter().getTickProgress(true);
            Render3D.begin(ctx.matrices(), mc.gameRenderer.getCamera().getCameraPos());
            try {
                EVENTS.post(new Events.Render3D(ctx.matrices(), delta, mc.gameRenderer.getCamera().getCameraPos()));
            } finally {
                Render3D.end();
            }
        });

        HudElementRegistry.addLast(Identifier.of("bali", "hud"), (context, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden) return;
            EVENTS.post(new Events.Render2D(context, tickCounter.getTickProgress(true)));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> EVENTS.post(Events.Join.INSTANCE));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            EVENTS.post(Events.Leave.INSTANCE);
            CONFIG.save();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> CONFIG.save());

        if (Boolean.getBoolean("bali.audit")) {
            boolean[] done = {false};
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (done[0] || mc.getOverlay() != null) return;
                done[0] = true;
                try {
                    org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
                    LOG.info("BALI AUDIT: mixins OK");
                } catch (Throwable t) {
                    LOG.error("BALI AUDIT: mixin audit FAILED", t);
                }
                try {
                    LOG.info("BALI AUDIT: pipelines valid = {}", Render3D.precompile());
                } catch (Throwable t) {
                    LOG.error("BALI AUDIT: pipeline compile FAILED", t);
                }
                mc.scheduleStop();
            });
        }

        LOG.info("{} {} loaded with {} modules", NAME, VERSION, MODULES.all().size());
    }
}
