package com.clientm.gui;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiOpenEvent;

import com.clientm.Tags;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The title screen in the ClientM look: black, the name, and four buttons. It replaces the vanilla title screen (and
 * its animated panorama, which is the most expensive thing vanilla draws in a menu).
 */
public class ClientMainMenu extends GuiScreen {

    private static final int LINE = 0xFF22222C;
    private static final int PANEL = 0xFF0B0B0F;
    private static final int HOVER = 0x18FFFFFF;
    private static final int TEXT = 0xEDEDF2;
    private static final int TEXT_2 = 0xA3A3B2;
    private static final int TEXT_3 = 0x6C6C7C;
    private static final int ACCENT = 0xFFFF4D5E;
    private static final int INK = 0x0C0A18;

    private static final String[] LABELS = { "Singleplayer", "Multiplayer", "Options", "Quit" };
    private static final int BTN_W = 170;
    private static final int BTN_H = 22;
    private static final int GAP = 6;
    private static final String COPYRIGHT = "Copyright Mojang AB. Do not distribute!";

    private SmoothFont font, bold, logo;
    private int top;

    /** Swaps the vanilla title screen for this one whenever the game opens it. */
    public static class Hook {

        @SubscribeEvent
        public void onOpen(GuiOpenEvent event) {
            if (event.gui != null && event.gui.getClass() == GuiMainMenu.class) event.gui = new ClientMainMenu();
        }
    }

    @Override
    public void initGui() {
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        font = SmoothFont.get(scale, 9, false);
        bold = SmoothFont.get(scale, 9, true);
        logo = SmoothFont.get(scale, 30, true);
        int total = 34 + 18 + LABELS.length * (BTN_H + GAP);
        top = (height - total) / 2;
    }

    private int buttonY(int i) {
        return top + 34 + 18 + i * (BTN_H + GAP);
    }

    private int buttonAt(int mouseX, int mouseY) {
        int x = (width - BTN_W) / 2;
        for (int i = 0; i < LABELS.length; i++) {
            int y = buttonY(i);
            if (mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H) return i;
        }
        return -1;
    }

    private static void round(int x, int y, int w, int h, int color) {
        int x2 = x + w;
        int y2 = y + h;
        Gui.drawRect(x + 2, y, x2 - 2, y2, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y2 - 1, color);
        Gui.drawRect(x, y + 2, x + 1, y2 - 2, color);
        Gui.drawRect(x2 - 2, y + 1, x2 - 1, y2 - 1, color);
        Gui.drawRect(x2 - 1, y + 2, x2, y2 - 2, color);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Black, fading to a very dark red at the bottom
        drawGradientRect(0, 0, width, height, 0xFF000000, 0xFF16070B);

        int half = logo.width("ClientM") / 2;
        logo.draw("Client", width / 2 - half, top, TEXT);
        logo.draw("M", width / 2 - half + logo.width("Client"), top, 0xFF4D5E);
        String tag = "Minecraft 1.7.10";
        font.draw(tag, (width - font.width(tag)) / 2, top + 34, TEXT_3);

        int x = (width - BTN_W) / 2;
        int hover = buttonAt(mouseX, mouseY);
        for (int i = 0; i < LABELS.length; i++) {
            int y = buttonY(i);
            boolean primary = i == 0;
            if (primary) {
                round(x, y, BTN_W, BTN_H, ACCENT);
            } else {
                round(x, y, BTN_W, BTN_H, LINE);
                round(x + 1, y + 1, BTN_W - 2, BTN_H - 2, PANEL);
            }
            if (i == hover) round(x, y, BTN_W, BTN_H, HOVER);
            SmoothFont f = primary ? bold : font;
            f.draw(LABELS[i], x + (BTN_W - f.width(LABELS[i])) / 2, y + 8, primary ? INK : i == hover ? TEXT : TEXT_2);
        }

        font.draw("ClientM " + Tags.VERSION, 6, height - 12, TEXT_3);
        font.draw(COPYRIGHT, width - 6 - font.width(COPYRIGHT), height - 12, TEXT_3);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) return;
        int i = buttonAt(mouseX, mouseY);
        if (i < 0) return;
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
        if (i == 0) mc.displayGuiScreen(new GuiSelectWorld(this));
        else if (i == 1) mc.displayGuiScreen(new GuiMultiplayer(this));
        else if (i == 2) mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
        else mc.shutdown();
    }

    @Override
    protected void keyTyped(char typed, int key) {
        // Escape does nothing on the title screen, as in vanilla.
    }
}
