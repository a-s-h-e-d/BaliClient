package dev.bali.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class ColorSetting extends Setting<Integer> {
    public ColorSetting(String name, String description, int argb) {
        super(name, description, argb);
    }

    public int argb() { return value; }
    public int a() { return (value >>> 24) & 0xFF; }
    public int r() { return (value >> 16) & 0xFF; }
    public int g() { return (value >> 8) & 0xFF; }
    public int b() { return value & 0xFF; }

    public int withAlpha(int alpha) { return (value & 0x00FFFFFF) | ((alpha & 0xFF) << 24); }

    public void setChannel(int channel, int v) {
        v = Math.max(0, Math.min(255, v));
        int shift = switch (channel) { case 0 -> 16; case 1 -> 8; case 2 -> 0; default -> 24; };
        set((value & ~(0xFF << shift)) | (v << shift));
    }

    public int channel(int channel) {
        return switch (channel) { case 0 -> r(); case 1 -> g(); case 2 -> b(); default -> a(); };
    }

    @Override
    public boolean parse(String text) {
        try {
            String t = text.startsWith("#") ? text.substring(1) : text;
            long v = Long.parseLong(t, 16);
            if (t.length() <= 6) v |= 0xFF000000L;
            set((int) v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public String displayValue() { return String.format("#%08X", value); }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(displayValue()); }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive()) parse(json.getAsString());
    }
}
