package dev.bali.module.client;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.util.ChatUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Recorder extends Module {
    private final BoolSetting movement = bool("Movement Packets", true, "Log every movement packet (noisy, needed for flight issues)");
    private final BoolSetting other = bool("Other Packets", true, "Log interactions, slots, commands, chat");
    private final BoolSetting modules = bool("Module Logs", true, "AutoCrystal / ElytraFly decision logs");

    private static Recorder instance;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bali-Recorder");
        t.setDaemon(true);
        return t;
    });
    private BufferedWriter out;
    private Path file;
    private long tick;

    public Recorder() {
        super("Recorder", "Logs packets/setbacks/decisions to bali/logs for debugging", Category.CLIENT);
        instance = this;
        setDrawn(true);
    }

    public static void log(String category, String msg) {
        Recorder r = instance;
        if (r == null || !r.isEnabled() || !r.modules.on()) return;
        r.write(category, msg);
    }

    public static boolean active() {
        Recorder r = instance;
        return r != null && r.isEnabled() && r.modules.on();
    }

    @Override
    protected void onEnable() {
        try {
            Path dir = mc.runDirectory.toPath().resolve("bali").resolve("logs");
            Files.createDirectories(dir);
            file = dir.resolve("session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log");
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            tick = 0;
            write("INFO", "Recording started. Bali " + Bali.VERSION + ", server " +
                (mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer/none"));
            write("INFO", "Enabled modules: " + enabledModules());
            ChatUtil.info("Recording to " + file.getFileName());
        } catch (IOException e) {
            ChatUtil.error("Recorder failed: " + e.getMessage());
            toggle();
        }
    }

    @Override
    protected void onDisable() {
        if (out == null) return;
        write("INFO", "Recording stopped.");
        BufferedWriter w = out;
        out = null;
        io.execute(() -> {
            try { w.close(); } catch (IOException ignored) {}
        });
        if (file != null) ChatUtil.info("Saved " + file.getFileName() + " (bali/logs)");
    }

    private void write(String category, String msg) {
        BufferedWriter w = out;
        if (w == null) return;
        String line = "[" + LocalTime.now().format(TIME) + "] t=" + tick + " [" + category + "] " + msg + "\n";
        io.execute(() -> {
            try {
                w.write(line);
                w.flush();
            } catch (IOException ignored) {}
        });
    }

    @EventHandler(priority = -100_000)
    private void onTick(Events.TickPost e) {
        tick++;
        if (tick % 20 == 0 && mc.player != null) {
            write("STATE", state());
        }
        if (mc.player != null) logElytraDurability();
        if (tick % 5 == 0 && mc.player != null && mc.world != null) {
            StringBuilder sb = new StringBuilder();
            for (var pl : mc.world.getPlayers()) {
                if (pl == mc.player || pl.squaredDistanceTo(mc.player) > 16 * 16) continue;
                sb.append(String.format(Locale.ROOT, " %s pos=%s dist=%.1f hp=%.1f hold=%s/%s;", pl.getGameProfile().name(), vec(pl.getEntityPos()),
                    pl.distanceTo(mc.player), pl.getHealth() + pl.getAbsorptionAmount(), itemName(pl.getMainHandStack()), itemName(pl.getOffHandStack())));
            }
            if (!sb.isEmpty()) write("PLAYERS", sb.substring(1));
        }
    }

    private int lastElytraDamage = -1, glideTicks;

    private void logElytraDurability() {
        glideTicks = mc.player.isGliding() ? glideTicks + 1 : 0;
        net.minecraft.item.ItemStack elytra = mc.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
        String where = "chest";
        if (!elytra.isOf(net.minecraft.item.Items.ELYTRA)) {
            elytra = net.minecraft.item.ItemStack.EMPTY;
            for (int i = 0; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).isOf(net.minecraft.item.Items.ELYTRA)) {
                    elytra = mc.player.getInventory().getStack(i);
                    where = "slot " + i;
                    break;
                }
            }
        }
        if (elytra.isEmpty()) return;
        int dmg = elytra.getDamage();
        if (dmg != lastElytraDamage) {
            if (lastElytraDamage == -1) {
                write("ELYTRA", String.format(Locale.ROOT, "durability %d/%d at start", elytra.getMaxDamage() - dmg, elytra.getMaxDamage()));
            } else {
                write("ELYTRA", String.format(Locale.ROOT, "durability %d/%d (%+d) in %s, client gliding %d ticks without a break",
                    elytra.getMaxDamage() - dmg, elytra.getMaxDamage(), lastElytraDamage - dmg, where, glideTicks));
            }
            lastElytraDamage = dmg;
        }
    }

    private static String itemName(net.minecraft.item.ItemStack s) {
        return s.isEmpty() ? "-" : net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
    }

    private String who(net.minecraft.entity.Entity ent) {
        if (ent == null) return "?";
        if (ent == mc.player) return "SELF";
        return ent instanceof net.minecraft.entity.player.PlayerEntity pl ? pl.getGameProfile().name() : ent.getType().getUntranslatedName();
    }

    @EventHandler(priority = -100_000)
    private void onSend(Events.PacketSend e) {
        Packet<?> p = e.packet;
        if (p instanceof CommonPongC2SPacket || p instanceof KeepAliveC2SPacket) return;
        String prefix = e.isCancelled() ? "HELD " : "";
        if (p instanceof PlayerMoveC2SPacket m) {
            if (!movement.on()) return;
            StringBuilder sb = new StringBuilder(prefix).append("MOVE");
            if (m.changesPosition()) sb.append(String.format(Locale.ROOT, " pos=%.3f,%.3f,%.3f", m.getX(0), m.getY(0), m.getZ(0)));
            if (m.changesLook()) sb.append(String.format(Locale.ROOT, " rot=%.2f,%.2f", m.getYaw(0), m.getPitch(0)));
            sb.append(" ground=").append(m.isOnGround()).append(" hcol=").append(m.horizontalCollision());
            write("OUT", sb.toString());
            return;
        }
        if (!other.on()) return;
        String s;
        if (p instanceof PlayerInputC2SPacket in) s = "INPUT " + in.input();
        else if (p instanceof ClientCommandC2SPacket c) s = "COMMAND " + c.getMode();
        else if (p instanceof PlayerInteractBlockC2SPacket b) s = String.format(Locale.ROOT, "USE_BLOCK hand=%s pos=%s side=%s hit=%s seq=%d",
            b.getHand(), b.getBlockHitResult().getBlockPos().toShortString(), b.getBlockHitResult().getSide(), vec(b.getBlockHitResult().getPos()), b.getSequence());
        else if (p instanceof PlayerInteractEntityC2SPacket i) s = "INTERACT_ENTITY id=" + i.entityId + entityName(i.entityId);
        else if (p instanceof PlayerInteractItemC2SPacket it) s = "USE_ITEM hand=" + it.getHand();
        else if (p instanceof HandSwingC2SPacket h) s = "SWING " + h.getHand();
        else if (p instanceof UpdateSelectedSlotC2SPacket u) s = "SLOT " + u.getSelectedSlot();
        else if (p instanceof PlayerActionC2SPacket a) s = "ACTION " + a.getAction() + " " + a.getPos().toShortString() + " " + a.getDirection();
        else if (p instanceof net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket c) s = "CLICK slot=" + c.slot() + " button=" + c.button() + " " + c.actionType() + " rev=" + c.revision();
        else s = p.getClass().getSimpleName();
        write("OUT", prefix + s);
    }

    @EventHandler(priority = 100_000)
    private void onReceive(Events.PacketReceive e) {
        Packet<?> p = e.packet;
        if (p instanceof PlayerPositionLookS2CPacket pl) {
            Vec3d client = mc.player == null ? Vec3d.ZERO : mc.player.getEntityPos();
            Vec3d target = pl.change().position();
            write("SETBACK", String.format(Locale.ROOT, "id=%d to=%s rel=%s client=%s dist=%.3f | %s | modules: %s",
                pl.teleportId(), vec(target), pl.relatives(), vec(client), pl.relatives().isEmpty() ? client.distanceTo(target) : -1,
                state(), enabledModules()));
            return;
        }
        if (!other.on()) return;
        if (p instanceof KeepAliveS2CPacket || p instanceof CommonPingS2CPacket || p instanceof BundleS2CPacket) return;
        if (p instanceof EntitySpawnS2CPacket sp && sp.getEntityType() == EntityType.END_CRYSTAL) {
            write("IN", String.format(Locale.ROOT, "CRYSTAL_SPAWN id=%d pos=%.2f,%.2f,%.2f", sp.getEntityId(), sp.getX(), sp.getY(), sp.getZ()));
        } else if (p instanceof EntitiesDestroyS2CPacket d) {
            if (d.getEntityIds().size() <= 16) write("IN", "DESTROY " + d.getEntityIds());
        } else if (p instanceof ExplosionS2CPacket ex) {
            write("IN", "EXPLOSION " + vec(ex.center()) + " kb=" + ex.playerKnockback().map(Recorder::vec).orElse("none"));
        } else if (p instanceof EntityStatusS2CPacket st && (st.getStatus() == 35 || st.getStatus() == 3)) {
            var ent = mc.world == null ? null : st.getEntity(mc.world);
            write("IN", "STATUS " + st.getStatus() + (st.getStatus() == 35 ? " (totem pop)" : " (death/hit)") + " entity=" + who(ent));
        } else if (p instanceof net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket d && mc.player != null && d.entityId() == mc.player.getId()) {
            var cause = mc.world == null ? null : mc.world.getEntityById(d.sourceCauseId());
            write("IN", "DAMAGED by=" + d.sourceType().getIdAsString().replace("minecraft:", "") + " cause=" + who(cause)
                + d.sourcePosition().map(v -> " at=" + vec(v) + String.format(Locale.ROOT, " dist=%.2f", v.distanceTo(mc.player.getEntityPos()))).orElse("")
                + " offhand=" + itemName(mc.player.getOffHandStack()));
        } else if (p instanceof net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket h && mc.player != null) {
            write("IN", String.format(Locale.ROOT, "HEALTH hp=%.1f abs=%.1f food=%d offhand=%s", h.getHealth(), mc.player.getAbsorptionAmount(),
                h.getFood(), itemName(mc.player.getOffHandStack())));
        } else if (p instanceof net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket su && su.getSyncId() == 0 && su.getSlot() == 45) {
            write("IN", "OFFHAND_SET " + itemName(su.getStack()));
        } else if (p instanceof net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket su && su.getSyncId() == 0 && su.getSlot() == 6) {
            write("IN", "CHEST_SET " + itemName(su.getStack()));
        } else if (p instanceof net.minecraft.network.packet.s2c.play.InventoryS2CPacket inv && inv.syncId() == 0 && inv.contents().size() > 6) {
            write("IN", "INVENTORY_SYNC chest=" + itemName(inv.contents().get(6)));
        } else if (p instanceof net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket tu && mc.player != null && tu.id() == mc.player.getId()) {
            for (var entry : tu.trackedValues()) {
                if (entry.id() == 0 && entry.value() instanceof Byte b) write("IN", "SELF_GLIDE " + ((b & 0x80) != 0 ? "on" : "off"));
            }
        } else if (p instanceof EntityVelocityUpdateS2CPacket v && mc.player != null && v.getEntityId() == mc.player.getId()) {
            write("IN", "SELF_VELOCITY " + vec(v.getVelocity()));
        } else if (p instanceof GameMessageS2CPacket gm) {
            write("CHAT", gm.content().getString());
        } else if (p instanceof DisconnectS2CPacket dc) {
            write("KICK", dc.reason().getString());
        }
    }

    private String state() {
        if (mc.player == null) return "no player";
        var pl = mc.player;
        return String.format(Locale.ROOT, "pos=%s vel=%s yaw=%.1f pitch=%.1f ground=%b glide=%b sprint=%b sneak=%b using=%b hp=%.1f food=%d sat=%.1f ping=%d tps=%.1f",
            vec(pl.getEntityPos()), vec(pl.getVelocity()), pl.getYaw(), pl.getPitch(), pl.isOnGround(), pl.isGliding(), pl.isSprinting(),
            pl.isSneaking(), pl.isUsingItem(), pl.getHealth() + pl.getAbsorptionAmount(), pl.getHungerManager().getFoodLevel(), pl.getHungerManager().getSaturationLevel(), Bali.SERVER.ping(), Bali.SERVER.tps());
    }

    private static String enabledModules() {
        StringBuilder sb = new StringBuilder();
        for (Module m : Bali.MODULES.all()) {
            if (!m.isEnabled() || m instanceof Recorder) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(m.name);
            String info = m.getInfo();
            if (info != null) sb.append('[').append(info).append(']');
        }
        return sb.toString();
    }

    private String entityName(int id) {
        if (mc.world == null) return "";
        var e = mc.world.getEntityById(id);
        return e == null ? " (unknown/not spawned yet)" : " (" + e.getType().getUntranslatedName() + ")";
    }

    private static String vec(Vec3d v) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", v.x, v.y, v.z);
    }

    public static void openFolder() {
        Path dir = mc.runDirectory.toPath().resolve("bali").resolve("logs");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        Util.getOperatingSystem().open(dir);
    }
}
