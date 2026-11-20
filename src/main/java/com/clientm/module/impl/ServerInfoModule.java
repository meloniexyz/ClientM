package com.clientm.module.impl;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.client.network.NetHandlerPlayClient;

import com.clientm.module.HudModule;
import com.clientm.setting.BoolSetting;

/**
 * Your ping and the server's ticks per second, so you can tell whether a lost trade was the connection, a lagging
 * server or you. Ping is the server's own figure for you (the one in the tab list); TPS is worked out from how fast
 * the server's clock advances between its time updates (sent once a second).
 */
public class ServerInfoModule extends HudModule {

    private final BoolSetting showPing = add(new BoolSetting("ping", "Show ping", true));
    private final BoolSetting showTps = add(new BoolSetting("tps", "Show server TPS", true));

    // time updates: the server's world time and when it arrived
    private static long lastWorldTime = -1, lastArrival;
    private static float tps = 20f;
    private static boolean tpsKnown;

    private String text = "";
    private int textWidth;

    public ServerInfoModule() {
        super("serverinfo", "Ping & TPS", false, 0.005f, 0.11f);
    }

    @Override
    public String description() {
        return "Your ping and the server's TPS: is it lag, or you?";
    }

    /** Called for every time update from the server (MixinNetHandlerPlayClient_ServerInfo). */
    public static void timeUpdate(long worldTime) {
        long now = System.nanoTime();
        if (lastWorldTime >= 0 && worldTime > lastWorldTime) {
            double seconds = (now - lastArrival) / 1e9;
            if (seconds > 0.3 && seconds < 10) {
                float measured = (float) Math.min(20.0, (worldTime - lastWorldTime) / seconds);
                // Smoothed: one late packet should not show as a lag spike.
                tps = tpsKnown ? tps * 0.6f + measured * 0.4f : measured;
                tpsKnown = true;
            }
        }
        lastWorldTime = worldTime;
        lastArrival = now;
    }

    /** Forgets the measurements when joining another server or world. */
    public static void reset() {
        lastWorldTime = -1;
        tpsKnown = false;
        tps = 20f;
    }

    /** For the feature test tour: what the display shows. */
    public static String shownText() {
        return lastText;
    }

    private static volatile String lastText = "";

    private int ping() {
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null || mc.thePlayer == null) return -1;
        String me = mc.thePlayer.getCommandSenderName();
        for (Object o : net.playerInfoList) {
            GuiPlayerInfo info = (GuiPlayerInfo) o;
            if (info.name.equals(me)) return info.responseTime;
        }
        return -1;
    }

    @Override
    public void onTick() {
        StringBuilder sb = new StringBuilder();
        if (showPing.value) {
            int ping = ping();
            sb.append(ping < 0 ? "? ms" : ping + " ms");
        }
        if (showTps.value) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(tpsKnown ? String.format("%.1f TPS", tps) : "? TPS");
        }
        String next = sb.toString();
        if (!next.equals(text)) {
            text = next;
            lastText = next;
            textWidth = mc.fontRenderer.getStringWidth(text);
        }
    }

    @Override
    public int width() {
        return textWidth + 6;
    }

    @Override
    public int height() {
        return 14;
    }

    @Override
    public void render(FontRenderer font) {
        drawLabel(font, text.isEmpty() ? "Ping & TPS" : text);
    }
}
