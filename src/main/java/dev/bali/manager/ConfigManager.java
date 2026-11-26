package dev.bali.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bali.Bali;
import dev.bali.gui.ClickGuiScreen;
import dev.bali.module.Module;
import dev.bali.setting.Setting;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("bali");
    private final Path main = dir.resolve("config.json");
    private final Path profiles = dir.resolve("configs");

    public void save() { write(main, true); }

    public void load() { read(main, true); }

    public boolean saveProfile(String name) { return write(profiles.resolve(sanitize(name) + ".json"), false); }

    public boolean loadProfile(String name) {
        Path p = profiles.resolve(sanitize(name) + ".json");
        if (!Files.exists(p)) return false;
        return read(p, false);
    }

    public List<String> listProfiles() {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(profiles)) {
            s.filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> out.add(p.getFileName().toString().replace(".json", "")));
        } catch (IOException ignored) {}
        return out;
    }

    private static String sanitize(String n) { return n.replaceAll("[^A-Za-z0-9_\\-]", "_"); }

    private boolean write(Path path, boolean includeGlobal) {
        try {
            JsonObject root = new JsonObject();
            JsonObject mods = new JsonObject();
            for (Module m : Bali.MODULES.all()) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.isEnabled());
                o.addProperty("key", m.getKey());
                o.addProperty("drawn", m.isDrawn());
                JsonObject settings = new JsonObject();
                for (Setting<?> s : m.settings) settings.add(s.name, s.toJson());
                o.add("settings", settings);
                mods.add(m.name, o);
            }
            root.add("modules", mods);

            if (includeGlobal) {
                root.addProperty("prefix", Bali.COMMANDS.prefix());
                JsonArray friends = new JsonArray();
                synchronized (Bali.FRIENDS.all()) {
                    for (String f : Bali.FRIENDS.all()) friends.add(f);
                }
                root.add("friends", friends);
                JsonObject gui = new JsonObject();
                for (Map.Entry<String, int[]> e : ClickGuiScreen.PANEL_STATE.entrySet()) {
                    JsonArray a = new JsonArray();
                    for (int v : e.getValue()) a.add(v);
                    gui.add(e.getKey(), a);
                }
                root.add("gui", gui);
            }

            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            Bali.LOG.error("Failed to save config {}", path, e);
            return false;
        }
    }

    private boolean read(Path path, boolean includeGlobal) {
        if (!Files.exists(path)) return false;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("modules")) {
                JsonObject mods = root.getAsJsonObject("modules");
                for (Module m : Bali.MODULES.all()) {
                    if (!mods.has(m.name)) continue;
                    JsonObject o = mods.getAsJsonObject(m.name);
                    if (o.has("settings")) {
                        JsonObject settings = o.getAsJsonObject("settings");
                        for (Setting<?> s : m.settings) {
                            JsonElement v = settings.get(s.name);
                            if (v != null) {
                                try { s.fromJson(v); } catch (Exception ignored) {}
                            }
                        }
                    }
                    if (o.has("key")) m.setKey(o.get("key").getAsInt());
                    if (o.has("drawn")) m.setDrawn(o.get("drawn").getAsBoolean());
                    if (o.has("enabled")) m.setEnabled(o.get("enabled").getAsBoolean());
                }
            }
            if (includeGlobal) {
                if (root.has("prefix")) Bali.COMMANDS.setPrefix(root.get("prefix").getAsString());
                if (root.has("friends")) {
                    Bali.FRIENDS.clear();
                    for (JsonElement f : root.getAsJsonArray("friends")) Bali.FRIENDS.add(f.getAsString());
                }
                if (root.has("gui")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("gui").entrySet()) {
                        JsonArray a = e.getValue().getAsJsonArray();
                        int[] v = new int[a.size()];
                        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsInt();
                        ClickGuiScreen.PANEL_STATE.put(e.getKey(), v);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Bali.LOG.error("Failed to load config {}", path, e);
            return false;
        }
    }
}
