package dev.bali.module.misc;

import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

public final class AutoReconnect extends Module {
    public final NumberSetting delay = number("Delay", 5, 1, 120, 1, "Seconds before reconnecting");

    public static ServerInfo lastServer;
    public static ServerAddress lastAddress;

    public AutoReconnect() {
        super("AutoReconnect", "Reconnects after a kick", Category.MISC);
    }

    public static void reconnect() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (lastServer == null || lastAddress == null) return;
        ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), mc, lastAddress, lastServer, false, null);
    }
}
