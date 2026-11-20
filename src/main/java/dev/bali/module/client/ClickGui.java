package dev.bali.module.client;

import dev.bali.gui.ClickGuiScreen;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

public final class ClickGui extends Module {
    public final ColorSetting accent = color("Accent", 0xFF2BB5C9, "Main GUI/HUD color");
    public final BoolSetting toggleMessages = bool("Toggle Messages", true, "Chat message when a module is toggled by key/command");
    public final BoolSetting darken = bool("Darken", true, "Darken the background");
    public final NumberSetting scale = number("Scale", 1.0, 0.5, 2.0, 0.05, "Size of the ClickGUI");

    public ClickGui() {
        super("ClickGUI", "Opens the module menu", Category.CLIENT);
        setKey(GLFW.GLFW_KEY_RIGHT_SHIFT);
        setDrawn(false);
    }

    @Override
    protected void onEnable() {
        if (mc.currentScreen == null) mc.setScreen(new ClickGuiScreen());
        mc.execute(() -> setEnabled(false));
    }
}
