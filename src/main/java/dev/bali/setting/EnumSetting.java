package dev.bali.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class EnumSetting<E extends Enum<E>> extends Setting<E> {
    private final E[] values;

    public EnumSetting(String name, String description, E defaultValue) {
        super(name, description, defaultValue);
        this.values = defaultValue.getDeclaringClass().getEnumConstants();
    }

    public boolean is(E e) { return value == e; }

    public void cycle() { set(values[(value.ordinal() + 1) % values.length]); }

    public void cycleBack() { set(values[(value.ordinal() - 1 + values.length) % values.length]); }

    public E[] values() { return values; }

    @Override
    public boolean parse(String text) {
        for (E e : values) {
            if (e.name().equalsIgnoreCase(text)) {
                set(e);
                return true;
            }
        }
        return false;
    }

    @Override
    public JsonElement toJson() { return new JsonPrimitive(value.name()); }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive()) parse(json.getAsString());
    }
}
