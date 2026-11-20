package dev.bali.module.render;

import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;

public final class NoRender extends Module {
    public final BoolSetting hurtCam = bool("Hurt Cam", true, "No camera shake when hit");
    public final BoolSetting fire = bool("Fire", true, "No fire overlay");
    public final BoolSetting totem = bool("Totem", true, "No totem pop animation");
    public final BoolSetting wall = bool("In Wall", true, "No overlay when inside blocks");
    public final BoolSetting water = bool("Water", false, "No underwater overlay");

    public NoRender() {
        super("NoRender", "Hides annoying visuals", Category.RENDER);
    }
}
