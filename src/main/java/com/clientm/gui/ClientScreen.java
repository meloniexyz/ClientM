package com.clientm.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.HudModule;
import com.clientm.module.Module;
import com.clientm.setting.ActionSetting;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.IntSetting;
import com.clientm.setting.Setting;

/**
 * The whole ClientM interface in one screen. It opens as the HUD editor (drag modules around) with a Mods button in
 * the middle; that opens a card grid of modules by category, and each card's Options button opens its settings page.
 * Escape steps back one level.
 */
public class ClientScreen extends GuiScreen {

    private enum View {
        EDIT,
        MODS,
        SETTINGS
    }

    private static final int ACCENT = 0xFFE0A526;
    private static final int PANEL = 0xF0121318;
    private static final int HEADER = 0xFF1B1D25;
    private static final int CARD = 0xFF22252F;
    private static final int CARD_HOVER = 0xFF2B2F3C;
    private static final int ON = 0xFF2E9E4F;
    private static final int OFF = 0xFF8A3030;
    private static final int NEUTRAL = 0xFF3A3F50;

    private static final int PANEL_W = 384;
    private static final int PANEL_H = 236;
    private static final int HEADER_H = 26;
    private static final int COLS = 3;
    private static final int GAP = 6;
    private static final int CARD_H = 56;
    private static final int ROW = 20;
    private static final int SLIDER_W = 110;
    private static final int MIN_W = 60;
    private static final int MIN_H = 14;

    private static Category selected = Category.HUD;

    private View view = View.EDIT;
    private long viewSince;
    private Module settingsOf;
    private int scroll;

    private final List<Module> cards = new ArrayList<>();

    private HudModule dragging;
    private int dragOffX, dragOffY;
    private IntSetting sliding;

    // Panel geometry, recomputed in initGui
    private int px, py, bodyX, bodyY, bodyW, bodyH, cardW;

    @Override
    public void initGui() {
        ClientM.modules.editing = true;
        px = (width - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;
        bodyX = px + 8;
        bodyY = py + HEADER_H + 22;
        bodyW = PANEL_W - 16;
        bodyH = PANEL_H - HEADER_H - 30;
        cardW = (bodyW - GAP * (COLS - 1)) / COLS;
        if (viewSince == 0) viewSince = System.currentTimeMillis();
        rebuildCards();
    }

    @Override
    public void onGuiClosed() {
        ClientM.modules.editing = false;
        ClientM.modules.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void show(View v) {
        view = v;
        viewSince = System.currentTimeMillis();
        scroll = 0;
        dragging = null;
        sliding = null;
    }

    private void rebuildCards() {
        cards.clear();
        for (Module m : ClientM.modules.all()) if (m.category == selected) cards.add(m);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void button(int x, int y, int w, int h, String label, int color, boolean hover) {
        Gui.drawRect(x, y, x + w, y + h, color);
        if (hover) Gui.drawRect(x, y, x + w, y + h, 0x30FFFFFF);
        fontRendererObj
            .drawString(label, x + (w - fontRendererObj.getStringWidth(label)) / 2, y + (h - 8) / 2, 0xFFFFFF);
    }

    private static int hudW(HudModule m) {
        return Math.max(m.width(), MIN_W);
    }

    private static int hudH(HudModule m) {
        return Math.max(m.height(), MIN_H);
    }

    private int hudX(HudModule m) {
        return Math.max(0, Math.min((int) (m.x * width), width - hudW(m)));
    }

    private int hudY(HudModule m) {
        return Math.max(0, Math.min((int) (m.y * height), height - hudH(m)));
    }

    private int cardX(int i) {
        return bodyX + (i % COLS) * (cardW + GAP);
    }

    private int cardY(int i) {
        return bodyY + (i / COLS) * (CARD_H + GAP) - scroll;
    }

    private boolean rowVisible(int y, int h) {
        return y >= bodyY && y + h <= bodyY + bodyH;
    }

    private int contentHeight() {
        if (view == View.MODS) return ((cards.size() + COLS - 1) / COLS) * (CARD_H + GAP);
        if (view == View.SETTINGS) return settingsOf.settings.size() * ROW;
        return 0;
    }

    private void clampScroll() {
        int step = view == View.MODS ? CARD_H + GAP : ROW;
        int max = Math.max(0, contentHeight() - bodyH);
        max = (max + step - 1) / step * step;
        scroll = Math.max(0, Math.min(scroll, max));
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(0, 0, width, height, view == View.EDIT ? 0x50000000 : 0x90000000);
        drawHud(mouseX, mouseY);

        float t = Math.min(1f, (System.currentTimeMillis() - viewSince) / 140f);
        float ease = 1f - (1f - t) * (1f - t);

        if (view == View.EDIT) {
            drawEditOverlay(mouseX, mouseY);
            return;
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(0f, (1f - ease) * 12f, 0f);
        drawPanel(mouseX, mouseY);
        GL11.glPopMatrix();
    }

    private void drawHud(int mouseX, int mouseY) {
        boolean editing = view == View.EDIT;
        for (HudModule m : ClientM.modules.hud()) {
            int x = hudX(m);
            int y = hudY(m);
            int w = hudW(m);
            int h = hudH(m);
            boolean empty = m.width() <= 0 || m.height() <= 0;
            if (!editing && (!m.enabled || empty)) continue;

            GL11.glPushMatrix();
            GL11.glTranslatef(x, y, 0f);
            if (empty) {
                Gui.drawRect(0, 0, w, h, 0x6F000000);
                fontRendererObj.drawStringWithShadow(m.name, 3, 3, 0xFFFFFF);
            } else {
                m.render(fontRendererObj);
            }
            if (editing && !m.enabled) Gui.drawRect(0, 0, w, h, 0xB0200000);
            GL11.glPopMatrix();

            if (!editing) continue;
            boolean hover = in(mouseX, mouseY, x, y, w, h);
            int border = hover || m == dragging ? ACCENT : 0x50FFFFFF;
            Gui.drawRect(x - 1, y - 1, x + w + 1, y, border);
            Gui.drawRect(x - 1, y + h, x + w + 1, y + h + 1, border);
            Gui.drawRect(x - 1, y, x, y + h, border);
            Gui.drawRect(x + w, y, x + w + 1, y + h, border);
            if (hover) {
                String label = m.enabled ? m.name : m.name + " (off)";
                fontRendererObj.drawStringWithShadow(label, x, y < 12 ? y + h + 3 : y - 10, ACCENT);
            }
        }
    }

    private void drawEditOverlay(int mouseX, int mouseY) {
        int cx = width / 2;
        int cy = height / 2;
        GL11.glPushMatrix();
        GL11.glTranslatef(cx, cy - 34, 0f);
        GL11.glScalef(2f, 2f, 1f);
        fontRendererObj
            .drawStringWithShadow("ClientM", -fontRendererObj.getStringWidth("ClientM") / 2, 0, ACCENT & 0xFFFFFF);
        GL11.glPopMatrix();
        button(cx - 50, cy - 8, 100, 20, "MODS", NEUTRAL, in(mouseX, mouseY, cx - 50, cy - 8, 100, 20));
        drawCenteredString(
            fontRendererObj,
            "Drag a module to move it   Right-click: on/off   Middle-click: background",
            cx,
            height - 14,
            0xAAAAAA);
    }

    private void drawPanel(int mouseX, int mouseY) {
        Gui.drawRect(px, py, px + PANEL_W, py + PANEL_H, PANEL);
        Gui.drawRect(px, py, px + PANEL_W, py + HEADER_H, HEADER);
        Gui.drawRect(px, py + HEADER_H - 1, px + PANEL_W, py + HEADER_H, ACCENT);

        // Header: back button, title, close
        String back = view == View.SETTINGS ? "< Mods" : "< HUD";
        int backW = fontRendererObj.getStringWidth(back) + 12;
        button(px + 6, py + 6, backW, 14, back, NEUTRAL, in(mouseX, mouseY, px + 6, py + 6, backW, 14));
        String title = view == View.SETTINGS ? settingsOf.name : "Mods";
        fontRendererObj.drawStringWithShadow(title, px + 14 + backW, py + 9, 0xFFFFFF);
        button(px + PANEL_W - 20, py + 6, 14, 14, "x", OFF, in(mouseX, mouseY, px + PANEL_W - 20, py + 6, 14, 14));

        if (view == View.MODS) drawMods(mouseX, mouseY);
        else drawSettings(mouseX, mouseY);

        if (contentHeight() > bodyH) {
            int trackH = bodyH;
            int thumbH = Math.max(12, trackH * bodyH / contentHeight());
            int thumbY = bodyY + (trackH - thumbH) * scroll / Math.max(1, contentHeight() - bodyH);
            Gui.drawRect(px + PANEL_W - 5, bodyY, px + PANEL_W - 3, bodyY + trackH, 0x40FFFFFF);
            Gui.drawRect(
                px + PANEL_W - 5,
                Math.min(thumbY, bodyY + trackH - thumbH),
                px + PANEL_W - 3,
                Math.min(thumbY, bodyY + trackH - thumbH) + thumbH,
                ACCENT);
        }
    }

    private void drawMods(int mouseX, int mouseY) {
        // Category tabs
        Category[] cats = Category.values();
        int tabW = bodyW / cats.length;
        for (int i = 0; i < cats.length; i++) {
            int tx = bodyX + i * tabW;
            int ty = py + HEADER_H + 4;
            boolean sel = cats[i] == selected;
            boolean hover = in(mouseX, mouseY, tx, ty, tabW - 2, 14);
            Gui.drawRect(tx, ty, tx + tabW - 2, ty + 14, sel ? NEUTRAL : hover ? 0x30FFFFFF : 0x18FFFFFF);
            if (sel) Gui.drawRect(tx, ty + 13, tx + tabW - 2, ty + 14, ACCENT);
            String label = cats[i].label;
            fontRendererObj.drawString(
                label,
                tx + (tabW - 2 - fontRendererObj.getStringWidth(label)) / 2,
                ty + 3,
                sel ? 0xFFFFFF : 0xAAAAAA);
        }

        for (int i = 0; i < cards.size(); i++) {
            int x = cardX(i);
            int y = cardY(i);
            if (!rowVisible(y, CARD_H)) continue;
            Module m = cards.get(i);
            boolean hover = in(mouseX, mouseY, x, y, cardW, CARD_H);
            Gui.drawRect(x, y, x + cardW, y + CARD_H, hover ? CARD_HOVER : CARD);
            Gui.drawRect(x, y, x + cardW, y + 1, m.enabled || !m.toggleable() ? ACCENT : 0xFF444444);

            int nameW = fontRendererObj.getStringWidth(m.name);
            int swatch = m.previewColor();
            int nameX = x + (cardW - nameW - (swatch != -1 ? 14 : 0)) / 2;
            fontRendererObj.drawStringWithShadow(m.name, nameX, y + 10, 0xFFFFFF);
            if (swatch != -1) Gui.drawRect(nameX + nameW + 4, y + 9, nameX + nameW + 14, y + 19, 0xFF000000 | swatch);

            int by = y + CARD_H - 20;
            boolean hasOptions = !m.settings.isEmpty();
            int optW = hasOptions ? 46 : 0;
            if (m.toggleable()) {
                int tw = cardW - 8 - (hasOptions ? optW + 4 : 0);
                button(
                    x + 4,
                    by,
                    tw,
                    16,
                    m.enabled ? "Enabled" : "Disabled",
                    m.enabled ? ON : OFF,
                    in(mouseX, mouseY, x + 4, by, tw, 16));
            }
            if (hasOptions) {
                int ox = m.toggleable() ? x + cardW - 4 - optW : x + 4;
                int ow = m.toggleable() ? optW : cardW - 8;
                button(ox, by, ow, 16, "Options", NEUTRAL, in(mouseX, mouseY, ox, by, ow, 16));
            }
        }
    }

    private void drawSettings(int mouseX, int mouseY) {
        String d = settingsOf.description();
        if (d != null) fontRendererObj.drawString(d, bodyX + 2, py + HEADER_H + 8, 0x999999);

        int right = bodyX + bodyW - 8;
        List<Setting> settings = settingsOf.settings;
        for (int i = 0; i < settings.size(); i++) {
            int y = bodyY + i * ROW - scroll;
            if (!rowVisible(y, ROW)) continue;
            Setting s = settings.get(i);
            boolean hover = in(mouseX, mouseY, bodyX, y, bodyW - 6, ROW - 2);
            Gui.drawRect(bodyX, y, bodyX + bodyW - 6, y + ROW - 2, hover ? CARD_HOVER : CARD);

            if (s instanceof BoolSetting) {
                BoolSetting b = (BoolSetting) s;
                fontRendererObj.drawString(b.name, bodyX + 6, y + 5, 0xDDDDDD);
                button(right - 34, y + 2, 34, 14, b.value ? "ON" : "OFF", b.value ? ON : OFF, false);
            } else if (s instanceof IntSetting) {
                IntSetting n = (IntSetting) s;
                fontRendererObj.drawString(n.name + ": " + n.value, bodyX + 6, y + 5, 0xDDDDDD);
                int sx = right - SLIDER_W;
                int filled = n.max == n.min ? 0 : (n.value - n.min) * SLIDER_W / (n.max - n.min);
                Gui.drawRect(sx, y + 8, sx + SLIDER_W, y + 10, 0xFF555555);
                Gui.drawRect(sx, y + 8, sx + filled, y + 10, ACCENT);
                Gui.drawRect(sx + filled - 2, y + 4, sx + filled + 2, y + 14, 0xFFFFFFFF);
            } else if (s instanceof ActionSetting) {
                ActionSetting a = (ActionSetting) s;
                fontRendererObj.drawString(a.name, bodyX + 6, y + 5, 0xDDDDDD);
                int bw = fontRendererObj.getStringWidth(a.button) + 14;
                button(right - bw, y + 2, bw, 14, a.button, NEUTRAL, hover && mouseX >= right - bw);
            }
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (view == View.SETTINGS) {
                show(View.MODS);
                return;
            }
            if (view == View.MODS) {
                show(View.EDIT);
                return;
            }
        }
        super.keyTyped(typed, key);
    }

    private void slide(IntSetting s, int mouseX) {
        int sx = bodyX + bodyW - 8 - SLIDER_W;
        float frac = Math.max(0f, Math.min(1f, (mouseX - sx) / (float) SLIDER_W));
        s.set(s.min + Math.round(frac * (s.max - s.min)));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (view == View.EDIT) {
            clickEdit(mouseX, mouseY, button);
            return;
        }
        if (button != 0) return;

        int backW = fontRendererObj.getStringWidth(view == View.SETTINGS ? "< Mods" : "< HUD") + 12;
        if (in(mouseX, mouseY, px + 6, py + 6, backW, 14)) {
            show(view == View.SETTINGS ? View.MODS : View.EDIT);
            return;
        }
        if (in(mouseX, mouseY, px + PANEL_W - 20, py + 6, 14, 14)) {
            mc.displayGuiScreen(null);
            return;
        }
        if (!in(mouseX, mouseY, px, py, PANEL_W, PANEL_H)) {
            show(View.EDIT);
            return;
        }
        if (view == View.MODS) clickMods(mouseX, mouseY);
        else clickSettings(mouseX, mouseY);
    }

    private void clickEdit(int mouseX, int mouseY, int button) {
        if (button == 0 && in(mouseX, mouseY, width / 2 - 50, height / 2 - 8, 100, 20)) {
            show(View.MODS);
            return;
        }
        HudModule[] hud = ClientM.modules.hud();
        for (int i = hud.length - 1; i >= 0; i--) {
            HudModule m = hud[i];
            if (!in(mouseX, mouseY, hudX(m), hudY(m), hudW(m), hudH(m))) continue;
            if (button == 0) {
                dragging = m;
                dragOffX = mouseX - hudX(m);
                dragOffY = mouseY - hudY(m);
            } else if (button == 1) {
                m.setEnabled(!m.enabled);
            } else if (button == 2) {
                m.background = !m.background;
            }
            return;
        }
    }

    private void clickMods(int mouseX, int mouseY) {
        Category[] cats = Category.values();
        int tabW = bodyW / cats.length;
        for (int i = 0; i < cats.length; i++) {
            if (in(mouseX, mouseY, bodyX + i * tabW, py + HEADER_H + 4, tabW - 2, 14)) {
                selected = cats[i];
                scroll = 0;
                rebuildCards();
                return;
            }
        }
        for (int i = 0; i < cards.size(); i++) {
            int x = cardX(i);
            int y = cardY(i);
            if (!rowVisible(y, CARD_H) || !in(mouseX, mouseY, x, y, cardW, CARD_H)) continue;
            Module m = cards.get(i);
            int by = y + CARD_H - 20;
            boolean hasOptions = !m.settings.isEmpty();
            boolean onButtons = mouseY >= by && mouseY < by + 16;
            boolean onOptions = hasOptions && onButtons && (!m.toggleable() || mouseX >= x + cardW - 4 - 46);
            if (onOptions) {
                settingsOf = m;
                show(View.SETTINGS);
            } else if (onButtons && m.toggleable()) {
                m.setEnabled(!m.enabled);
            }
            return;
        }
    }

    private void clickSettings(int mouseX, int mouseY) {
        int right = bodyX + bodyW - 8;
        List<Setting> settings = settingsOf.settings;
        for (int i = 0; i < settings.size(); i++) {
            int y = bodyY + i * ROW - scroll;
            if (!rowVisible(y, ROW) || !in(mouseX, mouseY, bodyX, y, bodyW - 6, ROW - 2)) continue;
            Setting s = settings.get(i);
            if (s instanceof BoolSetting) {
                ((BoolSetting) s).value = !((BoolSetting) s).value;
                settingsOf.onSettingsChanged();
            } else if (s instanceof IntSetting && mouseX >= right - SLIDER_W - 4) {
                sliding = (IntSetting) s;
                slide(sliding, mouseX);
            } else if (s instanceof ActionSetting) {
                ActionSetting a = (ActionSetting) s;
                if (mouseX >= right - fontRendererObj.getStringWidth(a.button) - 14) a.action.run();
            }
            return;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceClick) {
        if (sliding != null) {
            slide(sliding, mouseX);
        } else if (dragging != null && button == 0) {
            int x = Math.max(0, Math.min(mouseX - dragOffX, width - hudW(dragging)));
            int y = Math.max(0, Math.min(mouseY - dragOffY, height - hudH(dragging)));
            // Half a pixel is added so converting back to pixels lands on the same pixel.
            dragging.x = (x + 0.5f) / width;
            dragging.y = (y + 0.5f) / height;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        if (which != 0) return;
        dragging = null;
        if (sliding != null) {
            // Apply once on release, so dragging a slider does not reload chunks on every pixel.
            sliding = null;
            settingsOf.onSettingsChanged();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && view != View.EDIT) {
            scroll += (wheel > 0 ? -1 : 1) * (view == View.MODS ? CARD_H + GAP : ROW);
            clampScroll();
        }
    }
}
