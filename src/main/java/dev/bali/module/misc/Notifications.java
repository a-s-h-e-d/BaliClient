package dev.bali.module.misc;

import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import net.minecraft.entity.mob.CreeperEntity;

public class Notifications extends Module {
    private final BoolSetting creeper = bool("Creeper", true, "notifies if creepers are within a 10 block range");

    public Notifications() {
        super("Notifications", "Miscellaneous notifications for world events", Category.MISC);
    }

    private void onTick() {

    }
}
