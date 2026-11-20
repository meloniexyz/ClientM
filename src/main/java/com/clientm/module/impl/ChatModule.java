package com.clientm.module.impl;

import java.text.SimpleDateFormat;
import java.util.Date;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import org.lwjgl.input.Keyboard;

import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.ChoiceSetting;

import cpw.mods.fml.client.registry.ClientRegistry;

/**
 * Chat improvements: hide the chat from the screen (it still shows while you type), the dark background and the text
 * shadow, timestamps, and repeated messages stacked into one line with a counter. Only what you see changes.
 * (Hodgepodge
 * already keeps 8191 lines of chat history.)
 */
public class ChatModule extends Module {

    private static ChatModule instance;

    private final BoolSetting show = add(new BoolSetting("show", "Show chat on screen", true));
    private final BoolSetting background = add(new BoolSetting("background", "Chat background", false));
    private final BoolSetting shadow = add(new BoolSetting("shadow", "Text shadow", true));
    private final BoolSetting timestamps = add(new BoolSetting("timestamps", "Timestamps", false));
    private final ChoiceSetting timeFormat = add(
        new ChoiceSetting("timeFormat", "Time format", 0, "14:05", "14:05:09", "2:05 PM"));
    private final BoolSetting stack = add(new BoolSetting("stack", "Stack repeated messages", true));

    /** Toggles "Show chat on screen"; unbound until you pick a key in Controls. */
    private final KeyBinding toggleKey = new KeyBinding("Toggle chat (ClientM)", Keyboard.KEY_NONE, "ClientM");

    private static final String[] FORMATS = { "HH:mm", "HH:mm:ss", "h:mm a" };
    /** Chat line ids for ClientM's own lines, so a repeated message can replace the line it repeats. */
    private static final int ID_BASE = 0x434D0000;

    private String lastText;
    private int lastId, repeats, nextId;
    private boolean printing;

    public ChatModule() {
        super("chat", "Chat", Category.HUD, true);
        instance = this;
        ClientRegistry.registerKeyBinding(toggleKey);
    }

    @Override
    public String description() {
        return "Hide chat, background, shadow, timestamps, stacking";
    }

    /** False when the chat should not be drawn on the HUD (it is always drawn while the chat screen is open). */
    public static boolean visible() {
        ChatModule m = instance;
        if (m == null || !m.enabled || m.show.value) return true;
        return m.mc.ingameGUI != null && m.mc.ingameGUI.getChatGUI()
            .getChatOpen();
    }

    /** The toggle key (checked every client tick while the module is on). */
    @Override
    public void onTick() {
        boolean changed = false;
        while (toggleKey.isPressed()) {
            show.value = !show.value;
            changed = true;
        }
        if (changed) com.clientm.ClientM.modules.save();
    }

    /** False when chat lines are drawn without the dark background behind them. */
    public static boolean background() {
        ChatModule m = instance;
        return m == null || !m.enabled || m.background.value;
    }

    /** False when chat text is drawn without its shadow. */
    public static boolean shadow() {
        ChatModule m = instance;
        return m == null || !m.enabled || m.shadow.value;
    }

    /**
     * A new chat message (MixinGuiNewChat_Chat). Returns the message to show and its line id instead, or null to show
     * it unchanged.
     */
    public static Object[] rewrite(IChatComponent message, int id) {
        ChatModule m = instance;
        if (m == null || !m.enabled || m.printing || id != 0 || message == null) return null;
        if (!m.timestamps.value && !m.stack.value) return null;
        String text = message.getUnformattedText();
        IChatComponent shown = message;
        int lineId;
        if (m.stack.value && text.equals(m.lastText)
            && !text.trim()
                .isEmpty()) {
            m.repeats++;
            lineId = m.lastId; // reprinting with the same id replaces the earlier line
            shown = message.createCopy()
                .appendSibling(
                    new ChatComponentText(" (x" + m.repeats + ")")
                        .setChatStyle(new net.minecraft.util.ChatStyle().setColor(EnumChatFormatting.GRAY)));
        } else {
            m.lastText = text;
            m.repeats = 1;
            m.nextId = (m.nextId + 1) & 0xFFFF;
            lineId = m.stack.value ? ID_BASE + m.nextId : 0;
            m.lastId = lineId;
        }
        if (m.timestamps.value) {
            String time = new SimpleDateFormat(FORMATS[Math.max(0, Math.min(FORMATS.length - 1, m.timeFormat.value))])
                .format(new Date());
            shown = new ChatComponentText("")
                .appendSibling(
                    new ChatComponentText("[" + time + "] ")
                        .setChatStyle(new net.minecraft.util.ChatStyle().setColor(EnumChatFormatting.GRAY)))
                .appendSibling(shown);
        }
        return new Object[] { shown, lineId };
    }

    /** Guards the reprint, so the rewritten message is not rewritten again. */
    public static void printing(boolean on) {
        if (instance != null) instance.printing = on;
    }
}
