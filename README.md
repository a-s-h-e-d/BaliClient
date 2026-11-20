# Bali

A Fabric utility mod for Minecraft **1.21.11**, built for 2b2t and other anarchy servers running the **Grim** anticheat.

Open the menu with **Right Shift**. Commands use the `.` prefix (`.help`).

## Highlights

- **ElytraFly**: Bounce, Boost and Pitch40 modes. Pitch40 climbs and dives between two heights, and it uses rockets only when a climb stalls or when you start a glide too slowly.
- **ElytraMace**: dives at a target on an elytra, swaps the chestplate in at the right moment and lands mace smashes.
- **AutoCrystal**: places and breaks end crystals, using damage calculations and rotations that pass Grim.
- **StashFinder + StashFinderBot**: highlights chunks that may hold a stash or base, and flies an elytra-and-rocket bot that searches the map for them.
- **Recorder**: logs packets, setbacks and module decisions to `bali/logs`, so you can find out why something got flagged.

## Modules

**Combat**: AntiMace, AutoArmor, AutoCrystal, AutoLog, AutoTotem, AutoTrap, Criticals, ElytraMace, HoleFiller, KillAura, MaceDtap, Surround, Velocity

**Movement**: AutoWalk, Blink, ElytraFly, ElytraRecast, NoFall, NoSlow, RocketControl, Speed, Sprint, YawLock

**Player**: AntiHunger, AutoEat, AutoRespawn, AutoTool, FastPlace, MiddleClick, Replenish, XPThrower

**Render**: BlockESP, BreakESP, ESP, FootESP, Freecam, Fullbright, HoleESP, Nametags, NewChunks, NoParticles, NoRender, ShulkerViewer, StashFinder, StorageESP, Tracers, Xray

**Misc**: AutoReconnect, FakePlayer, PopCounter, StashFinderBot

**Client**: ClickGui, HUD, Notifications, Recorder, Rotations

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. Put it in your `mods` folder along with Fabric API.

## License

[MIT](LICENSE)
