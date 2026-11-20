package dev.bali.module.misc;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Recorder;
import dev.bali.module.movement.AutoWalk;
import dev.bali.module.movement.ElytraFly;
import dev.bali.module.render.StashFinder;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.ChatUtil;
import dev.bali.util.GrimBoost;
import dev.bali.util.InvUtil;
import dev.bali.util.StashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Elytra + firework search bot. Flies a coverage pattern at cruise altitude while StashFinder scans
 * every chunk the server sends and alerts/logs anything that looks player-built.
 *
 * Coverage: the server sends chunks within its view distance, so one pass sees a strip about
 * 2 * viewDistance chunks wide. Lanes are spaced exactly that far apart (measured from the chunks
 * actually loaded around you), so every chunk is seen once and nothing is flown twice.
 *
 * Rocket Mode Grim (default) is the fastest and cheapest: during a burn it takes the full velocity
 * Grim accepts (see {@link GrimBoost}) and climbs hard, then glides that height back down to
 * cruise altitude before the next rocket. About 37 b/s at 2-3 rockets a minute with flight 3 rockets.
 *
 * Pause / resume: turning the bot off is a pause. The spot on the search path where it stopped is
 * saved (also every 10 s while flying, so a kick or crash loses nothing). Turn it back on from
 * anywhere in that dimension and it flies straight back to that spot, then carries on, so no
 * chunk is skipped and none is flown twice.
 *
 * Anticheat: everything is vanilla input. The rotation is the real player rotation (turned smoothly),
 * the elytra opens on a fresh jump press like a human pressing space, and rockets are normal item
 * uses. Rockets are only used when speed drops below Rocket Speed, so one rocket lasts several
 * seconds of gliding. Elytras are swapped before they break; rockets are pulled from the inventory.
 *
 * Map: while it flies it records the ground of every chunk it sees, the NewChunks verdicts, the path
 * flown and every stash StashFinder flags ({@link StashMap}). Shown as a HUD minimap and in the
 * ClickGUI (hover a stash for why it was flagged); saved to .minecraft/bali/stashmaps/<server>/<dimension>/
 * as a .png picture, a .txt list of the stashes and the map data, which a resumed search keeps adding to.
 */
public final class StashFinderBot extends Module {
    public enum Pattern { Spiral, Grid, Line }
    public enum Center { Here, Custom }
    public enum Finish { Land, Disconnect }

    private final EnumSetting<Pattern> pattern = mode("Pattern", Pattern.Spiral, "Spiral = outward square spiral, Grid = rectangle, Line = straight");
    private final EnumSetting<Center> center = mode("Center", Center.Here, "Spiral center: where you enable it, or Center X/Z")
        .visibleWhen(() -> pattern != null && pattern.is(Pattern.Spiral));
    private final NumberSetting centerX = number("Center X", 0, -30_000_000, 30_000_000, 1, "Spiral center X")
        .visibleWhen(() -> pattern != null && pattern.is(Pattern.Spiral) && center != null && center.is(Center.Custom));
    private final NumberSetting centerZ = number("Center Z", 0, -30_000_000, 30_000_000, 1, "Spiral center Z")
        .visibleWhen(() -> pattern != null && pattern.is(Pattern.Spiral) && center != null && center.is(Center.Custom));
    private final NumberSetting minRadius = number("Min Radius", 0, 0, 1_000_000, 100, "Spiral: skip everything closer than this to the center")
        .visibleWhen(() -> pattern != null && pattern.is(Pattern.Spiral));
    private final NumberSetting maxRadius = number("Max Radius", 10000, 100, 3_000_000, 100, "Spiral: stop at this distance from the center")
        .visibleWhen(() -> pattern != null && pattern.is(Pattern.Spiral));
    private final NumberSetting x1 = number("X1", -5000, -30_000_000, 30_000_000, 1, "Grid corner 1 X").visibleWhen(() -> pattern != null && pattern.is(Pattern.Grid));
    private final NumberSetting z1 = number("Z1", -5000, -30_000_000, 30_000_000, 1, "Grid corner 1 Z").visibleWhen(() -> pattern != null && pattern.is(Pattern.Grid));
    private final NumberSetting x2 = number("X2", 5000, -30_000_000, 30_000_000, 1, "Grid corner 2 X").visibleWhen(() -> pattern != null && pattern.is(Pattern.Grid));
    private final NumberSetting z2 = number("Z2", 5000, -30_000_000, 30_000_000, 1, "Grid corner 2 Z").visibleWhen(() -> pattern != null && pattern.is(Pattern.Grid));
    private final NumberSetting lineYaw = number("Direction", 0, -180, 180, 1, "Line: yaw to fly (0 = +Z south, -90 = +X east)")
        .visibleWhen(() -> pattern != null && pattern.is(Pattern.Line));
    private final NumberSetting lineDistance = number("Distance", 20000, 100, 3_000_000, 100, "Line: how far to fly")
        .visibleWhen(() -> pattern != null && pattern.is(Pattern.Line));
    private final NumberSetting laneWidth = number("Lane Width", 0, 0, 2000, 16, "Blocks between passes; 0 = auto from the server's view distance");
    private final NumberSetting altitude = number("Altitude", 330, -64, 400, 1, "Cruise Y in the Overworld/End (above 320 = nothing to hit)");
    private final NumberSetting netherAltitude = number("Nether Altitude", 140, 128, 255, 1, "Cruise Y on the Nether roof (start on the roof)");
    public enum RocketMode { Grim, Fast, Balanced, Saver, Custom }

    private final EnumSetting<RocketMode> rocketMode = mode("Rocket Mode", RocketMode.Grim,
        "Grim = fastest and fewest rockets (climbs during the burn, glides it off). Fast/Balanced/Saver = plain vanilla rockets, Custom = sliders");
    private final NumberSetting grimClimb = number("Grim Climb", 100, 0, 200, 10, "Grim: most blocks above cruise altitude a burn may climb (0 = stay level, many more rockets)")
        .visibleWhen(() -> rocketMode != null && rocketMode.is(RocketMode.Grim));
    private final NumberSetting rocketSpeed = number("Rocket Speed", 22, 5, 40, 1, "Custom: use a rocket when slower than this (b/s)")
        .visibleWhen(() -> rocketMode != null && rocketMode.is(RocketMode.Custom));
    private final NumberSetting rocketDelay = number("Rocket Delay", 15, 5, 100, 1, "Custom: min ticks between rockets")
        .visibleWhen(() -> rocketMode != null && rocketMode.is(RocketMode.Custom));
    private final NumberSetting glideBand = number("Glide Band", 0, 0, 150, 5, "Custom: blocks it may glide down below cruise altitude before boosting back up")
        .visibleWhen(() -> rocketMode != null && rocketMode.is(RocketMode.Custom));
    private final NumberSetting glidePitch = number("Glide Pitch", 0, -10, 30, 0.5, "Custom: nose-down angle while gliding through the band")
        .visibleWhen(() -> rocketMode != null && rocketMode.is(RocketMode.Custom));
    private final BoolSetting autoSaver = bool("Auto Saver", true, "Switch to Saver when rockets drop below Low Rockets");
    private final BoolSetting waitForChunks = bool("Wait For Chunks", true, "Don't boost while chunks ahead haven't loaded yet (slow servers / new chunks)");
    private final NumberSetting turnSpeed = number("Turn Speed", 6, 1, 30, 1, "Max degrees per tick");
    private final NumberSetting swapAt = number("Elytra Swap At", 15, 2, 100, 1, "Swap to a fresh elytra at this durability left");
    private final NumberSetting lowRockets = number("Low Rockets", 64, 0, 1000, 8, "Warn when rockets drop below this");
    private final EnumSetting<Finish> finish = mode("On Finish", Finish.Land, "When done or out of rockets/elytras: land, or land then disconnect");
    private final BoolSetting freeLook = bool("Free Look", true, "Look around with the mouse while the bot steers (camera only, the server sees the bot's rotation)");
    private final BoolSetting resume = bool("Resume", true, "Turning the bot off pauses it: turn it on again (even after a relog, from anywhere) and it flies back to where it stopped and continues");
    private final BoolSetting minimap = bool("Minimap", true, "Map of the search on the HUD (bigger one with stash details in the ClickGUI)");
    private final NumberSetting minimapSize = number("Minimap Size", 120, 64, 256, 4, "HUD minimap size").visibleWhen(() -> minimap != null && minimap.on());
    private final BoolSetting mapPredicted = bool("Predicted Path", true, "Map: dotted line of where the bot will fly next");
    private final BoolSetting mapNewChunks = bool("NewChunks Overlay", true, "Map: color chunks new/old like NewChunks does (turns NewChunks on)");
    private final BoolSetting saveMap = bool("Save Map", true, "Save the map as a picture + stash list in .minecraft/bali/stashmaps (on pause, finish and every 10 min)");

    private enum State { TAKEOFF, CRUISE, LANDING }

    private State state = State.TAKEOFF;
    private double originX, originZ;
    private double lane;
    private int index;
    private int rocketCooldown;
    private int viewDistance;
    private boolean jumpDown, holdingJump;
    private boolean warnedRockets, warnedElytra;
    private boolean climbing;
    private int setbackPause;
    private String reason = "";
    private boolean saverForced;
    // pause / resume: last position on the search path, and whether we are flying back to it
    private double resumeX = Double.NaN, resumeZ = Double.NaN;
    private boolean returning;
    private boolean finished;
    // rocket usage for the HUD estimate
    private int lastRocketCount = -1;
    private long statStartTick;
    private int statRockets;
    private double rocketsPerMin;
    // map
    private StashMap map;
    private String progressMap;
    private String lazyKey; // world we last looked for a saved map in (GUI viewing while the bot is off)
    private static final ExecutorService MAP_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bali-StashMap-IO");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public StashFinderBot() {
        super("StashFinderBot", "Elytra + rocket bot that searches the map with StashFinder", Category.MISC);
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (isEnabled() && map != null && mapMatchesWorld(map)) map.sampleChunk(chunk, world.getRegistryKey() == World.NETHER);
        });
    }

    @Override
    public String getInfo() {
        if (state == State.LANDING) return "landing";
        double[] wp = target();
        if (wp == null || mc.player == null) return "#" + index;
        int d = (int) Math.hypot(wp[0] - mc.player.getX(), wp[1] - mc.player.getZ());
        int rockets = InvUtil.count(Items.FIREWORK_ROCKET);
        String left = rocketsPerMin > 0.5 ? " ~" + (int) (rockets / rocketsPerMin) + "min" : "";
        return (returning ? "back to #" : "#") + index + " " + d + "m " + rockets + "r" + left + (effectiveMode() == RocketMode.Saver && saverForced ? " saver" : "");
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onEnable() {
        if (nullCheck()) {
            toggle();
            return;
        }
        Bali.MODULES.get(ElytraFly.class).setEnabled(false);
        Bali.MODULES.get(AutoWalk.class).setEnabled(false);
        StashFinder sf = Bali.MODULES.get(StashFinder.class);
        if (!sf.isEnabled()) sf.setEnabled(true);

        state = mc.player.isGliding() ? State.CRUISE : State.TAKEOFF;
        warnedRockets = warnedElytra = false;
        saverForced = false;
        lastRocketCount = -1;
        statRockets = 0;
        rocketsPerMin = 0;
        statStartTick = mc.player.age;
        rocketCooldown = 0;
        setbackPause = 0;
        viewDistance = measureViewDistance();

        returning = false;
        finished = false;
        progressMap = null;
        if (resume.on() && loadProgress()) {
            openMap(progressMap);
            double away = Double.isNaN(resumeX) ? 0 : Math.hypot(resumeX - mc.player.getX(), resumeZ - mc.player.getZ());
            if (away > RESUME_REACH) {
                returning = true;
                ChatUtil.info(String.format(Locale.ROOT, "StashFinderBot: flying %d blocks back to where it paused (%d, %d), about %s",
                    (int) away, (int) resumeX, (int) resumeZ, eta(away)));
            }
        } else {
            resumeX = resumeZ = Double.NaN;
            index = 0;
            originX = center.is(Center.Custom) ? centerX.getD() : mc.player.getX();
            originZ = center.is(Center.Custom) ? centerZ.getD() : mc.player.getZ();
            if (pattern.is(Pattern.Line)) {
                originX = mc.player.getX();
                originZ = mc.player.getZ();
            }
            lane = laneWidth.getD() > 0 ? laneWidth.getD() : autoLane();
            openMap(null);
            saveProgress();
        }
        if (mapNewChunks.on()) {
            dev.bali.module.render.NewChunks nc = Bali.MODULES.get(dev.bali.module.render.NewChunks.class);
            if (!nc.isEnabled()) nc.setEnabled(true);
        }
        sampleLoadedChunks();
        if (mc.world.getRegistryKey() == World.NETHER && mc.player.getY() < 128)
            ChatUtil.warn("StashFinderBot: you're under the Nether roof. Start on top of the roof (y > 128) or it will fly into the bedrock.");
        ChatUtil.info(String.format(Locale.ROOT, "StashFinderBot: %s, lane %d blocks (view distance %d chunks), %d rockets, waypoint #%d",
            pattern.get(), (int) lane, viewDistance, InvUtil.count(Items.FIREWORK_ROCKET), index));
    }

    @Override
    protected void onDisable() {
        if (map != null && mc.player != null && mc.world != null && mapMatchesWorld(map)) {
            recordMap(true);
            map.breakPath();
            saveMapFiles(true);
        }
        if (!finished && mc.player != null && mc.world != null) {
            markPosition();
            saveProgress();
            if (resume.on() && !Double.isNaN(resumeX))
                ChatUtil.info(String.format(Locale.ROOT, "StashFinderBot: paused at %d, %d. Turn it on again to resume from there.", (int) resumeX, (int) resumeZ));
        }
        releaseJump();
        dev.bali.util.FreeLook.stop();
    }

    @EventHandler
    private void onLeave(Events.Leave e) {
        if (map != null) {
            map.breakPath();
            saveMapFiles(true);
        }
        releaseJump();
        state = State.TAKEOFF; // on rejoin we'll be falling: reopen the elytra
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        // setback: give the server a moment before boosting again
        if (e.packet instanceof PlayerPositionLookS2CPacket) setbackPause = 20;
    }

    // ------------------------------------------------------------------ main loop

    @EventHandler
    private void onTick(Events.TickPre e) {
        // free look follows the setting live; starts where you were looking
        if (freeLook.on() && !dev.bali.util.FreeLook.isActive()) dev.bali.util.FreeLook.start(mc.player.getYaw(), mc.player.getPitch());
        else if (!freeLook.on() && dev.bali.util.FreeLook.isActive()) dev.bali.util.FreeLook.stop();
        recordMap(false);
        if (rocketCooldown > 0) rocketCooldown--;
        if (setbackPause > 0) setbackPause--;
        if (mc.player.hasVehicle() || mc.player.getAbilities().flying) return;

        // ElytraRecast has a chestplate on for a few ticks: the chest slot is its until it reopens.
        // Putting the elytra back early means the server never sees the glide stop (durability is lost).
        boolean recasting = Bali.MODULES.get(dev.bali.module.movement.ElytraRecast.class).busy();
        if (!recasting && !wearingElytra()) {
            if (findSpareElytra() == -1) {
                stop("no usable elytra");
                return;
            }
            if (!equipElytra()) return; // waiting a tick for the click to be allowed
        }
        if (mc.player.isGliding() && !recasting) maintainElytra();

        int rockets = InvUtil.count(Items.FIREWORK_ROCKET);
        trackRockets(rockets);
        if (rockets < lowRockets.getI() && !warnedRockets) {
            warnedRockets = true;
            if (autoSaver.on() && effectiveMode() != RocketMode.Saver && effectiveMode() != RocketMode.Grim) {
                saverForced = true;
                ChatUtil.warn("StashFinderBot: only " + rockets + " rockets left, switching to Saver");
            } else {
                ChatUtil.warn("StashFinderBot: only " + rockets + " rockets left");
            }
        }
        if (rockets == 0 && state != State.LANDING) land("out of rockets");

        if (mc.player.isOnGround() || mc.player.isTouchingWater()) {
            if (state == State.LANDING) {
                finishUp();
                return;
            }
            state = State.TAKEOFF;
            setJump(true); // jump; the elytra opens on the way down
            face(targetYaw(), -10);
            return;
        }

        if (recasting && !mc.player.isGliding()) return; // ElytraRecast reopens it this tick
        if (!mc.player.isGliding()) {
            // airborne, elytra closed: a FRESH jump press opens it (vanilla sends START_FALL_FLYING)
            if (mc.player.getVelocity().y < 0) setJump(!jumpDown);
            else setJump(true);
            return;
        }

        releaseJump();
        if (state == State.TAKEOFF) state = State.CRUISE;
        if (state == State.LANDING) {
            face(mc.player.getYaw(), 18); // shallow glide down, no rockets
            return;
        }
        cruise();
    }

    private void cruise() {
        double[] wp = target();
        if (wp == null) {
            land("search finished");
            return;
        }
        double dx = wp[0] - mc.player.getX(), dz = wp[1] - mc.player.getZ();
        double dist = Math.hypot(dx, dz);
        double speed = mc.player.getVelocity().horizontalLength() * 20;
        if (returning) {
            if (dist < Math.max(24, speed * 1.5)) {
                returning = false;
                ChatUtil.info(Formatting.GREEN + "StashFinderBot: back where it paused, continuing to waypoint #" + index);
                if (Recorder.active()) Recorder.log("STASHBOT", "resumed at " + (int) resumeX + " " + (int) resumeZ);
                return;
            }
        } else if (mc.player.age % 200 == 0) {
            markPosition(); // a kick or crash resumes from within 10 s of here
            saveProgress();
        }
        if (!returning && dist < Math.max(24, speed * 1.5)) {
            index++;
            saveProgress();
            if (Recorder.active()) Recorder.log("STASHBOT", "reached waypoint #" + (index - 1) + " at " + (int) wp[0] + " " + (int) wp[1]);
            if (index % 4 == 0) ChatUtil.info(String.format(Locale.ROOT, "StashFinderBot: waypoint #%d, %d rockets, elytra %d",
                index, InvUtil.count(Items.FIREWORK_ROCKET), elytraLeft()));
            return;
        }

        double alt = mc.world.getRegistryKey() == World.NETHER ? netherAltitude.getD() : altitude.getD();
        double err = alt - mc.player.getY();
        double band = band();
        if (mc.world.getRegistryKey() == World.NETHER) band = Math.min(band, Math.max(0, alt - 135)); // never glide toward the roof
        boolean lowTerrain = clearanceAhead() < 25; // mountains / roof under the glide path: climb now
        if (effectiveMode() == RocketMode.Grim) {
            grimCruise(alt, speed, lowTerrain);
            return;
        }
        if (lowTerrain) climbing = true;
        boolean boosting = !mc.world.getEntitiesByClass(FireworkRocketEntity.class, mc.player.getBoundingBox().expand(3), r -> true).isEmpty();
        if (boosting) climbing = band > 0 && err > 2;   // a boost inside the band climbs all the way back up
        else if (err <= 2) climbing = false;

        float pitch;
        if (band > 0 && !climbing && err > -2 && err < band + 12) {
            // unpowered glide, nose slightly down: trades height for distance instead of rockets
            pitch = (float) glidePitch();
        } else {
            // altitude hold / climb back to cruise: look up when low, down when high
            pitch = (float) MathHelper.clamp(-err * 1.5 - 2, -40, 25);
        }
        face(targetYaw(), pitch);

        if (boosting || rocketCooldown > 0 || setbackPause > 0) return;
        boolean needAlt = err > band + 12 || lowTerrain;  // below the band, or terrain close below
        boolean stalling = speed < 8;       // never let it stall, whatever the mode
        boolean wantClimb = climbing && err > 2;
        if (!needAlt && !stalling && !wantClimb) {
            if (band > 0) return;                        // gliding through the band: no rockets
            if (speed >= fireSpeed()) return;
        }
        if (waitForChunks.on() && !needAlt && !stalling && !chunksAheadLoaded()) return; // let the server catch up first
        if (band > 0) climbing = true;
        fireRocket();
    }

    /**
     * Grim mode. Burn: full accepted speed along our heading while climbing up to Grim Climb above
     * cruise. No rocket: a level glide that sinks the banked height back off; the next rocket goes
     * when we are down at cruise altitude and slow.
     */
    private void grimCruise(double alt, double speed, boolean lowTerrain) {
        double y = mc.player.getY();
        double excess = y - alt;
        if (GrimBoost.rocketAttached() && setbackPause == 0) {
            double vy = MathHelper.clamp(alt + grimClimb.getD() - y, 0, 1.6);
            if (excess < 0) vy = Math.max(vy, Math.min(1.6, -excess)); // below cruise: always climb back
            GrimBoost.request(vy, 3.0);
            face(targetYaw(), GrimBoost.burnPitch(vy));
            return;
        }
        double wantVy = excess > 0.5 && !lowTerrain ? -Math.min(0.5, excess / 40) : 0;
        face(targetYaw(), GrimBoost.glidePitch(mc.player.getVelocity(), mc.player.getYaw(), wantVy));

        if (rocketCooldown > 0 || setbackPause > 0) return;
        boolean needAlt = excess < -3 || lowTerrain;
        boolean stalling = speed < 8;
        if (!needAlt && !stalling && (excess > 0.5 || speed >= fireSpeed())) return;
        if (waitForChunks.on() && !needAlt && !stalling && !chunksAheadLoaded()) return;
        fireRocket();
    }

    /** True while Grim mode flies the burn itself: the vanilla client-side rocket push must not also apply. */
    public boolean ownsRocketBoost() {
        return isEnabled() && state == State.CRUISE && effectiveMode() == RocketMode.Grim && setbackPause == 0
            && mc.player != null && mc.player.isGliding();
    }

    // ------------------------------------------------------------------ waypoints

    /** Waypoint i as {x, z}, or null once the pattern is complete. */
    private double[] waypoint(int i) {
        switch (pattern.get()) {
            case Spiral -> {
                // ring k is a square of half-size h around the center, corners E-N -> E-S -> W-S -> W-N;
                // the W-N corner leads into the next ring's first corner, one lane further out
                int k = i / 4, c = i % 4;
                double h = Math.max(minRadius.getD(), lane / 2) + k * lane;
                if (h > maxRadius.getD()) return null;
                return switch (c) {
                    case 0 -> new double[]{originX + h, originZ - h};
                    case 1 -> new double[]{originX + h, originZ + h};
                    case 2 -> new double[]{originX - h, originZ + h};
                    default -> new double[]{originX - h, originZ - h - lane};
                };
            }
            case Grid -> {
                double minX = Math.min(x1.getD(), x2.getD()), maxX = Math.max(x1.getD(), x2.getD());
                double minZ = Math.min(z1.getD(), z2.getD()), maxZ = Math.max(z1.getD(), z2.getD());
                int row = i / 2;
                double z = minZ + lane / 2 + row * lane;
                if (row > 0 && z - lane / 2 > maxZ) return null;
                boolean eastward = row % 2 == 0, end = i % 2 == 1;
                double x = (eastward ^ end) ? minX : maxX;
                return new double[]{x, Math.min(z, maxZ)};
            }
            default -> {
                if (i > 0) return null;
                double yaw = Math.toRadians(lineYaw.getD());
                return new double[]{originX - Math.sin(yaw) * lineDistance.getD(), originZ + Math.cos(yaw) * lineDistance.getD()};
            }
        }
    }

    /** Where we are flying right now: back to the pause point, or the current waypoint. */
    private double[] target() {
        return returning ? new double[]{resumeX, resumeZ} : waypoint(index);
    }

    /** Remembers our position as the place to resume from; only while actually on the search path. */
    private void markPosition() {
        if (returning || state != State.CRUISE || !mc.player.isGliding()) return;
        resumeX = mc.player.getX();
        resumeZ = mc.player.getZ();
    }

    private static final double RESUME_REACH = 48;

    private String eta(double blocks) {
        int secs = (int) (blocks / (effectiveMode() == RocketMode.Grim ? 38 : 30)); // diagonal legs are the fast ones in Grim mode
        return secs >= 120 ? secs / 60 + " min" : secs + " s";
    }

    /**
     * Heading for this tick. On a search leg we steer for a point further along the leg's line
     * rather than straight at its far end, so after a turn or a resume we get back onto the exact
     * line (flying beside it would leave a strip unsearched).
     */
    private float targetYaw() {
        double[] wp = target();
        if (wp == null) return mc.player.getYaw();
        double px = mc.player.getX(), pz = mc.player.getZ();
        double tx = wp[0], tz = wp[1];
        if (!returning) {
            double[] from = index > 0 ? waypoint(index - 1) : new double[]{originX, originZ};
            double lx = wp[0] - from[0], lz = wp[1] - from[1], len = Math.hypot(lx, lz);
            if (len > 1) {
                double ux = lx / len, uz = lz / len;
                double along = (px - from[0]) * ux + (pz - from[1]) * uz;
                double ahead = Math.max(60, mc.player.getVelocity().horizontalLength() * 60); // 3 s of flight
                double t = MathHelper.clamp(along + ahead, 0, len);
                tx = from[0] + ux * t;
                tz = from[1] + uz * t;
            }
        }
        return (float) Math.toDegrees(Math.atan2(-(tx - px), tz - pz));
    }

    // ------------------------------------------------------------------ flight helpers

    /** Real rotation, turned smoothly (looks like a player steering). */
    private void face(float yaw, float pitch) {
        float t = turnSpeed.getF();
        float cy = mc.player.getYaw();
        float dy = MathHelper.wrapDegrees(yaw - cy);
        mc.player.setYaw(cy + MathHelper.clamp(dy, -t, t));
        float cp = mc.player.getPitch();
        mc.player.setPitch(cp + MathHelper.clamp(pitch - cp, -t, t));
    }

    private void fireRocket() {
        Runnable use = () -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        int hot = InvUtil.findHotbar(Items.FIREWORK_ROCKET);
        if (hot != -1) InvUtil.silentSwap(hot, use);
        else {
            if (!InvUtil.canClickInventory()) return;
            int inv = InvUtil.findInventory(Items.FIREWORK_ROCKET);
            if (inv == -1) return;
            if (!InvUtil.invSwap(inv, use)) return; // keys released for the click; fires next tick
        }
        rocketCooldown = delay();
    }

    // ------------------------------------------------------------------ rocket economy

    private RocketMode effectiveMode() {
        // Grim already uses fewer rockets than Saver, so Auto Saver leaves it alone
        return saverForced && !rocketMode.is(RocketMode.Grim) ? RocketMode.Saver : rocketMode.get();
    }

    /** Boost when slower than this (b/s). */
    private double fireSpeed() {
        return switch (effectiveMode()) {
            case Grim -> 20;
            case Fast -> 30;
            case Balanced -> 22;
            case Saver -> 12;
            case Custom -> rocketSpeed.getD();
        };
    }

    private int delay() {
        return switch (effectiveMode()) {
            case Grim -> 20;
            case Fast -> 8;
            case Balanced -> 15;
            case Saver -> 20;
            case Custom -> rocketDelay.getI();
        };
    }

    /** How far below cruise altitude it may glide before climbing back. */
    private double band() {
        return switch (effectiveMode()) {
            case Grim, Fast, Balanced -> 0;
            case Saver -> 80;
            case Custom -> glideBand.getD();
        };
    }

    /** Measured best: a flat (0 degree) glide keeps ~30 b/s and goes ~300+ blocks per rocket. */
    private double glidePitch() {
        return effectiveMode() == RocketMode.Custom ? glidePitch.getD() : 0;
    }

    /** Blocks between us and the highest ground under the next ~3 seconds of flight path. */
    private double clearanceAhead() {
        var v = mc.player.getVelocity();
        int top = mc.world.getBottomY();
        for (int t = 0; t <= 60; t += 10) {
            int x = MathHelper.floor(mc.player.getX() + v.x * t), z = MathHelper.floor(mc.player.getZ() + v.z * t);
            if (!mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
            top = Math.max(top, mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z));
        }
        return mc.player.getY() - top;
    }

    private void trackRockets(int rockets) {
        if (lastRocketCount >= 0 && rockets < lastRocketCount) statRockets += lastRocketCount - rockets;
        lastRocketCount = rockets;
        long ticks = mc.player.age - statStartTick;
        if (ticks >= 1200) { // refreshed every minute, smoothed
            double now = statRockets * 1200.0 / ticks;
            rocketsPerMin = rocketsPerMin <= 0 ? now : rocketsPerMin * 0.5 + now * 0.5;
            statRockets = 0;
            statStartTick = mc.player.age;
        }
    }

    private boolean chunksAheadLoaded() {
        double yaw = Math.toRadians(mc.player.getYaw());
        double ahead = Math.max(1, viewDistance - 1) * 16;
        int cx = MathHelper.floor(mc.player.getX() - Math.sin(yaw) * ahead) >> 4;
        int cz = MathHelper.floor(mc.player.getZ() + Math.cos(yaw) * ahead) >> 4;
        return mc.world.getChunkManager().isChunkLoaded(cx, cz);
    }

    /** Radius (in chunks) of loaded chunks around the player in all four directions. */
    private int measureViewDistance() {
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        var cm = mc.world.getChunkManager();
        int r = 0;
        for (int d = 1; d <= 32; d++) {
            if (!cm.isChunkLoaded(pcx + d, pcz) || !cm.isChunkLoaded(pcx - d, pcz)
                || !cm.isChunkLoaded(pcx, pcz + d) || !cm.isChunkLoaded(pcx, pcz - d)) break;
            r = d;
        }
        return Math.max(2, r);
    }

    /** One pass sees a strip (2 * viewDistance + 1) chunks wide; leave one chunk of overlap. */
    private double autoLane() {
        return Math.max(32, 2 * viewDistance * 16);
    }

    // ------------------------------------------------------------------ elytra management

    private static boolean usable(ItemStack s, int minLeft) {
        return s.contains(DataComponentTypes.GLIDER) && s.getMaxDamage() - s.getDamage() > minLeft;
    }

    private boolean wearingElytra() {
        return usable(mc.player.getEquippedStack(EquipmentSlot.CHEST), 1);
    }

    private int elytraLeft() {
        ItemStack s = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        return s.getMaxDamage() - s.getDamage();
    }

    /** Best spare elytra in the inventory (most durability), or -1. */
    private int findSpareElytra() {
        int best = -1, bestLeft = swapAt.getI();
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!usable(s, bestLeft)) continue;
            bestLeft = s.getMaxDamage() - s.getDamage();
            best = i;
        }
        return best;
    }

    /** Puts the best spare elytra in the chest slot with SWAP clicks (all in one tick, flight continues). */
    private boolean equipElytra() {
        if (!InvUtil.canClickInventory()) return false;
        int slot = findSpareElytra();
        if (slot == -1) return false;
        if (!dev.bali.util.ClickGate.tryClick()) return false; // keys released (no effect while gliding); swap next tick
        int chest = 6;
        if (slot < 9) {
            InvUtil.swapClick(chest, slot);
        } else {
            int sel = InvUtil.selected();
            int screen = InvUtil.toScreenSlot(slot);
            InvUtil.swapClick(screen, sel);  // spare elytra -> selected hotbar slot
            InvUtil.swapClick(chest, sel);   // -> chest; worn one comes back to the hotbar
            InvUtil.swapClick(screen, sel);  // worn one -> where the spare was, hotbar item restored
        }
        ChatUtil.info("StashFinderBot: equipped a fresh elytra (" + elytraLeft() + " durability)");
        return wearingElytra();
    }

    private void maintainElytra() {
        if (elytraLeft() > swapAt.getI()) return;
        if (findSpareElytra() != -1) {
            equipElytra();
            return;
        }
        if (!warnedElytra) {
            warnedElytra = true;
            ChatUtil.warn("StashFinderBot: elytra low and no spare elytra");
        }
        if (elytraLeft() <= 6 && state != State.LANDING) land("elytra about to break");
    }

    // ------------------------------------------------------------------ ending

    private void land(String why) {
        if (state == State.LANDING) return;
        reason = why;
        state = State.LANDING;
        ChatUtil.warn("StashFinderBot: " + why + ", landing");
    }

    private void finishUp() {
        releaseJump();
        finished = true;
        if (reason.equals("search finished")) clearProgress();
        ChatUtil.info("StashFinderBot: landed (" + reason + ") at " + mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ());
        boolean disconnect = finish.is(Finish.Disconnect);
        setEnabled(false);
        if (disconnect && mc.getNetworkHandler() != null)
            mc.getNetworkHandler().getConnection().disconnect(Text.literal("[StashFinderBot] " + reason));
    }

    private void stop(String why) {
        ChatUtil.error("StashFinderBot stopped: " + why);
        setEnabled(false);
    }

    // ------------------------------------------------------------------ input

    private void setJump(boolean down) {
        mc.options.jumpKey.setPressed(down);
        jumpDown = down;
        holdingJump = true;
    }

    private void releaseJump() {
        if (!holdingJump) return;
        holdingJump = false;
        jumpDown = false;
        if (mc.options != null) mc.options.jumpKey.setPressed(false);
    }

    // ------------------------------------------------------------------ map

    private String serverKey() {
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        return server.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path mapDir(String server, String dimension) {
        return mc.runDirectory.toPath().resolve("bali").resolve("stashmaps").resolve(server).resolve(dimension);
    }

    private boolean mapMatchesWorld(StashMap m) {
        return mc.world != null && m.server.equals(serverKey()) && m.dimension.equals(mc.world.getRegistryKey().getValue().getPath());
    }

    /** Continues the saved map with this name (a resumed search), or starts a new one. */
    private void openMap(String name) {
        String server = serverKey(), dim = mc.world.getRegistryKey().getValue().getPath();
        if (name != null) {
            if (map != null && map.name.equals(name) && mapMatchesWorld(map)) return;
            Path f = mapDir(server, dim).resolve(name + ".bmap");
            if (Files.exists(f)) {
                try {
                    map = StashMap.load(f);
                    lazyKey = null;
                    return;
                } catch (IOException | RuntimeException ex) {
                    ChatUtil.warn("StashFinderBot: couldn't read the saved map (" + ex.getMessage() + "), starting a new one");
                }
            }
        }
        String fresh = name != null ? name : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        map = new StashMap(server, dim, fresh, signature(), originX, originZ, System.currentTimeMillis());
        lazyKey = null;
    }

    private void sampleLoadedChunks() {
        if (map == null) return;
        boolean nether = mc.world.getRegistryKey() == World.NETHER;
        int view = Math.max(viewDistance, mc.options.getClampedViewDistance()) + 1;
        var c = mc.player.getChunkPos();
        for (int x = c.x - view; x <= c.x + view; x++)
            for (int z = c.z - view; z <= c.z + view; z++) {
                var chunk = mc.world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null) map.sampleChunk(chunk, nether);
            }
    }

    /** Path point every tick; NewChunks verdicts twice a second, StashFinder's stashes once a second; autosave. */
    private void recordMap(boolean now) {
        if (map == null || mc.player == null || !mapMatchesWorld(map)) return;
        map.addPathPoint(mc.player.getX(), mc.player.getZ());
        int age = mc.player.age;
        if (mapNewChunks.on() && (now || age % 10 == 0)) {
            dev.bali.module.render.NewChunks nc = Bali.MODULES.get(dev.bali.module.render.NewChunks.class);
            if (nc.isEnabled()) {
                int r = Math.max(viewDistance, 2) + 1;
                var c = mc.player.getChunkPos();
                var cm = mc.world.getChunkManager();
                for (int x = c.x - r; x <= c.x + r; x++)
                    for (int z = c.z - r; z <= c.z + r; z++) {
                        if (!cm.isChunkLoaded(x, z)) continue;
                        byte v = nc.mapVerdict(x, z);
                        if (v != StashMap.NC_NONE) map.setOverlay(x, z, v);
                    }
            }
        }
        if (now || age % 20 == 0) syncStashes();
        if (!now && saveMap.on() && age % 2400 == 0) saveMapFiles(age % 12000 == 0); // data every 2 min, picture every 10
    }

    /** Puts every stash StashFinder knows on the map, if this map has seen its chunk. */
    private void syncStashes() {
        StashFinder sf = Bali.MODULES.get(StashFinder.class);
        if (!sf.isEnabled()) return;
        for (StashFinder.StashInfo s : sf.snapshot()) {
            if (!map.seen(s.pos().getX() >> 4, s.pos().getZ() >> 4)) continue;
            map.putMark(new StashMap.Mark(s.id(), s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.score(), s.details()));
        }
    }

    /** Writes the map data (and the picture + stash list) on a background thread. */
    private void saveMapFiles(boolean picture) {
        StashMap m = map;
        if (m == null || !saveMap.on()) return;
        StashMap.Overlay overlay = mapOverlayOn() ? mapOverlay() : StashMap.Overlay.NONE;
        boolean here = mc.player != null && mapMatchesWorld(m);
        double px = here ? mc.player.getX() : Double.NaN, pz = here ? mc.player.getZ() : Double.NaN;
        Path dir = mapDir(m.server, m.dimension);
        MAP_IO.execute(() -> {
            try {
                m.save(dir.resolve(m.name + ".bmap"));
                if (picture) m.exportPng(dir.resolve(m.name + ".png"), dir.resolve(m.name + ".txt"), overlay, px, pz);
            } catch (Throwable t) {
                Bali.LOG.warn("StashFinderBot: saving the map failed", t);
            }
        });
    }

    /** Folder the maps of this server/dimension are saved in. */
    public Path mapFolder() {
        if (mc.world == null) return mc.runDirectory.toPath().resolve("bali").resolve("stashmaps");
        return mapDir(serverKey(), mc.world.getRegistryKey().getValue().getPath());
    }

    /**
     * The map to show: the one being flown, or (bot off) the newest one saved for this server and
     * dimension, so a finished search can still be looked at in the ClickGUI.
     */
    public StashMap viewMap() {
        if (mc.world == null) return map;
        if (map != null && mapMatchesWorld(map)) return map;
        String key = serverKey() + "/" + mc.world.getRegistryKey().getValue().getPath();
        if (key.equals(lazyKey)) return null;
        lazyKey = key;
        try (var files = Files.list(mapFolder())) {
            Path newest = files.filter(f -> f.getFileName().toString().endsWith(".bmap"))
                .max(java.util.Comparator.comparing(f -> f.getFileName().toString())).orElse(null);
            if (newest != null) map = StashMap.load(newest);
        } catch (IOException | RuntimeException ignored) {}
        return map != null && mapMatchesWorld(map) ? map : null;
    }

    /** {x, z, yaw} of the player if they are in the map's world. */
    public double[] playerOnMap(StashMap m) {
        if (mc.player == null || m == null || !mapMatchesWorld(m)) return null;
        return new double[]{mc.player.getX(), mc.player.getZ(), mc.player.getYaw()};
    }

    /** Where the bot is flying right now, or null. */
    public double[] currentTarget() {
        if (!isEnabled() || mc.player == null || state == State.LANDING) return null;
        return target();
    }

    /** The bot's next waypoints (the pause point first while flying back): about two spiral rings. */
    public List<double[]> predictedPath() {
        List<double[]> out = new ArrayList<>();
        if (!isEnabled() || state == State.LANDING) return out;
        if (returning && !Double.isNaN(resumeX)) out.add(new double[]{resumeX, resumeZ});
        for (int i = index; i < index + 8; i++) {
            double[] wp = waypoint(i);
            if (wp == null) break;
            out.add(wp);
        }
        return out;
    }

    public boolean mapPredictOn() { return mapPredicted.on(); }

    public boolean mapOverlayOn() { return mapNewChunks.on(); }

    public StashMap.Overlay mapOverlay() { return Bali.MODULES.get(dev.bali.module.render.NewChunks.class).mapOverlay(); }

    @EventHandler
    private void onRender2D(Events.Render2D e) {
        if (!minimap.on() || mc.getDebugHud().shouldShowDebugHud() || mc.currentScreen instanceof dev.bali.gui.ClickGuiScreen) return;
        dev.bali.gui.StashMapWidget.INSTANCE.draw(e.context, 4, 14, minimapSize.getI(), -1, -1, false);
    }

    /** .stashmap [save]: save a picture now, or open the folder. */
    public void mapCommand(String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("save")) {
            StashMap m = viewMap();
            if (m == null) {
                ChatUtil.info("No stash map for this server/dimension yet");
                return;
            }
            boolean was = saveMap.on();
            saveMap.set(true);
            saveMapFiles(true);
            saveMap.set(was);
            ChatUtil.info("Saving the stash map to " + mapDir(m.server, m.dimension).resolve(m.name + ".png"));
            return;
        }
        Path dir = mapFolder();
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        net.minecraft.util.Util.getOperatingSystem().open(dir);
        ChatUtil.info("Stash maps: " + dir);
    }

    // ------------------------------------------------------------------ progress file

    private Path progressFile() {
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        String key = server.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + mc.world.getRegistryKey().getValue().getPath();
        return mc.runDirectory.toPath().resolve("bali").resolve("stashbot").resolve(key + ".properties");
    }

    private String signature() {
        return switch (pattern.get()) {
            case Spiral -> "Spiral " + (center.is(Center.Custom) ? (long) centerX.getD() + "," + (long) centerZ.getD() : "here")
                + " " + (long) minRadius.getD() + "-" + (long) maxRadius.getD();
            case Grid -> "Grid " + (long) x1.getD() + "," + (long) z1.getD() + " " + (long) x2.getD() + "," + (long) z2.getD();
            case Line -> "Line " + (long) lineYaw.getD() + " " + (long) lineDistance.getD();
        };
    }

    private boolean loadProgress() {
        Path f = progressFile();
        if (!Files.exists(f)) return false;
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(f, StandardCharsets.UTF_8));
            if (!signature().equals(p.getProperty("pattern"))) return false; // settings changed: new search
            index = Integer.parseInt(p.getProperty("index"));
            originX = Double.parseDouble(p.getProperty("originX"));
            originZ = Double.parseDouble(p.getProperty("originZ"));
            lane = Double.parseDouble(p.getProperty("lane"));
            resumeX = Double.parseDouble(p.getProperty("resumeX", "NaN"));
            resumeZ = Double.parseDouble(p.getProperty("resumeZ", "NaN"));
            progressMap = p.getProperty("map");
            ChatUtil.info(Formatting.GREEN + "StashFinderBot: resuming at waypoint #" + index);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private void saveProgress() {
        if (!resume.on()) return;
        Properties p = new Properties();
        p.setProperty("pattern", signature());
        p.setProperty("index", String.valueOf(index));
        p.setProperty("originX", String.valueOf(originX));
        p.setProperty("originZ", String.valueOf(originZ));
        p.setProperty("lane", String.valueOf(lane));
        if (map != null) p.setProperty("map", map.name);
        if (!Double.isNaN(resumeX)) {
            p.setProperty("resumeX", String.valueOf(resumeX));
            p.setProperty("resumeZ", String.valueOf(resumeZ));
        }
        try {
            Path f = progressFile();
            Files.createDirectories(f.getParent());
            try (var w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                p.store(w, "StashFinderBot progress");
            }
        } catch (IOException ex) {
            Bali.LOG.warn("StashFinderBot save failed", ex);
        }
    }

    private void clearProgress() {
        try { Files.deleteIfExists(progressFile()); } catch (IOException ignored) {}
    }
}
