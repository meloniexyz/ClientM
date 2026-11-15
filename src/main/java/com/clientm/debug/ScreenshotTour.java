package com.clientm.debug;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.storage.SaveFormatComparator;

import com.clientm.ClientM;
import com.clientm.compat.PerformancePreset;
import com.clientm.gui.ClientScreen;
import com.clientm.module.Category;
import com.clientm.module.HudModule;
import com.clientm.module.Module;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Developer tool, only active when the CLIENTM_TOUR environment variable is set. It loads the first saved world,
 * walks through every ClientM screen, saves a screenshot of each into the screenshots folder, then closes the game.
 */
public final class ScreenshotTour {

    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean launched;
    private int step;
    private int wait = 140;
    private ClientScreen screen;

    public static boolean requested() {
        return System.getenv("CLIENTM_TOUR") != null;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!launched) {
            if (mc.currentScreen instanceof GuiMainMenu) launchFirstWorld();
            return;
        }
        if (mc.thePlayer == null || wait-- > 0) return;
        try {
            advance();
        } catch (Throwable t) {
            ClientM.LOG.error("Screenshot tour failed at step {}", step, t);
            mc.shutdown();
        }
    }

    private void launchFirstWorld() {
        launched = true;
        mc.gameSettings.pauseOnLostFocus = false;
        try {
            List<?> saves = mc.getSaveLoader()
                .getSaveList();
            if (saves.isEmpty()) {
                ClientM.LOG.error("Screenshot tour: no saved world to load");
                mc.shutdown();
                return;
            }
            SaveFormatComparator save = (SaveFormatComparator) saves.get(0);
            mc.launchIntegratedServer(save.getFileName(), save.getDisplayName(), null);
        } catch (Throwable t) {
            ClientM.LOG.error("Screenshot tour: could not load a world", t);
            mc.shutdown();
        }
    }

    private void shot(String name) {
        ScreenShotHelper.saveScreenshot(
            mc.mcDataDir,
            "clientm-" + name + ".png",
            mc.displayWidth,
            mc.displayHeight,
            mc.getFramebuffer());
    }

    private Module module(String id) {
        for (Module m : ClientM.modules.all()) if (m.id.equals(id)) return m;
        throw new IllegalStateException("No module " + id);
    }

    /** CLIENTM_TOUR=diag: photograph the HUD with HUD caching and textured backgrounds each on and off. */
    private void diagnose() {
        wait = 50;
        int n = step++;
        if (n == 0) {
            if (mc.getIntegratedServer() != null) mc.getIntegratedServer().worldServers[0].setWorldTime(6000L);
            for (HudModule h : ClientM.modules.hud()) h.setEnabled(true);
        }
        if (n >= 1 && n <= 4)
            shot("diag-cache" + ((n - 1) / 2 == 0 ? "On" : "Off") + "-textured" + ((n - 1) % 2 == 0 ? "On" : "Off"));
        if (n >= 4) {
            mc.shutdown();
            return;
        }
        module("hudcaching").setEnabled(n / 2 == 0);
        module("texturedbg").setEnabled(n % 2 == 0);
    }

    private void advance() {
        if ("diag".equals(System.getenv("CLIENTM_TOUR"))) {
            diagnose();
            return;
        }
        wait = 12;
        switch (step++) {
            case 0:
                // Daylight, a mid preset and every HUD module on, so the pictures show something.
                if (mc.getIntegratedServer() != null) mc.getIntegratedServer().worldServers[0].setWorldTime(6000L);
                PerformancePreset.BALANCED.apply();
                for (HudModule h : ClientM.modules.hud()) h.setEnabled(true);
                wait = 160;
                break;
            case 1:
                shot("01-hud");
                screen = new ClientScreen();
                mc.displayGuiScreen(screen);
                break;
            case 2:
                shot("02-hud-editor");
                screen.showMods(Category.HUD);
                break;
            case 3:
                shot("03-mods-hud");
                screen.showMods(Category.VISUAL);
                break;
            case 4:
                shot("04-mods-visual");
                screen.showMods(Category.MOVEMENT);
                break;
            case 5:
                shot("05-mods-movement");
                screen.showMods(Category.PERFORMANCE);
                break;
            case 6:
                shot("06-mods-performance");
                screen.showSettings(module("glint"));
                break;
            case 7:
                shot("07-settings-glint");
                screen.showSettings(module("performance"));
                break;
            case 8:
                shot("08-settings-presets");
                screen.showSettings(module("chunks"));
                break;
            case 9:
                shot("09-settings-chunks");
                screen.showSettings(module("hudcaching"));
                break;
            case 10:
                shot("10-settings-hudcaching");
                break;
            default:
                ClientM.LOG.info("Screenshot tour finished");
                mc.shutdown();
        }
    }
}
