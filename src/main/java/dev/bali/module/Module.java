package dev.bali.module;

import dev.bali.Bali;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.setting.Setting;
import dev.bali.util.ChatUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();
    public final String name;
    public final String description;
    public final Category category;
    public final List<Setting<?>> settings = new ArrayList<>();

    private boolean enabled;
    private int key = -1;
    private boolean drawn = true;

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    protected void onEnable() {}

    protected void onDisable() {}

    public String getInfo() { return null; }

    public final boolean isEnabled() { return enabled; }

    public final void toggle() { setEnabled(!enabled); }

    public final void setEnabled(boolean value) {
        if (value == enabled) return;
        enabled = value;
        if (value) {
            Bali.EVENTS.subscribe(this);
            try { onEnable(); } catch (Throwable t) { Bali.LOG.error("onEnable failed for {}", name, t); }
        } else {
            Bali.EVENTS.unsubscribe(this);
            try { onDisable(); } catch (Throwable t) { Bali.LOG.error("onDisable failed for {}", name, t); }
        }
    }

    public final void toggleWithMessage() {
        toggle();
        if (Bali.MODULES != null && this != Bali.MODULES.clickGui() && Bali.MODULES.clickGui().toggleMessages.on()) {
            ChatUtil.info(name + " " + (enabled ? Formatting.GREEN + "enabled" : Formatting.RED + "disabled"));
        }
    }

    public int getKey() { return key; }
    public void setKey(int key) { this.key = key; }
    public boolean isDrawn() { return drawn; }
    public void setDrawn(boolean drawn) { this.drawn = drawn; }

    protected static boolean nullCheck() {
        return mc.player == null || mc.world == null || mc.interactionManager == null;
    }

    protected <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }

    protected BoolSetting bool(String name, boolean def, String desc) {
        return add(new BoolSetting(name, desc, def));
    }

    protected NumberSetting number(String name, double def, double min, double max, double step, String desc) {
        return add(new NumberSetting(name, desc, def, min, max, step));
    }

    protected <E extends Enum<E>> EnumSetting<E> mode(String name, E def, String desc) {
        return add(new EnumSetting<>(name, desc, def));
    }

    protected ColorSetting color(String name, int argb, String desc) {
        return add(new ColorSetting(name, desc, argb));
    }

    public Setting<?> getSetting(String name) {
        for (Setting<?> s : settings) if (s.name.equalsIgnoreCase(name)) return s;
        return null;
    }
}
