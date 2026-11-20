package dev.bali.util;

import dev.bali.module.ModuleManager;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class KeyNames {
    private KeyNames() {}

    public static String name(int key) {
        if (key < 0) return "None";
        if (key >= ModuleManager.MOUSE_OFFSET) return "Mouse" + (key - ModuleManager.MOUSE_OFFSET + 1);
        return switch (key) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RAlt";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LAlt";
            case GLFW.GLFW_KEY_INSERT -> "Insert";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_PAGE_UP -> "PgUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PgDn";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "Grave";
            default -> {
                String n = InputUtil.fromKeyCode(new net.minecraft.client.input.KeyInput(key, 0, 0)).getLocalizedText().getString();
                yield n.length() == 1 ? n.toUpperCase(Locale.ROOT) : n;
            }
        };
    }

    /** @return GLFW key, MOUSE_OFFSET+button, -1 for none, or Integer.MIN_VALUE if unknown. */
    public static int parse(String s) {
        String k = s.toLowerCase(Locale.ROOT);
        if (k.equals("none") || k.equals("unbind") || k.equals("null")) return -1;
        if (k.startsWith("mouse")) {
            try {
                return ModuleManager.MOUSE_OFFSET + Integer.parseInt(k.substring(5)) - 1;
            } catch (NumberFormatException e) {
                return Integer.MIN_VALUE;
            }
        }
        switch (k) {
            case "rshift": return GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "lshift": return GLFW.GLFW_KEY_LEFT_SHIFT;
            case "rctrl", "rcontrol": return GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "lctrl", "lcontrol": return GLFW.GLFW_KEY_LEFT_CONTROL;
            case "ralt": return GLFW.GLFW_KEY_RIGHT_ALT;
            case "lalt": return GLFW.GLFW_KEY_LEFT_ALT;
            case "insert": return GLFW.GLFW_KEY_INSERT;
            case "delete", "del": return GLFW.GLFW_KEY_DELETE;
            case "home": return GLFW.GLFW_KEY_HOME;
            case "end": return GLFW.GLFW_KEY_END;
            case "pgup": return GLFW.GLFW_KEY_PAGE_UP;
            case "pgdn": return GLFW.GLFW_KEY_PAGE_DOWN;
            case "tab": return GLFW.GLFW_KEY_TAB;
            case "caps": return GLFW.GLFW_KEY_CAPS_LOCK;
            case "grave": return GLFW.GLFW_KEY_GRAVE_ACCENT;
            case "space": return GLFW.GLFW_KEY_SPACE;
            default: break;
        }
        if (k.length() == 1) {
            char c = k.charAt(0);
            if (c >= 'a' && c <= 'z') return GLFW.GLFW_KEY_A + (c - 'a');
            if (c >= '0' && c <= '9') return GLFW.GLFW_KEY_0 + (c - '0');
        }
        if (k.startsWith("f")) {
            try {
                int n = Integer.parseInt(k.substring(1));
                if (n >= 1 && n <= 25) return GLFW.GLFW_KEY_F1 + n - 1;
            } catch (NumberFormatException ignored) {}
        }
        if (k.startsWith("kp")) {
            try {
                int n = Integer.parseInt(k.substring(2));
                if (n >= 0 && n <= 9) return GLFW.GLFW_KEY_KP_0 + n;
            } catch (NumberFormatException ignored) {}
        }
        return Integer.MIN_VALUE;
    }
}
