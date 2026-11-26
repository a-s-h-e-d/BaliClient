package dev.bali.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    public final String name;
    public final String description;
    protected final T defaultValue;
    protected T value;
    private BooleanSupplier visible = () -> true;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public T get() { return value; }

    public void set(T value) { this.value = value; }

    public void reset() { set(defaultValue); }

    public boolean isVisible() { return visible.getAsBoolean(); }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier visible) {
        this.visible = visible;
        return (S) this;
    }

    public abstract boolean parse(String text);

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);

    public String displayValue() { return String.valueOf(value); }
}
