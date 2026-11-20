package dev.bali.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
Command syntax: "add diamond_ore", "del diamond_ore", "clear",
 */
public class BlockListSetting extends Setting<Set<Block>> {
    private int version;

    public BlockListSetting(String name, String description, List<Block> defaults) {
        super(name, description, Collections.unmodifiableSet(new LinkedHashSet<>(defaults)));
        this.value = new LinkedHashSet<>(defaults);
    }

    public int version() { return version; }

    public boolean contains(Block b) { return value.contains(b); }

    public void toggle(Block b) {
        if (!value.remove(b)) value.add(b);
        version++;
    }

    public void add(Block b) { if (value.add(b)) version++; }

    public void remove(Block b) { if (value.remove(b)) version++; }

    @Override
    public void set(Set<Block> v) {
        this.value = new LinkedHashSet<>(v);
        version++;
    }

    @Override
    public void reset() { set(defaultValue); }

    public static Optional<Block> lookup(String id) {
        Identifier ident = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (ident == null) return Optional.empty();
        return Registries.BLOCK.getOptionalValue(ident).filter(b -> b != Blocks.AIR || id.endsWith("air"));
    }

    @Override
    public boolean parse(String text) {
        String t = text.trim();
        String lower = t.toLowerCase();
        if (lower.equals("clear")) {
            set(Set.of());
            return true;
        }
        if (lower.startsWith("add ") || lower.startsWith("del ") || lower.startsWith("remove ")) {
            boolean add = lower.startsWith("add ");
            String id = t.substring(t.indexOf(' ') + 1).trim();
            Optional<Block> b = lookup(id);
            if (b.isEmpty()) return false;
            if (add) add(b.get());
            else remove(b.get());
            return true;
        }
        Set<Block> out = new LinkedHashSet<>();
        for (String part : t.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            Optional<Block> b = lookup(part);
            if (b.isEmpty()) return false;
            out.add(b.get());
        }
        set(out);
        return true;
    }

    @Override
    public String displayValue() {
        StringBuilder sb = new StringBuilder();
        for (Block b : value) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(Registries.BLOCK.getId(b).getPath());
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }

    @Override
    public JsonElement toJson() {
        JsonArray a = new JsonArray();
        for (Block b : value) a.add(Registries.BLOCK.getId(b).toString());
        return a;
    }

    @Override
    public void fromJson(JsonElement json) {
        if (!json.isJsonArray()) return;
        Set<Block> out = new LinkedHashSet<>();
        for (JsonElement e : json.getAsJsonArray()) lookup(e.getAsString()).ifPresent(out::add);
        set(out);
    }
}
