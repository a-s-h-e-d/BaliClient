package dev.bali.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class NumberSetting extends Setting<Double> {
    public final double min;
    public final double max;
    public final double step;

    public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
        super(name, description, defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    @Override
    public void set(Double v) {
        double clamped = Math.max(min, Math.min(max, v));
        if (step > 0) clamped = Math.round(clamped / step) * step;
        this.value = Math.round(clamped * 10000.0) / 10000.0;
    }

    public double getD() { return value; }
    public float getF() { return value.floatValue(); }
    public int getI() { return (int) Math.round(value); }

    @Override
    public boolean parse(String text) {
        try {
            set(Double.parseDouble(text));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public String displayValue() {
        if (step >= 1 && value == Math.floor(value)) return String.valueOf(value.intValue());
        String s = String.format(java.util.Locale.ROOT, "%.2f", value);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value); }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive()) set(json.getAsDouble());
    }
}
