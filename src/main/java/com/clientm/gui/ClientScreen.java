package com.clientm.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.clientm.ClientM;
import com.clientm.compat.PerformancePreset;
import com.clientm.module.Category;
import com.clientm.module.HudModule;
import com.clientm.module.Module;
import com.clientm.setting.ActionSetting;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.ChoiceSetting;
import com.clientm.setting.IntSetting;
import com.clientm.setting.Setting;

/**
 * The whole ClientM interface in one screen ("Glass" layout). The game stays visible behind a see-through panel: on
 * the left the name, the categories and two links; on the right a grid of mod tiles with a search box. A tile's switch
 * turns the mod on or off, anywhere else on the tile opens its options in the same place (with a way back). "Edit HUD
 * layout" hides the panel so the HUD boxes can be dragged; every HUD box can be dragged whenever the panel is not over
 * it.
 */
public class ClientScreen extends GuiScreen {

    // ClientM's colours: black, the red accent and the purple of the presets
    private static final int GLASS = 0xF20B0B0E;
    private static final int EDGE = 0x1CFFFFFF;
    private static final int SPLIT = 0x12FFFFFF;
    private static final int TILE = 0xFF15151B;
    private static final int TILE_EDGE = 0x12FFFFFF;
    private static final int HOVER = 0x14FFFFFF;
    private static final int ROW_BG = 0x09FFFFFF;
    private static final int TEXT = 0xEDEDF2;
    private static final int TEXT_2 = 0xA3A3B2;
    private static final int TEXT_3 = 0x6C6C7C;
    private static final int ACCENT = 0xFFFF4D5E;
    private static final int PURPLE = 0xFFA99DFF;
    private static final int PURPLE_TINT = 0x29A99DFF;
    private static final int INK = 0x0C0A18;
    private static final int SWITCH_OFF = 0xFF2E2E38;

    private static final int RAIL_W = 92;
    private static final int CAT_ROW = 15;
    private static final int TILE_H = 46;
    private static final int GAP = 5;
    private static final int ROW = 17;
    private static final int SLIDER_W = 84;
    private static final int MIN_W = 60;
    private static final int MIN_H = 14;

    private static final String HUD_HINT = "Drag a HUD box to move it   Right-click: on/off   Middle-click: background";

    /** What the right side shows. */
    private enum View {
        GRID,
        OPTIONS,
        PRESETS
    }

    // Remembered between openings
    private static Category category; // null: all mods
    private static View view = View.GRID;
    private static String selectedId = "fps";
    private static boolean panelOpen = true;

    private Module selected;
    private long openedAt;
    private int gridScroll, listScroll;
    private String search = "";
    private boolean searchFocus;

    private final List<Module> shown = new ArrayList<>();
    private final PerformancePreset[] presets = PerformancePreset.values();

    // Smooth menu text; the HUD boxes keep Minecraft's font
    private SmoothFont f, bold, logo, big;

    private HudModule dragging;
    private int dragOffX, dragOffY;
    private IntSetting sliding;

    // Geometry, recomputed in initGui
    private int sx, sy, sw, sh, mx, mw, gridY, gridH, cols, tileW, bodyY, bodyH;

    @Override
    public void initGui() {
        ClientM.modules.editing = true;
        Keyboard.enableRepeatEvents(true);
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        f = SmoothFont.get(scale, 9, false);
        bold = SmoothFont.get(scale, 9, true);
        big = SmoothFont.get(scale, 12, true);
        logo = SmoothFont.get(scale, 16, true);
        sw = Math.min(470, width - 20);
        sh = Math.min(270, height - 30);
        sx = (width - sw) / 2;
        sy = (height - sh) / 2;
        mx = sx + RAIL_W + 10;
        mw = sx + sw - 10 - mx;
        gridY = sy + 34;
        gridH = sy + sh - 10 - gridY;
        cols = Math.max(2, Math.min(4, (mw + GAP) / (96 + GAP)));
        tileW = (mw - (cols - 1) * GAP) / cols;
        bodyY = sy + 50;
        bodyH = sy + sh - 10 - bodyY;
        if (selected == null) {
            for (Module m : ClientM.modules.all()) if (m.id.equals(selectedId)) selected = m;
            if (selected == null) selected = ClientM.modules.all()
                .get(0);
        }
        if (openedAt == 0) openedAt = System.currentTimeMillis();
        filter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        ClientM.modules.editing = false;
        ClientM.modules.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** The tiles to show: the picked category (or all), narrowed by the search text. */
    private void filter() {
        shown.clear();
        String q = search.trim()
            .toLowerCase(Locale.ROOT);
        for (Module m : ClientM.modules.all()) {
            if (category != null && m.category != category && q.isEmpty()) continue;
            if (!q.isEmpty() && !m.name.toLowerCase(Locale.ROOT)
                .contains(q)) continue;
            shown.add(m);
        }
        clampScroll();
    }

    private static int count(Category c) {
        int n = 0;
        for (Module m : ClientM.modules.all()) if (c == null || m.category == c) n++;
        return n;
    }

    private void select(Module m) {
        selected = m;
        selectedId = m.id;
        listScroll = 0;
        sliding = null;
        view = View.OPTIONS;
        panelOpen = true;
    }

    /** Opens the panel on one category's tiles. */
    public void showMods(Category c) {
        category = c;
        view = View.GRID;
        panelOpen = true;
        gridScroll = 0;
        search = "";
        if (f != null) filter();
    }

    /** Opens the panel on one mod's options. */
    public void showSettings(Module module) {
        category = module.category;
        select(module);
        if (f != null) filter();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** A rectangle with rounded corners (radius r of 1..3 pixels), made of non-overlapping rectangles. */
    private static void round(int x, int y, int w, int h, int r, int color) {
        int x2 = x + w;
        int y2 = y + h;
        Gui.drawRect(x + r, y, x2 - r, y2, color);
        for (int i = 0; i < r; i++) {
            // Each column next to the middle is one pixel shorter at each end the nearer it is to the corner.
            int inset = r - i == 1 ? 1 : r - i;
            if (r >= 3 && i == r - 1) inset = 1;
            Gui.drawRect(x + i, y + inset, x + i + 1, y2 - inset, color);
            Gui.drawRect(x2 - i - 1, y + inset, x2 - i, y2 - inset, color);
        }
    }

    /**
     * A rounded box with a one-pixel border. The border is drawn as thin edges, not as a filled shape under the fill,
     * so a see-through fill does not show the border colour through it.
     */
    private static void outlined(int x, int y, int w, int h, int r, int edge, int fill) {
        round(x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), fill);
        Gui.drawRect(x + r, y, x + w - r, y + 1, edge);
        Gui.drawRect(x + r, y + h - 1, x + w - r, y + h, edge);
        Gui.drawRect(x, y + r, x + 1, y + h - r, edge);
        Gui.drawRect(x + w - 1, y + r, x + w, y + h - r, edge);
        if (r > 1) {
            // corner pixels
            Gui.drawRect(x + 1, y + 1, x + 2, y + 2, edge);
            Gui.drawRect(x + w - 2, y + 1, x + w - 1, y + 2, edge);
            Gui.drawRect(x + 1, y + h - 2, x + 2, y + h - 1, edge);
            Gui.drawRect(x + w - 2, y + h - 2, x + w - 1, y + h - 1, edge);
        }
    }

    private static void toggle(int x, int y, boolean on) {
        round(x, y, 18, 9, 3, on ? ACCENT : SWITCH_OFF);
        int kx = on ? x + 10 : x + 2;
        round(kx, y + 2, 6, 5, 2, on ? 0xFFFFFFFF : 0xFF8A8A96);
    }

    /** A small gear: an outlined square with notches, enough to read as "options" at this size. */
    private static void gear(int x, int y, int color) {
        Gui.drawRect(x + 1, y + 1, x + 6, y + 2, color);
        Gui.drawRect(x + 1, y + 5, x + 6, y + 6, color);
        Gui.drawRect(x + 1, y + 2, x + 2, y + 5, color);
        Gui.drawRect(x + 5, y + 2, x + 6, y + 5, color);
        Gui.drawRect(x + 3, y, x + 4, y + 1, color);
        Gui.drawRect(x + 3, y + 6, x + 4, y + 7, color);
        Gui.drawRect(x, y + 3, x + 1, y + 4, color);
        Gui.drawRect(x + 6, y + 3, x + 7, y + 4, color);
    }

    private void text(String s, int x, int y, int color) {
        f.draw(s, x, y, color);
    }

    private void centred(String s, int x, int y, int w, int h, int color) {
        f.draw(s, x + (w - f.width(s)) / 2, y + (h - 7) / 2, color);
    }

    private static int hudW(HudModule m) {
        return Math.max(m.scaledWidth(), MIN_W);
    }

    private static int hudH(HudModule m) {
        return Math.max(m.scaledHeight(), MIN_H);
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

    private int gridContent() {
        int rows = (shown.size() + cols - 1) / cols;
        return rows * (TILE_H + GAP) - GAP;
    }

    private static int maxScroll(int content, int view) {
        return Math.max(0, content - view);
    }

    private void clampScroll() {
        gridScroll = Math.max(0, Math.min(gridScroll, maxScroll(gridContent(), gridH)));
        if (selected != null) listScroll = Math.max(0, Math.min(listScroll, maxScroll(rowCount() * ROW, bodyH)));
    }

    /** The "Mods" button shown while the panel is hidden for HUD editing. */
    private int reopenX() {
        return (width - 60) / 2;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(0, 0, width, height, panelOpen ? 0x58000000 : 0x30000000);
        drawHud(mouseX, mouseY);

        if (!panelOpen) {
            // HUD editing: a button to bring the panel back, and how dragging works
            int bx = reopenX();
            round(bx, 6, 60, 16, 3, in(mouseX, mouseY, bx, 6, 60, 16) ? 0xFFFF6B79 : ACCENT);
            bold.draw("Mods", bx + (60 - bold.width("Mods")) / 2, 11, INK);
            f.drawShadowed(HUD_HINT, (width - f.width(HUD_HINT)) / 2, height - 11, TEXT_2);
            return;
        }
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 140f);
        GL11.glPushMatrix();
        GL11.glTranslatef(0f, (1f - t) * (1f - t) * 8f, 0f);
        outlined(sx, sy, sw, sh, 3, EDGE, GLASS);
        Gui.drawRect(sx + RAIL_W, sy + 1, sx + RAIL_W + 1, sy + sh - 1, SPLIT);
        drawRail(mouseX, mouseY);
        if (view == View.GRID) drawGrid(mouseX, mouseY);
        else if (view == View.OPTIONS) drawOptions(mouseX, mouseY);
        else drawPresets(mouseX, mouseY);
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
                GL11.glPushMatrix();
                GL11.glScalef(m.scale(), m.scale(), 1f);
                m.render(fontRendererObj);
                GL11.glPopMatrix();
            }
            if (!m.enabled) Gui.drawRect(0, 0, w, h, 0xB0200000);
            GL11.glPopMatrix();

            boolean hover = !overPanel && in(mouseX, mouseY, x, y, w, h);
            int border = hover || m == dragging ? ACCENT : 0x50FFFFFF;
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

    /** The categories the rail lists: null stands for "All mods". */
    private static Category[] railCategories() {
        Category[] all = Category.values();
        Category[] out = new Category[all.length + 1];
        System.arraycopy(all, 0, out, 1, all.length);
        return out;
    }

    private int catY(int i) {
        return sy + 34 + i * CAT_ROW;
    }

    private int linkY(int i) {
        return sy + sh - 30 + i * 13;
    }

    private void drawRail(int mouseX, int mouseY) {
        // Name: "Client" in white, "M" in the accent colour
        int lx = sx + 10;
        logo.drawShadowed("Client", lx, sy + 11, TEXT);
        logo.drawShadowed("M", lx + logo.width("Client"), sy + 11, 0xFF4D5E);

        Category[] cats = railCategories();
        int x = sx + 6;
        int w = RAIL_W - 12;
        for (int i = 0; i < cats.length; i++) {
            int y = catY(i);
            Category c = cats[i];
            boolean on = view != View.PRESETS && search.isEmpty() && c == category;
            if (on) {
                round(x, y, w, CAT_ROW - 2, 2, PURPLE_TINT);
                Gui.drawRect(x, y + 1, x + 2, y + CAT_ROW - 3, PURPLE);
            } else if (in(mouseX, mouseY, x, y, w, CAT_ROW - 2)) round(x, y, w, CAT_ROW - 2, 2, HOVER);
            (on ? bold : f).draw(c == null ? "All mods" : c.label, x + 7, y + 3, on ? TEXT : TEXT_2);
            String n = Integer.toString(count(c));
            text(n, x + w - 5 - f.width(n), y + 3, TEXT_3);
        }

        String[] links = { "Edit HUD layout", "Video presets" };
        for (int i = 0; i < links.length; i++) {
            int y = linkY(i);
            boolean on = i == 1 && view == View.PRESETS;
            boolean hover = in(mouseX, mouseY, x, y - 2, w, 12);
            text(links[i], x + 4, y, on ? PURPLE : hover ? TEXT : TEXT_3);
        }
    }

    private void drawSearch(int mouseX, int mouseY) {
        int w = Math.min(120, mw / 2);
        int x = mx + mw - w;
        int y = sy + 10;
        outlined(x, y, w, 15, 3, searchFocus ? PURPLE : 0x30FFFFFF, searchFocus ? 0xFF1C1C24 : 0xFF17171D);
        boolean blink = searchFocus && (System.currentTimeMillis() / 500) % 2 == 0;
        if (search.isEmpty() && !searchFocus) text("Search mods...", x + 7, y + 4, TEXT_3);
        else text(f.trim(search, w - 16) + (blink ? "_" : ""), x + 7, y + 4, TEXT);
    }

    private String title() {
        if (!search.isEmpty()) return "Search";
        return category == null ? "All mods" : category.label;
    }

    private void drawGrid(int mouseX, int mouseY) {
        big.draw(title(), mx, sy + 12, TEXT);
        drawSearch(mouseX, mouseY);
        if (shown.isEmpty()) text("No mods match \"" + search + "\"", mx + 2, gridY + 4, TEXT_3);

        // Tiles outside the grid area are skipped; ones cut by its edge are clipped with a scissor.
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int s = res.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(mx * s, mc.displayHeight - (gridY + gridH) * s, (mw + 2) * s, gridH * s);
        boolean inGrid = in(mouseX, mouseY, mx, gridY, mw, gridH);
        for (int i = 0; i < shown.size(); i++) {
            Module m = shown.get(i);
            int x = mx + (i % cols) * (tileW + GAP);
            int y = gridY + (i / cols) * (TILE_H + GAP) - gridScroll;
            if (y + TILE_H < gridY || y > gridY + gridH) continue;
            boolean hover = inGrid && in(mouseX, mouseY, x, y, tileW, TILE_H);
            boolean sel = m == selected;
            outlined(x, y, tileW, TILE_H, 3, sel ? PURPLE : hover ? 0x30FFFFFF : TILE_EDGE, hover ? 0xFF1C1C24 : TILE);
            bold.draw(bold.trim(m.name, tileW - 14), x + 7, y + 7, TEXT);
            String d = m.description();
            if (d != null) text(f.trim(d, tileW - 14), x + 7, y + 18, TEXT_3);
            if (m.toggleable()) toggle(x + 7, y + TILE_H - 15, m.enabled);
            else text("Open", x + 7, y + TILE_H - 14, PURPLE);
            boolean gearHover = hover && mouseX >= x + tileW - 18 && mouseY >= y + TILE_H - 18;
            gear(x + tileW - 15, y + TILE_H - 14, gearHover ? 0xFFEDEDF2 : 0xFF6C6C7C);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        scrollbar(sx + sw - 5, gridY, gridH, gridContent(), gridScroll);
    }

    private void drawOptions(int mouseX, int mouseY) {
        int right = mx + mw;
        // Back to the tiles
        boolean backHover = in(mouseX, mouseY, mx, sy + 9, 14, 16);
        outlined(mx, sy + 9, 14, 16, 3, backHover ? PURPLE : TILE_EDGE, 0xFF121217);
        text("<", mx + 5, sy + 13, backHover ? TEXT : TEXT_2);
        big.draw(selected.name, mx + 22, sy + 12, TEXT);
        if (selected.toggleable()) toggle(right - 20, sy + 13, selected.enabled);
        String d = selected.description();
        if (d != null) text(f.trim(d, mw - 24), mx + 22, sy + 30, TEXT_3);
        Gui.drawRect(mx, bodyY - 5, right, bodyY - 4, SPLIT);

        int extra = extraRows();
        int rows = rowCount();
        int lw = mw;
        if (rows == 0) text("No options", mx + 6, bodyY + 5, TEXT_3);
        for (int i = 0; i < rows; i++) {
            int y = bodyY + i * ROW - listScroll;
            if (y < bodyY || y + ROW > bodyY + bodyH) continue;
            boolean hover = in(mouseX, mouseY, mx, y, lw, ROW - 1);
            if (hover) round(mx, y, lw, ROW - 1, 2, HOVER);
            else if ((i & 1) == 1) round(mx, y, lw, ROW - 1, 2, ROW_BG);

            if (i < extra) {
                text("Show background", mx + 6, y + 5, TEXT);
                toggle(right - 24, y + 4, ((HudModule) selected).background);
                continue;
            }
            Setting s = selected.settings.get(i - extra);
            if (s instanceof BoolSetting) {
                text(f.trim(s.name, lw - 40), mx + 6, y + 5, TEXT);
                toggle(right - 24, y + 4, ((BoolSetting) s).value);
            } else if (s instanceof ChoiceSetting) {
                // "< Label >" selector
                ChoiceSetting c = (ChoiceSetting) s;
                text(f.trim(c.name, lw - SLIDER_W - 20), mx + 6, y + 5, TEXT);
                int x = right - 6 - SLIDER_W;
                round(x, y + 2, SLIDER_W, 12, 2, SWITCH_OFF);
                text("<", x + 5, y + 4, TEXT_2);
                text(">", x + SLIDER_W - 5 - f.width(">"), y + 4, TEXT_2);
                centred(c.label(), x, y + 2, SLIDER_W, 12, TEXT);
            } else if (s instanceof IntSetting) {
                IntSetting n = (IntSetting) s;
                text(f.trim(n.name, lw - SLIDER_W - 40), mx + 6, y + 5, TEXT);
                int x = right - 6 - SLIDER_W;
                int filled = n.max == n.min ? 0 : (n.value - n.min) * SLIDER_W / (n.max - n.min);
                String v = Integer.toString(n.value);
                text(v, x - 6 - f.width(v), y + 5, TEXT_2);
                Gui.drawRect(x, y + 7, x + SLIDER_W, y + 9, SWITCH_OFF);
                Gui.drawRect(x, y + 7, x + filled, y + 9, ACCENT);
                round(x + filled - 2, y + 4, 5, 8, 1, 0xFFFFFFFF);
            } else if (s instanceof ActionSetting) {
                ActionSetting a = (ActionSetting) s;
                int bw = f.width(a.button) + 12;
                text(f.trim(a.name, lw - bw - 16), mx + 6, y + 5, TEXT);
                round(right - 6 - bw, y + 2, bw, 12, 2, hover && mouseX >= right - 6 - bw ? ACCENT : SWITCH_OFF);
                centred(a.button, right - 6 - bw, y + 2, bw, 12, TEXT);
            }
        }
        scrollbar(sx + sw - 5, bodyY, bodyH, rows * ROW, listScroll);
    }

    private int presetY(int i) {
        return sy + 50 + i * 22;
    }

    private void drawPresets(int mouseX, int mouseY) {
        big.draw("Video presets", mx, sy + 12, TEXT);
        text("One click sets Minecraft's and Angelica's video options.", mx, sy + 30, TEXT_3);
        for (int i = 0; i < presets.length; i++) {
            int y = presetY(i);
            boolean on = presets[i] == PerformancePreset.last;
            boolean hover = in(mouseX, mouseY, mx, y, mw, 18);
            if (on) round(mx, y, mw, 18, 3, PURPLE);
            else outlined(mx, y, mw, 18, 3, hover ? 0x30FFFFFF : TILE_EDGE, hover ? 0xFF1C1C24 : TILE);
            bold.draw(presets[i].label, mx + 9, y + 6, on ? INK : TEXT);
        }
    }

    private static void scrollbar(int x, int y, int h, int content, int scroll) {
        if (content <= h) return;
        int thumb = Math.max(10, h * h / content);
        int ty = y + Math.min(h - thumb, (h - thumb) * scroll / Math.max(1, content - h));
        round(x, ty, 2, thumb, 1, PURPLE);
    }

    // ---------------------------------------------------------------- input

    private void slide(IntSetting s, int mouseX) {
        int x = mx + mw - 6 - SLIDER_W;
        float frac = Math.max(0f, Math.min(1f, (mouseX - x) / (float) SLIDER_W));
        s.set(s.min + Math.round(frac * (s.max - s.min)));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (!panelOpen) {
            if (button == 0 && in(mouseX, mouseY, reopenX(), 6, 60, 16)) {
                panelOpen = true;
                openedAt = System.currentTimeMillis();
                return;
            }
        } else if (in(mouseX, mouseY, sx, sy, sw, sh)) {
            if (button == 0) clickPanel(mouseX, mouseY);
            return;
        }
        searchFocus = false;
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

    private void clickPanel(int mouseX, int mouseY) {
        int searchW = Math.min(120, mw / 2);
        searchFocus = view == View.GRID && in(mouseX, mouseY, mx + mw - searchW, sy + 10, searchW, 15);
        if (searchFocus) return;

        if (mouseX < sx + RAIL_W) {
            Category[] cats = railCategories();
            for (int i = 0; i < cats.length; i++) {
                if (in(mouseX, mouseY, sx + 6, catY(i), RAIL_W - 12, CAT_ROW - 2)) {
                    showMods(cats[i]);
                    return;
                }
            }
            if (in(mouseX, mouseY, sx + 6, linkY(0) - 2, RAIL_W - 12, 12)) {
                panelOpen = false; // edit the HUD
            } else if (in(mouseX, mouseY, sx + 6, linkY(1) - 2, RAIL_W - 12, 12)) {
                view = View.PRESETS;
            }
            return;
        }
        if (view == View.GRID) clickGrid(mouseX, mouseY);
        else if (view == View.OPTIONS) clickOptions(mouseX, mouseY);
        else {
            for (int i = 0; i < presets.length; i++) if (in(mouseX, mouseY, mx, presetY(i), mw, 18)) presets[i].apply();
        }
    }

    private void clickGrid(int mouseX, int mouseY) {
        if (!in(mouseX, mouseY, mx, gridY, mw, gridH)) return;
        for (int i = 0; i < shown.size(); i++) {
            int x = mx + (i % cols) * (tileW + GAP);
            int y = gridY + (i / cols) * (TILE_H + GAP) - gridScroll;
            if (!in(mouseX, mouseY, x, y, tileW, TILE_H)) continue;
            Module m = shown.get(i);
            // The switch (bottom left) turns the mod on or off; anywhere else opens its options.
            if (m.toggleable() && in(mouseX, mouseY, x + 3, y + TILE_H - 19, 26, 16)) m.setEnabled(!m.enabled);
            else select(m);
            return;
        }
    }

    private void clickOptions(int mouseX, int mouseY) {
        int right = mx + mw;
        if (in(mouseX, mouseY, mx, sy + 9, 14, 16)) {
            view = View.GRID;
            return;
        }
        if (mouseY < bodyY - 4) {
            if (selected.toggleable() && in(mouseX, mouseY, right - 24, sy + 9, 26, 16))
                selected.setEnabled(!selected.enabled);
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
        } else if (s instanceof ChoiceSetting) {
            // Left half of the selector goes back, anywhere else on the row goes forward.
            ((ChoiceSetting) s).step(mouseX >= right - 6 - SLIDER_W && mouseX < right - 6 - SLIDER_W / 2 ? -1 : 1);
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
    protected void keyTyped(char c, int key) {
        if (searchFocus && view == View.GRID) {
            if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN) {
                searchFocus = false;
                return;
            }
            if (key == Keyboard.KEY_BACK) {
                if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            } else if (c >= ' ' && c != 127 && search.length() < 24) {
                search += c;
            }
            gridScroll = 0;
            filter();
            return;
        }
        if (key == Keyboard.KEY_ESCAPE && view != View.GRID && panelOpen) {
            view = View.GRID; // Esc goes back to the tiles first
            return;
        }
        super.keyTyped(c, key);
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
        if (view == View.GRID) gridScroll += wheel > 0 ? -(TILE_H + GAP) / 2 : (TILE_H + GAP) / 2;
        else if (view == View.OPTIONS) listScroll += wheel > 0 ? -ROW : ROW;
        clampScroll();
    }
}
