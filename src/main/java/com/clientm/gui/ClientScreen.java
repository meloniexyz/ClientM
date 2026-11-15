package com.clientm.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.clientm.ClientM;
import com.clientm.compat.PerformancePreset;
import com.clientm.module.Category;
import com.clientm.module.HudModule;
import com.clientm.module.Module;
import com.clientm.setting.ActionSetting;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.IntSetting;
import com.clientm.setting.Setting;

/**
 * The whole ClientM interface in one screen. The game stays visible and every HUD module can be dragged at any time.
 * In the middle sit the name, a row of three buttons and one panel: a list of mods on the left, the picked mod's
 * options on the right. The video presets are a row of chips under the panel.
 */
public class ClientScreen extends GuiScreen {

    private static final int BG = 0xE6000000;
    private static final int LINE = 0xFF22222C;
    private static final int ROW_BG = 0xFF111116;
    private static final int HOVER = 0x18FFFFFF;
    private static final int TEXT = 0xEDEDF2;
    private static final int TEXT_2 = 0xA3A3B2;
    private static final int TEXT_3 = 0x6C6C7C;
    private static final int ACCENT = 0xFFFF4D5E;
    private static final int ACCENT_TINT = 0x38FF4D5E;
    private static final int PRESET = 0xFFA99DFF;
    private static final int INK = 0x0C0A18;
    private static final int SWITCH_OFF = 0xFF3A3A46;

    private static final int RAIL_W = 96;
    private static final int RAIL_ROW = 13;
    private static final int ROW = 17;
    private static final int SLIDER_W = 84;
    private static final int BTN_H = 18;
    private static final int MODS_W = 76;
    private static final int CHIP_H = 14;
    private static final int MIN_W = 60;
    private static final int MIN_H = 14;

    private static final String HINT = "Drag a HUD box to move it   Right-click: on/off   Middle-click: background";

    // Remembered between openings
    private static String selectedId = "fps";
    private static boolean panelOpen = true;

    private Module selected;
    private long openedAt;
    private int railScroll, listScroll;

    /** Left list: a Category (heading) or a Module per row. */
    private final List<Object> rail = new ArrayList<>();
    private final PerformancePreset[] presets = PerformancePreset.values();
    private final int[] chipX = new int[presets.length + 1];

    // Smooth menu text; the HUD boxes keep Minecraft's font
    private SmoothFont f, bold, logo;

    private HudModule dragging;
    private int dragOffX, dragOffY;
    private IntSetting sliding;

    // Geometry, recomputed in initGui
    private int logoY, btnY, sx, sy, sw, sh, railY, railH, lx, lw, bodyY, bodyH, chipY;

    @Override
    public void initGui() {
        ClientM.modules.editing = true;
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        f = SmoothFont.get(scale, 9, false);
        bold = SmoothFont.get(scale, 9, true);
        logo = SmoothFont.get(scale, 18, true);
        sw = Math.min(330, width - 16);
        sh = Math.min(156, height - 78);
        int total = 16 + 4 + BTN_H + 5 + sh + 5 + CHIP_H;
        logoY = (height - total) / 2;
        btnY = logoY + 20;
        sx = (width - sw) / 2;
        sy = btnY + BTN_H + 5;
        railY = sy + 5;
        railH = sh - 10;
        lx = sx + RAIL_W + 7;
        lw = sx + sw - 9 - lx;
        bodyY = sy + 24;
        bodyH = sh - 29;
        chipY = sy + sh + 5;

        int w = 0;
        for (PerformancePreset p : presets) w += f.width(p.label) + 12 + 3;
        int x = (width - w + 3) / 2;
        for (int i = 0; i < presets.length; i++) {
            chipX[i] = x;
            x += f.width(presets[i].label) + 12 + 3;
        }
        chipX[presets.length] = x;

        rail.clear();
        for (Category c : Category.values()) {
            rail.add(c);
            for (Module m : ClientM.modules.all()) if (m.category == c) rail.add(m);
        }
        if (selected == null) {
            for (Module m : ClientM.modules.all()) if (m.id.equals(selectedId)) selected = m;
            if (selected == null) selected = ClientM.modules.all()
                .get(0);
        }
        if (openedAt == 0) openedAt = System.currentTimeMillis();
        clampScroll();
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

    private void select(Module m) {
        selected = m;
        selectedId = m.id;
        listScroll = 0;
        sliding = null;
        panelOpen = true;
    }

    /** Opens the panel on the first mod of a category. */
    public void showMods(Category category) {
        for (Module m : ClientM.modules.all()) {
            if (m.category == category) {
                select(m);
                railScroll = Math.max(0, (rail.indexOf(m) - 1) * RAIL_ROW);
                clampScroll();
                return;
            }
        }
    }

    /** Opens the panel on one mod's options. */
    public void showSettings(Module module) {
        select(module);
        railScroll = Math.max(0, (rail.indexOf(module) - 3) * RAIL_ROW);
        clampScroll();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** A rectangle with 2-pixel rounded corners, made of five non-overlapping rectangles. */
    private static void round(int x, int y, int w, int h, int color) {
        int x2 = x + w;
        int y2 = y + h;
        Gui.drawRect(x + 2, y, x2 - 2, y2, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y2 - 1, color);
        Gui.drawRect(x, y + 2, x + 1, y2 - 2, color);
        Gui.drawRect(x2 - 2, y + 1, x2 - 1, y2 - 1, color);
        Gui.drawRect(x2 - 1, y + 2, x2, y2 - 2, color);
    }

    private static void panel(int x, int y, int w, int h) {
        round(x, y, w, h, LINE);
        round(x + 1, y + 1, w - 2, h - 2, BG);
    }

    private static void toggle(int x, int y, boolean on) {
        round(x, y, 16, 8, on ? ACCENT : SWITCH_OFF);
        Gui.drawRect(on ? x + 9 : x + 1, y + 1, on ? x + 15 : x + 7, y + 7, on ? 0xFFFFFFFF : 0xFFC9C9D4);
    }

    private void text(String s, int x, int y, int color) {
        f.draw(s, x, y, color);
    }

    private void centred(String s, int x, int y, int w, int h, int color) {
        f.draw(s, x + (w - f.width(s)) / 2, y + (h - 7) / 2, color);
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

    /** Rows in the options list: "Show background" first for HUD mods, then the mod's own settings. */
    private int extraRows() {
        return selected instanceof HudModule ? 1 : 0;
    }

    private int rowCount() {
        return extraRows() + selected.settings.size();
    }

    private static int maxScroll(int content, int view, int step) {
        return (Math.max(0, content - view) + step - 1) / step * step;
    }

    private void clampScroll() {
        railScroll = Math.max(0, Math.min(railScroll, maxScroll(rail.size() * RAIL_ROW, railH, RAIL_ROW)));
        if (selected != null) listScroll = Math.max(0, Math.min(listScroll, maxScroll(rowCount() * ROW, bodyH, ROW)));
    }

    private int btnX() {
        return (width - MODS_W - 2 * (BTN_H + 4)) / 2;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(0, 0, width, height, 0x48000000);
        drawHud(mouseX, mouseY);

        // Name: "Client" in white, "M" in the accent colour
        int half = logo.width("ClientM") / 2;
        logo.drawShadowed("Client", width / 2 - half, logoY + 2, TEXT);
        logo.drawShadowed("M", width / 2 - half + logo.width("Client"), logoY + 2, 0xFF4D5E);

        drawButtons(mouseX, mouseY);
        drawChips(mouseX, mouseY);
        f.drawShadowed(HINT, (width - f.width(HINT)) / 2, height - 11, TEXT_2);

        if (!panelOpen) return;
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 120f);
        GL11.glPushMatrix();
        GL11.glTranslatef(0f, (1f - t) * (1f - t) * 8f, 0f);
        panel(sx, sy, sw, sh);
        Gui.drawRect(sx + RAIL_W, sy + 1, sx + RAIL_W + 1, sy + sh - 1, LINE);
        drawRail(mouseX, mouseY);
        drawOptions(mouseX, mouseY);
        GL11.glPopMatrix();
    }

    private void drawHud(int mouseX, int mouseY) {
        boolean overPanel = panelOpen && in(mouseX, mouseY, sx, sy, sw, sh);
        for (HudModule m : ClientM.modules.hud()) {
            int x = hudX(m);
            int y = hudY(m);
            int w = hudW(m);
            int h = hudH(m);
            boolean empty = m.width() <= 0 || m.height() <= 0;

            GL11.glPushMatrix();
            GL11.glTranslatef(x, y, 0f);
            if (empty) {
                Gui.drawRect(0, 0, w, h, 0x6F000000);
                fontRendererObj.drawStringWithShadow(m.name, 3, 3, 0xFFFFFF);
            } else {
                m.render(fontRendererObj);
            }
            if (!m.enabled) Gui.drawRect(0, 0, w, h, 0xB0200000);
            GL11.glPopMatrix();

            boolean hover = !overPanel && in(mouseX, mouseY, x, y, w, h);
            int border = hover || m == dragging || m == selected ? ACCENT : 0x50FFFFFF;
            Gui.drawRect(x - 1, y - 1, x + w + 1, y, border);
            Gui.drawRect(x - 1, y + h, x + w + 1, y + h + 1, border);
            Gui.drawRect(x - 1, y, x, y + h, border);
            Gui.drawRect(x + w, y, x + w + 1, y + h, border);
            if (hover) {
                String label = m.enabled ? m.name : m.name + " (off)";
                fontRendererObj.drawStringWithShadow(label, x, y < 12 ? y + h + 3 : y - 10, 0xFF4D5E);
            }
        }
    }

    private void drawButtons(int mouseX, int mouseY) {
        int x = btnX();
        // Left: jump to the HUD mods (three bars)
        panel(x, btnY, BTN_H, BTN_H);
        if (in(mouseX, mouseY, x, btnY, BTN_H, BTN_H)) round(x, btnY, BTN_H, BTN_H, HOVER);
        for (int i = 0; i < 3; i++) Gui.drawRect(x + 5, btnY + 5 + i * 3, x + 13, btnY + 6 + i * 3, 0xFFA3A3B2);

        // Middle: show or hide the panel
        int mx = x + BTN_H + 4;
        round(mx, btnY, MODS_W, BTN_H, panelOpen ? ACCENT : LINE);
        if (!panelOpen) round(mx + 1, btnY + 1, MODS_W - 2, BTN_H - 2, BG);
        if (in(mouseX, mouseY, mx, btnY, MODS_W, BTN_H)) round(mx, btnY, MODS_W, BTN_H, HOVER);
        bold.draw("Mods", mx + (MODS_W - bold.width("Mods")) / 2, btnY + 6, panelOpen ? INK : TEXT);

        // Right: jump to the performance mods (two sliders)
        int gx = mx + MODS_W + 4;
        panel(gx, btnY, BTN_H, BTN_H);
        if (in(mouseX, mouseY, gx, btnY, BTN_H, BTN_H)) round(gx, btnY, BTN_H, BTN_H, HOVER);
        Gui.drawRect(gx + 5, btnY + 6, gx + 13, btnY + 7, 0xFFA3A3B2);
        Gui.drawRect(gx + 5, btnY + 11, gx + 13, btnY + 12, 0xFFA3A3B2);
        Gui.drawRect(gx + 10, btnY + 5, gx + 12, btnY + 8, 0xFFEDEDF2);
        Gui.drawRect(gx + 6, btnY + 10, gx + 8, btnY + 13, 0xFFEDEDF2);
    }

    private void drawChips(int mouseX, int mouseY) {
        for (int i = 0; i < presets.length; i++) {
            int x = chipX[i];
            int w = chipX[i + 1] - x - 3;
            boolean on = presets[i] == PerformancePreset.last;
            if (on) round(x, chipY, w, CHIP_H, PRESET);
            else panel(x, chipY, w, CHIP_H);
            if (in(mouseX, mouseY, x, chipY, w, CHIP_H)) round(x, chipY, w, CHIP_H, HOVER);
            centred(presets[i].label, x, chipY, w, CHIP_H, on ? INK : TEXT_2);
        }
    }

    private void drawRail(int mouseX, int mouseY) {
        int x = sx + 5;
        int w = RAIL_W - 9;
        for (int i = 0; i < rail.size(); i++) {
            int y = railY + i * RAIL_ROW - railScroll;
            if (y < railY || y + RAIL_ROW > railY + railH) continue;
            Object o = rail.get(i);
            if (o instanceof Category) {
                text(((Category) o).label, x + 2, y + 4, TEXT_3);
                continue;
            }
            Module m = (Module) o;
            boolean sel = m == selected;
            if (sel) round(x, y, w, RAIL_ROW - 1, ACCENT_TINT);
            else if (in(mouseX, mouseY, x, y, w, RAIL_ROW - 1)) round(x, y, w, RAIL_ROW - 1, HOVER);
            text(f.trim(m.name, w - 15), x + 5, y + 2, sel ? 0xFF4D5E : TEXT_2);
            if (m.toggleable()) Gui.drawRect(x + w - 7, y + 4, x + w - 4, y + 7, m.enabled ? ACCENT : SWITCH_OFF);
        }
        scrollbar(sx + RAIL_W - 3, railY, railH, rail.size() * RAIL_ROW, railScroll);
    }

    private void drawOptions(int mouseX, int mouseY) {
        int right = lx + lw;
        bold.draw(selected.name, lx + 2, sy + 9, TEXT);
        String d = selected.description();
        int nameW = bold.width(selected.name);
        if (d != null && lx + nameW + 10 + f.width(d) < right - 22) text(d, lx + nameW + 10, sy + 9, TEXT_3);
        if (selected.toggleable()) toggle(right - 22, sy + 9, selected.enabled);
        Gui.drawRect(lx, sy + 21, right, sy + 22, LINE);

        int extra = extraRows();
        int rows = rowCount();
        if (rows == 0) text("No options", lx + 6, bodyY + 5, TEXT_3);
        for (int i = 0; i < rows; i++) {
            int y = bodyY + i * ROW - listScroll;
            if (y < bodyY || y + ROW > bodyY + bodyH) continue;
            boolean hover = in(mouseX, mouseY, lx, y, lw, ROW - 1);
            if (hover) round(lx, y, lw, ROW - 1, HOVER);
            else if ((i & 1) == 1) round(lx, y, lw, ROW - 1, ROW_BG);

            if (i < extra) {
                text("Show background", lx + 6, y + 4, TEXT);
                toggle(right - 22, y + 4, ((HudModule) selected).background);
                continue;
            }
            Setting s = selected.settings.get(i - extra);
            if (s instanceof BoolSetting) {
                text(s.name, lx + 6, y + 4, TEXT);
                toggle(right - 22, y + 4, ((BoolSetting) s).value);
            } else if (s instanceof IntSetting) {
                IntSetting n = (IntSetting) s;
                text(n.name, lx + 6, y + 4, TEXT);
                int x = right - 6 - SLIDER_W;
                int filled = n.max == n.min ? 0 : (n.value - n.min) * SLIDER_W / (n.max - n.min);
                String v = Integer.toString(n.value);
                text(v, x - 6 - f.width(v), y + 4, TEXT_2);
                Gui.drawRect(x, y + 7, x + SLIDER_W, y + 9, SWITCH_OFF);
                Gui.drawRect(x, y + 7, x + filled, y + 9, ACCENT);
                Gui.drawRect(x + filled - 2, y + 4, x + filled + 2, y + 12, 0xFFFFFFFF);
            } else if (s instanceof ActionSetting) {
                ActionSetting a = (ActionSetting) s;
                int bw = f.width(a.button) + 12;
                text(f.trim(a.name, lw - bw - 16), lx + 6, y + 4, TEXT);
                round(right - 6 - bw, y + 2, bw, 12, hover && mouseX >= right - 6 - bw ? ACCENT : SWITCH_OFF);
                centred(a.button, right - 6 - bw, y + 2, bw, 12, TEXT);
            }
        }
        scrollbar(sx + sw - 6, bodyY, bodyH, rows * ROW, listScroll);
    }

    private static void scrollbar(int x, int y, int h, int content, int scroll) {
        if (content <= h) return;
        int thumb = Math.max(10, h * h / content);
        int ty = y + Math.min(h - thumb, (h - thumb) * scroll / Math.max(1, content - h));
        Gui.drawRect(x, ty, x + 2, ty + thumb, ACCENT);
    }

    // ---------------------------------------------------------------- input

    private void slide(IntSetting s, int mouseX) {
        int x = lx + lw - 6 - SLIDER_W;
        float frac = Math.max(0f, Math.min(1f, (mouseX - x) / (float) SLIDER_W));
        s.set(s.min + Math.round(frac * (s.max - s.min)));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && clickChrome(mouseX, mouseY)) return;
        if (panelOpen && in(mouseX, mouseY, sx, sy, sw, sh)) {
            if (button == 0) clickPanel(mouseX, mouseY);
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

    /** The three centre buttons and the preset chips. */
    private boolean clickChrome(int mouseX, int mouseY) {
        int x = btnX();
        if (in(mouseX, mouseY, x, btnY, BTN_H, BTN_H)) {
            showMods(Category.HUD);
            return true;
        }
        if (in(mouseX, mouseY, x + BTN_H + 4, btnY, MODS_W, BTN_H)) {
            panelOpen = !panelOpen;
            openedAt = System.currentTimeMillis();
            return true;
        }
        if (in(mouseX, mouseY, x + BTN_H + 8 + MODS_W, btnY, BTN_H, BTN_H)) {
            showMods(Category.PERFORMANCE);
            return true;
        }
        for (int i = 0; i < presets.length; i++) {
            if (in(mouseX, mouseY, chipX[i], chipY, chipX[i + 1] - chipX[i] - 3, CHIP_H)) {
                presets[i].apply();
                return true;
            }
        }
        return false;
    }

    private void clickPanel(int mouseX, int mouseY) {
        if (mouseX < sx + RAIL_W) {
            int i = (mouseY - railY + railScroll) / RAIL_ROW;
            if (mouseY >= railY && mouseY < railY + railH && i >= 0 && i < rail.size()) {
                Object o = rail.get(i);
                if (o instanceof Module) {
                    Module m = (Module) o;
                    // The dot at the end of a row switches the mod on or off without opening it.
                    if (m.toggleable() && mouseX >= sx + RAIL_W - 14) m.setEnabled(!m.enabled);
                    else select(m);
                }
            }
            return;
        }
        int right = lx + lw;
        if (mouseY < sy + 21) {
            if (selected.toggleable() && mouseX >= right - 28) selected.setEnabled(!selected.enabled);
            return;
        }
        int i = (mouseY - bodyY + listScroll) / ROW;
        int y = bodyY + i * ROW - listScroll;
        if (mouseY < bodyY || i >= rowCount() || y < bodyY || y + ROW > bodyY + bodyH) return;
        int extra = extraRows();
        if (i < extra) {
            HudModule h = (HudModule) selected;
            h.background = !h.background;
            return;
        }
        Setting s = selected.settings.get(i - extra);
        if (s instanceof BoolSetting) {
            // The whole row is the click target, not only the switch.
            ((BoolSetting) s).value = !((BoolSetting) s).value;
            selected.onSettingsChanged();
        } else if (s instanceof IntSetting && mouseX >= right - 10 - SLIDER_W) {
            sliding = (IntSetting) s;
            slide(sliding, mouseX);
        } else if (s instanceof ActionSetting) {
            ActionSetting a = (ActionSetting) s;
            if (mouseX >= right - 18 - f.width(a.button)) a.action.run();
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
            selected.onSettingsChanged();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || !panelOpen) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        if (mouseX < sx + RAIL_W) railScroll += wheel > 0 ? -RAIL_ROW : RAIL_ROW;
        else listScroll += wheel > 0 ? -ROW : ROW;
        clampScroll();
    }
}
