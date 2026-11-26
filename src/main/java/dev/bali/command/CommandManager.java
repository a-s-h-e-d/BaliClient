package dev.bali.command;

import dev.bali.Bali;
import dev.bali.module.Module;
import dev.bali.module.ModuleManager;
import dev.bali.setting.Setting;
import dev.bali.util.ChatUtil;
import dev.bali.util.KeyNames;
import net.minecraft.util.Formatting;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class CommandManager {
    private String prefix = ".";

    public String prefix() { return prefix; }

    public void setPrefix(String p) { if (p != null && !p.isBlank()) prefix = p; }

    public boolean handle(String message) {
        if (!message.startsWith(prefix)) return false;
        String[] args = message.substring(prefix.length()).trim().split("\\s+");
        if (args.length == 0 || args[0].isEmpty()) return true;
        try {
            run(args[0].toLowerCase(Locale.ROOT), Arrays.copyOfRange(args, 1, args.length));
        } catch (Exception e) {
            ChatUtil.error("Command failed: " + e.getMessage());
            Bali.LOG.error("Command failed", e);
        }
        return true;
    }

    private void run(String cmd, String[] a) {
        switch (cmd) {
            case "help", "h" -> {
                ChatUtil.info("Commands: " + prefix + "toggle <module>, " + prefix + "bind <module> <key|none>, "
                    + prefix + "set <module> <setting> <value>, " + prefix + "settings <module>, " + prefix + "modules, "
                    + prefix + "friend add|del|list <name>, " + prefix + "config save|load|list <name>, "
                    + prefix + "drawn <module>, " + prefix + "prefix <char>, " + prefix + "reset <module>, " + prefix + "logs, " + prefix + "stashes [clear], " + prefix + "stashmap [save]");
            }
            case "toggle", "t" -> {
                Module m = module(a, 0);
                if (m != null) m.toggleWithMessage();
            }
            case "bind", "b" -> {
                Module m = module(a, 0);
                if (m == null) return;
                if (a.length < 2) {
                    ChatUtil.info(m.name + " is bound to " + KeyNames.name(m.getKey()));
                    return;
                }
                int key = KeyNames.parse(a[1]);
                if (key == Integer.MIN_VALUE) {
                    ChatUtil.error("Unknown key '" + a[1] + "'. Use letters, f1-f12, rshift, mouse4, none...");
                    return;
                }
                m.setKey(key);
                ChatUtil.info("Bound " + m.name + " to " + Formatting.AQUA + KeyNames.name(key));
            }
            case "set", "s" -> {
                Module m = module(a, 0);
                if (m == null) return;
                if (a.length < 3) {
                    ChatUtil.error("Usage: " + prefix + "set <module> <setting> <value>");
                    return;
                }
                Setting<?> s = setting(m, a[1]);
                if (s == null) return;
                String value = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                if (s.parse(value)) ChatUtil.info(m.name + " " + s.name + " = " + Formatting.AQUA + s.displayValue());
                else ChatUtil.error("Invalid value '" + value + "' for " + s.name);
            }
            case "settings" -> {
                Module m = module(a, 0);
                if (m == null) return;
                ChatUtil.info(m.name + ": " + m.settings.stream()
                    .map(s -> s.name.replace(" ", "") + "=" + s.displayValue())
                    .collect(Collectors.joining(", ")));
            }
            case "reset" -> {
                Module m = module(a, 0);
                if (m == null) return;
                m.settings.forEach(Setting::reset);
                ChatUtil.info("Reset " + m.name);
            }
            case "modules", "list" -> ChatUtil.info(Bali.MODULES.all().stream()
                .map(m -> (m.isEnabled() ? Formatting.GREEN : Formatting.GRAY) + m.name)
                .collect(Collectors.joining(Formatting.GRAY + ", ")));
            case "drawn" -> {
                Module m = module(a, 0);
                if (m == null) return;
                m.setDrawn(!m.isDrawn());
                ChatUtil.info(m.name + (m.isDrawn() ? " shown in" : " hidden from") + " array list");
            }
            case "friend", "f" -> friend(a);
            case "config", "cfg" -> config(a);
            case "logs" -> dev.bali.module.client.Recorder.openFolder();
            case "stashes", "stash" -> Bali.MODULES.get(dev.bali.module.render.StashFinder.class).command(a);
            case "stashmap", "map" -> Bali.MODULES.get(dev.bali.module.misc.StashFinderBot.class).mapCommand(a);
            case "prefix" -> {
                if (a.length < 1) return;
                setPrefix(a[0]);
                ChatUtil.info("Prefix set to " + prefix);
            }
            default -> ChatUtil.error("Unknown command. Try " + prefix + "help");
        }
    }

    private void friend(String[] a) {
        if (a.length < 1 || a[0].equalsIgnoreCase("list")) {
            ChatUtil.info("Friends: " + String.join(", ", Bali.FRIENDS.all()));
            return;
        }
        if (a.length < 2) {
            ChatUtil.error("Usage: " + prefix + "friend add|del <name>");
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "add" -> ChatUtil.info(Bali.FRIENDS.add(a[1]) ? "Added " + a[1] : a[1] + " is already a friend");
            case "del", "remove", "rem" -> ChatUtil.info(Bali.FRIENDS.remove(a[1]) ? "Removed " + a[1] : a[1] + " isn't a friend");
            default -> ChatUtil.error("Usage: " + prefix + "friend add|del <name>");
        }
    }

    private void config(String[] a) {
        if (a.length < 1) {
            ChatUtil.error("Usage: " + prefix + "config save|load|list [name]");
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "list" -> ChatUtil.info("Configs: " + String.join(", ", Bali.CONFIG.listProfiles()));
            case "save" -> {
                if (a.length < 2) { Bali.CONFIG.save(); ChatUtil.info("Saved."); return; }
                ChatUtil.info(Bali.CONFIG.saveProfile(a[1]) ? "Saved config " + a[1] : "Failed to save " + a[1]);
            }
            case "load" -> {
                if (a.length < 2) { Bali.CONFIG.load(); ChatUtil.info("Reloaded."); return; }
                ChatUtil.info(Bali.CONFIG.loadProfile(a[1]) ? "Loaded config " + a[1] : "No config named " + a[1]);
            }
            default -> ChatUtil.error("Usage: " + prefix + "config save|load|list [name]");
        }
    }

    private Module module(String[] a, int i) {
        if (a.length <= i) {
            ChatUtil.error("Specify a module.");
            return null;
        }
        Module m = Bali.MODULES.get(a[i]);
        if (m == null) ChatUtil.error("No module named '" + a[i] + "'");
        return m;
    }

    private Setting<?> setting(Module m, String name) {
        for (Setting<?> s : m.settings) {
            if (s.name.equalsIgnoreCase(name) || s.name.replace(" ", "").equalsIgnoreCase(name)) return s;
        }
        List<String> names = m.settings.stream().map(s -> s.name.replace(" ", "")).toList();
        ChatUtil.error("No setting '" + name + "'. Options: " + String.join(", ", names));
        return null;
    }

    @SuppressWarnings("unused")
    private static final int MOUSE = ModuleManager.MOUSE_OFFSET;
}
