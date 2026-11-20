package dev.bali.module.client;

import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;

/** Global rotation settings used by every module that rotates. Not a toggle: the settings always apply. */
public final class Rotations extends Module {
    public final BoolSetting moveFix = bool("Move Fix", true, "Move relative to the server rotation so Grim's simulation matches");
    public final BoolSetting gcdFix = bool("GCD Fix", true, "Snap rotations to your mouse sensitivity grid");

    public Rotations() {
        super("Rotations", "Global rotation settings (always active)", Category.CLIENT);
        setDrawn(false);
    }
}
