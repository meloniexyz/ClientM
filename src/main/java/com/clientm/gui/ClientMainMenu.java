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
    /** True while the account list under the account button is open. */
    private boolean accountsOpen;
    private static final int ACC_H = 20;
    private static final int ROW_H = 20;

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
            // Every button is dark until the pointer is on it; then it fills with the accent colour.
            boolean lit = i == hover;
            if (lit) {
                round(x, y, BTN_W, BTN_H, ACCENT);
            } else {
                round(x, y, BTN_W, BTN_H, LINE);
                round(x + 1, y + 1, BTN_W - 2, BTN_H - 2, PANEL);
            }
            SmoothFont f = lit ? bold : font;
            f.draw(LABELS[i], x + (BTN_W - f.width(LABELS[i])) / 2, y + 8, lit ? INK : TEXT_2);
        }

        drawAccounts(mouseX, mouseY);

        // The running version, bottom left: name in light grey, number in the accent colour so it stands out.
        font.draw("ClientM ", 6, height - 12, TEXT_2);
        font.draw(Tags.VERSION, 6 + font.width("ClientM "), height - 12, 0xFF4D5E);
        font.draw(COPYRIGHT, width - 6 - font.width(COPYRIGHT), height - 12, TEXT_3);
    }

    // ------------------------------------------------------------------ account switcher (top right)

    /** Test aid: opens or closes the account list. */
    public void setAccountsOpen(boolean open) {
        accountsOpen = open;
    }

    private String currentName() {
        return mc.getSession()
            .getUsername();
    }

    private int accountWidth() {
        return 6 + 14 + 6 + font.width(currentName()) + 8;
    }

    private int listWidth() {
        int w = accountWidth();
        for (Accounts.Account a : Accounts.all()) w = Math.max(w, 6 + 14 + 6 + font.width(a.name) + 8);
        return Math.max(w, 150);
    }

    private boolean onAccountButton(int mx, int my) {
        int w = accountWidth();
        return mx >= width - 6 - w && mx < width - 6 && my >= 6 && my < 6 + ACC_H;
    }

    /** The row of the open list under the pointer, or -1. */
    private int accountRowAt(int mx, int my) {
        if (!accountsOpen) return -1;
        int w = listWidth();
        int x = width - 6 - w;
        int y = 6 + ACC_H + 4;
        int rows = Math.max(
            1,
            Accounts.all()
                .size());
        if (mx < x || mx >= x + w || my < y + 3 || my >= y + 3 + rows * ROW_H) return -1;
        return (my - y - 3) / ROW_H;
    }

    private void drawAccounts(int mx, int my) {
        int w = accountWidth();
        int x = width - 6 - w;
        boolean hover = onAccountButton(mx, my) || accountsOpen;
        round(x, 6, w, ACC_H, hover ? ACCENT : LINE);
        round(x + 1, 7, w - 2, ACC_H - 2, PANEL);
        Accounts.drawFace(null, x + 4, 9, 14);
        font.draw(currentName(), x + 6 + 14 + 6, 13, TEXT);

        if (!accountsOpen) return;
        java.util.List<Accounts.Account> list = Accounts.all();
        int lw = listWidth();
        int lx = width - 6 - lw;
        int ly = 6 + ACC_H + 4;
        int rows = Math.max(1, list.size());
        round(lx, ly, lw, rows * ROW_H + 6, LINE);
        round(lx + 1, ly + 1, lw - 2, rows * ROW_H + 4, PANEL);
        if (list.isEmpty()) {
            font.draw("Add accounts in the ClientM launcher", lx + 8, ly + 3 + 6, TEXT_3);
            return;
        }
        int over = accountRowAt(mx, my);
        for (int i = 0; i < list.size(); i++) {
            Accounts.Account a = list.get(i);
            int ry = ly + 3 + i * ROW_H;
            boolean current = Accounts.isCurrent(a);
            if (i == over && !current) round(lx + 3, ry, lw - 6, ROW_H, 0x22FFFFFF);
            Accounts.drawFace(a, lx + 7, ry + 3, 14);
            font.draw(a.name, lx + 7 + 14 + 6, ry + 7, current ? 0xFF4D5E : i == over ? TEXT : TEXT_2);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) return;
        if (onAccountButton(mouseX, mouseY)) {
            accountsOpen = !accountsOpen;
            click();
            return;
        }
        if (accountsOpen) {
            int row = accountRowAt(mouseX, mouseY);
            java.util.List<Accounts.Account> list = Accounts.all();
            if (row >= 0 && row < list.size()) {
                Accounts.switchTo(list.get(row));
                click();
            }
            accountsOpen = false;
            return;
        }
        int i = buttonAt(mouseX, mouseY);
        if (i < 0) return;
        click();
        if (i == 0) mc.displayGuiScreen(new GuiSelectWorld(this));
        else if (i == 1) mc.displayGuiScreen(new GuiMultiplayer(this));
        else if (i == 2) mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
        else mc.shutdown();
    }

    private void click() {
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    @Override
    protected void keyTyped(char typed, int key) {
        // Escape does nothing on the title screen, as in vanilla.
    }
}
