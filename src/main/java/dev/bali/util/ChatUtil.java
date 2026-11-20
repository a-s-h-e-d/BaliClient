package dev.bali.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class ChatUtil {
    private ChatUtil() {}

    private static final String PREFIX = Formatting.DARK_AQUA + "[" + Formatting.AQUA + "Bali" + Formatting.DARK_AQUA + "] " + Formatting.RESET;

    public static void info(String msg) { send(PREFIX + Formatting.GRAY + msg); }

    public static void warn(String msg) { send(PREFIX + Formatting.YELLOW + msg); }

    public static void error(String msg) { send(PREFIX + Formatting.RED + msg); }

    private static void send(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal(msg));
        });
    }
}
