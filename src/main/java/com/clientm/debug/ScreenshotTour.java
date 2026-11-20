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
    private int menuTicks;
    private int step;
    private int wait = String.valueOf(System.getenv("CLIENTM_TOUR"))
        .startsWith("bench") ? 400 : 140;
    private ClientScreen screen;

    /**
     * CLIENTM_WINDOW_DISPLAY=x,y (a point on another monitor, in desktop coordinates): the test window is created there
     * and made fullscreen, so test runs use the whole screen without covering the main monitor.
     */
    private void toTestDisplay() {
        String where = System.getenv("CLIENTM_WINDOW_DISPLAY");
        if (where == null || mc.isFullScreen()) return;
        try {
            // Borderless, so clicking on the other monitor does not minimise the game.
            module("borderless").setEnabled(true);
            com.clientm.module.impl.BorderlessFullscreenModule.sync();
            // The window was already created on that monitor (com.clientm.vulkan.TestWindow); fullscreen stays there.
            mc.toggleFullscreen();
            ClientM.LOG.info("Test window moved to {} and made fullscreen", where);
        } catch (Throwable t) {
            ClientM.LOG.error("Could not move the test window", t);
        }
    }

    public static boolean requested() {
        return System.getenv("CLIENTM_TOUR") != null;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!launched && packsStarted && !packsDone) {
            packsMenu();
            return;
        }
        if (!launched) {
            if (mc.currentScreen instanceof GuiMainMenu || mc.currentScreen instanceof com.clientm.gui.ClientMainMenu) {
                if ("packs".equals(System.getenv("CLIENTM_TOUR")) && !packsDone) {
                    packsMenu();
                    return;
                }
                if ("accounts".equals(System.getenv("CLIENTM_TOUR"))) {
                    accountsTour();
                    return;
                }
                if (menuTicks == 0) {
                    toTestDisplay();
                    // Benchmark with VSync on: switched on at the menu, so the swapchain change is not measured.
                    if ("1".equals(System.getenv("CLIENTM_TEST_VSYNC"))) mc.gameSettings.enableVsync = true;
                }
                // Give the title screen a moment to draw, photograph it, then load the world.
                if (++menuTicks == 30) shot("00-main-menu");
                if (menuTicks > 34) launchFirstWorld();
            }
            return;
        }
        if (forceHurt && mc.thePlayer != null) mc.thePlayer.hurtTime = 8;
        if (tagged != null) { // the player may be up in the air: keep the test villager from falling away
            tagged.setPosition(tagX, tagY, tagZ);
            tagged.motionX = tagged.motionY = tagged.motionZ = 0;
        }
        if (mc.thePlayer == null || benching || wait-- > 0) return;
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

    /** CLIENTM_TOUR=accounts: the account list on the main menu, then a switch to the second account. */
    private void accountsTour() {
        com.clientm.gui.ClientMainMenu menu = mc.currentScreen instanceof com.clientm.gui.ClientMainMenu
            ? (com.clientm.gui.ClientMainMenu) mc.currentScreen
            : null;
        menuTicks++;
        if (menuTicks == 20) shot("accounts-0-closed");
        if (menuTicks == 24 && menu != null) menu.setAccountsOpen(true);
        if (menuTicks == 32) shot("accounts-1-open");
        if (menuTicks == 36) {
            java.util.List<com.clientm.gui.Accounts.Account> list = com.clientm.gui.Accounts.all();
            if (list.size() > 1) com.clientm.gui.Accounts.switchTo(list.get(1));
            if (menu != null) menu.setAccountsOpen(false);
        }
        if (menuTicks == 44) {
            shot("accounts-2-switched");
            com.clientm.ClientM.LOG.info(
                "Accounts tour: session is now {}",
                mc.getSession()
                    .getUsername());
        }
        if (menuTicks == 50) mc.shutdown();
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
            noonAndClear();
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

    // ---- CLIENTM_TOUR=bench: spin the camera once over 20 seconds and record every frame time.
    private boolean benching;
    /** CLIENTM_TOUR=benchfly: like bench, but flying through new terrain. */
    private static final boolean FLY = "benchfly".equals(System.getenv("CLIENTM_TOUR"));
    private double flyX, flyY, flyZ;
    private long benchStart, lastFrame;
    private boolean benchShot;
    private float baseYaw;
    private final int[] frameNanos = new int[400000];
    private int frames;

    // ---- CLIENTM_TOUR=features: hit marker (attacking real mobs), zoom, Ping & TPS and the custom crosshair.
    private long featureStart, lastAttack, featureQuitAt;
    private double homeX, homeY, homeZ;
    private float homeYaw, homePitch;
    private int attacks, featureStep;
    private boolean markerShot;
    private float zoomRatio;

    @SubscribeEvent
    public void onFeatureTest(TickEvent.RenderTickEvent event) {
        if ((featureStart == 0 && featureQuitAt == 0) || event.phase != TickEvent.Phase.END || mc.thePlayer == null)
            return;
        mc.inGameHasFocus = true;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        double secs = (System.nanoTime() - featureStart) / 1e9;
        if (secs > 3 && secs < 9 && System.nanoTime() - lastAttack > 600_000_000L) {
            net.minecraft.entity.Entity target = null;
            double best = 32 * 32;
            for (Object o : mc.theWorld.loadedEntityList) {
                if (!(o instanceof net.minecraft.entity.EntityLiving)) continue;
                net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) o;
                double d = e.getDistanceSqToEntity(mc.thePlayer);
                if (d < best) {
                    best = d;
                    target = e;
                }
            }
            if (target != null) {
                // Step up to it (they wander) and face it, as a player would.
                double dx = mc.thePlayer.posX - target.posX, dz = mc.thePlayer.posZ - target.posZ;
                double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                mc.thePlayer.setPosition(
                    target.posX + dx / len * 2,
                    target.posY + mc.thePlayer.yOffset,
                    target.posZ + dz / len * 2);
                mc.thePlayer.rotationYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + 180f;
                mc.thePlayer.rotationPitch = 10f;
                mc.thePlayer.swingItem();
                mc.playerController.attackEntity(mc.thePlayer, target);
                attacks++;
            }
            lastAttack = System.nanoTime();
        }
        if (!markerShot && com.clientm.module.impl.HitMarkerModule.shown > 0) {
            markerShot = true;
            shot("features-hitmarker");
        }
        int zoomKey = com.clientm.module.impl.ZoomModule.keyCode();
        if (secs > 9.5 && featureStep == 0) {
            featureStep = 1;
            net.minecraft.client.settings.KeyBinding.setKeyBindState(zoomKey, true);
        }
        if (secs > 10.5 && featureStep == 1) {
            featureStep = 2;
            zoomRatio = com.clientm.module.impl.ZoomModule.fov(70f) / 70f;
            shot("features-zoom");
        }
        if (secs > 11 && featureStep == 2) {
            featureStep = 3;
            net.minecraft.client.settings.KeyBinding.setKeyBindState(zoomKey, false);
        }
        if (secs > 12 && featureStep == 3) {
            featureStep = 4;
            float after = com.clientm.module.impl.ZoomModule.fov(70f) / 70f;
            String report = String.format(
                "attacks %d, hit markers shown %d%nzoom: fov x%.3f while held, x%.3f after release%nping & tps shows: %s%n",
                attacks,
                com.clientm.module.impl.HitMarkerModule.shown,
                zoomRatio,
                after,
                com.clientm.module.impl.ServerInfoModule.shownText());
            ClientM.LOG.info("Feature test:\n{}", report);
            try {
                java.nio.file.Files.write(
                    new java.io.File(mc.mcDataDir, "clientm-features.txt").toPath(),
                    report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                ClientM.LOG.error("Could not write the feature test result", e);
            }
            featureStart = 0;
            if (mc.getIntegratedServer() != null) {
                MobSpawner cleaner = new MobSpawner(0, 0, 0, 0);
                cleaner.clearFirst = true;
                cpw.mods.fml.common.FMLCommonHandler.instance()
                    .bus()
                    .register(cleaner);
            }
            mc.thePlayer.setPositionAndRotation(homeX, homeY, homeZ, homeYaw, homePitch);
            featureQuitAt = System.nanoTime() + 1_000_000_000L;
        }
        if (featureQuitAt != 0 && System.nanoTime() > featureQuitAt) {
            featureQuitAt = 0;
            mc.shutdown();
        }
    }

    // ---- CLIENTM_TOUR=clicks: clicks fed into the game's mouse queue (the real mouse is not touched), measuring the
    // time from each click to its swing, with Hit Fix's instant clicks on and then off.
    private boolean clicking;
    private int clickNo, clickPhase;
    private long clickAt, releaseAt, nextClickAt;
    private final long[][] clickDelays = new long[2][40];
    private final int[][] clickSwings = new int[2][40];

    @SubscribeEvent
    public void onClickTest(TickEvent.RenderTickEvent event) {
        if (!clicking || event.phase != TickEvent.Phase.END || mc.thePlayer == null) return;
        long now = System.nanoTime();
        mc.inGameHasFocus = true; // the test window may not have the keyboard focus; the game must act as if it had
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        com.clientm.module.Module hitfix = module("hitfix");
        if (clickAt != 0) {
            long swing = com.clientm.module.impl.HitFixModule.lastSwingNs;
            int n = com.clientm.module.impl.HitFixModule.swings;
            if (clickPhase < 2) {
                if (swing >= clickAt && clickDelays[clickPhase][clickNo] == 0)
                    clickDelays[clickPhase][clickNo] = swing - clickAt;
                clickSwings[clickPhase][clickNo] = n;
            }
            if (releaseAt == 0 && now - clickAt > 30_000_000L) {
                injectClick(false);
                releaseAt = now;
            }
            if (now - clickAt < 200_000_000L) return; // let the click finish and every tick see it
            clickAt = 0;
            releaseAt = 0;
            if (++clickNo == (clickPhase == 2 ? 20 : 40)) {
                clickNo = 0;
                if (++clickPhase == 3) {
                    clicking = false;
                    reportClicks();
                    mc.shutdown();
                    return;
                }
            }
            // Random gaps, so clicks land at every point between two game ticks.
            nextClickAt = now + 60_000_000L + (long) (Math.random() * 90_000_000L);
            return;
        }
        if (now < nextClickAt) return;
        setOption(hitfix, "instantAttack", clickPhase == 0);
        setOption(hitfix, "instantUse", clickPhase == 2);
        if (clickPhase == 2) {
            // Right-clicks with an empty hand: the action runs, and nothing is used up or held down.
            mc.thePlayer.inventory.currentItem = 8;
            clickButton = 1;
        }
        com.clientm.module.impl.HitFixModule.swings = 0;
        com.clientm.module.impl.HitFixModule.lastSwingNs = 0;
        clickAt = System.nanoTime();
        injectClick(true);
    }

    /** Puts a left-button event into lwjgl3ify's mouse queue, as if the button had been pressed or released. */
    private static int clickButton;

    private static void injectClick(boolean down) {
        try {
            Class<?> mouse = Class.forName("org.lwjglx.input.Mouse");
            mouse.getMethod("addButtonEvent", int.class, boolean.class)
                .invoke(null, clickButton, down);
            ClientM.LOG.info("CLICKDBG injected {} at {}", down ? "press" : "release", System.nanoTime());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The click test needs lwjgl3ify", e);
        }
    }

    private static void setOption(com.clientm.module.Module m, String id, boolean value) {
        for (com.clientm.setting.Setting s : m.settings)
            if (s.id.equals(id)) ((com.clientm.setting.BoolSetting) s).value = value;
    }

    private void reportClicks() {
        StringBuilder out = new StringBuilder();
        out.append(
            String.format(
                "instant right-clicks ON: 20 right-clicks, %d use actions, %d of them on the click's frame%n",
                com.clientm.module.impl.HitFixModule.uses,
                com.clientm.module.impl.HitFixModule.earlyUses));
        String[] names = { "instant clicks ON ", "instant clicks OFF" };
        for (int ph = 0; ph < 2; ph++) {
            long[] d = clickDelays[ph].clone();
            java.util.Arrays.sort(d);
            double sum = 0;
            int once = 0, missed = 0;
            for (int i = 0; i < 40; i++) {
                sum += d[i];
                if (clickSwings[ph][i] == 1) once++;
                if (clickDelays[ph][i] == 0) missed++;
            }
            out.append(
                String.format(
                    "%s: click to swing avg %.1f ms, median %.1f ms, max %.1f ms; exactly one swing %d/40, no swing %d%n",
                    names[ph],
                    sum / 40 / 1e6,
                    d[20] / 1e6,
                    d[39] / 1e6,
                    once,
                    missed));
        }
        ClientM.LOG.info("Click test:\n{}", out);
        try {
            java.nio.file.Files.write(
                new java.io.File(mc.mcDataDir, "clientm-clicks.txt").toPath(),
                out.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            ClientM.LOG.error("Could not write the click test result", e);
        }
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (!benching || event.phase != TickEvent.Phase.START || mc.thePlayer == null) return;
        long now = System.nanoTime();
        if (benchStart == 0) {
            benchStart = now;
            lastFrame = now;
            baseYaw = mc.thePlayer.rotationYaw;
            return;
        }
        if (frames < frameNanos.length) frameNanos[frames++] = (int) Math.min(Integer.MAX_VALUE, now - lastFrame);
        lastFrame = now;
        float secs = (now - benchStart) / 1e9f;
        // CLIENTM_BENCH_SHOT=seconds: one screenshot that far into the benchmark (to compare renderers).
        String shotAt = System.getenv("CLIENTM_BENCH_SHOT");
        if (shotAt != null && !benchShot && secs >= Float.parseFloat(shotAt)) {
            benchShot = true;
            shot("bench-" + shotAt + "s");
        }
        if (FLY) {
            // Fly out in a straight line for 10 seconds and back for 10, so new chunks load the whole time.
            if (flyY == 0) {
                flyX = mc.thePlayer.posX;
                flyZ = mc.thePlayer.posZ;
                flyY = 110;
            }
            double along = (secs < 10f ? secs : 20f - secs) * 28.0;
            mc.thePlayer.capabilities.isFlying = true;
            mc.thePlayer.motionX = mc.thePlayer.motionY = mc.thePlayer.motionZ = 0;
            mc.thePlayer.setPosition(flyX + along, flyY, flyZ);
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = secs < 10f ? -90f : 90f;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 15f;
            if (secs >= 20f) finishBench(secs);
            return;
        }
        // Four exchanges a second: the camera player swings and is hit, the fighters swing, half of them are hit.
        if (!fighters.isEmpty() && (int) (secs * 4f) != fightBeat) {
            fightBeat = (int) (secs * 4f);
            mc.thePlayer.inventory.mainInventory[mc.thePlayer.inventory.currentItem] = new net.minecraft.item.ItemStack(
                net.minecraft.init.Items.diamond_sword);
            // CLIENTM_FIGHT_OFF switches parts of the fight off to see what each one costs:
            // w swings, h hits on the camera player, e hits on fighters, p particles, s sounds.
            String off = System.getenv("CLIENTM_FIGHT_OFF");
            if (off == null) off = "";
            boolean swings = off.indexOf('w') < 0, sounds = off.indexOf('s') < 0;
            if (swings) mc.thePlayer.swingItem();
            if ((fightBeat & 1) == 0 && off.indexOf('h') < 0) {
                mc.thePlayer.hurtTime = mc.thePlayer.maxHurtTime = 10;
                mc.thePlayer.attackedAtYaw = 0f;
                if (sounds) mc.theWorld.playSound(
                    mc.thePlayer.posX,
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ,
                    "game.player.hurt",
                    1f,
                    1f,
                    false);
            }
            for (int i = 0; i < fighters.size(); i++) {
                net.minecraft.client.entity.EntityOtherPlayerMP f = fighters.get(i);
                if (swings) f.swingItem();
                if ((i + fightBeat) % 2 != 0) continue;
                if (off.indexOf('e') < 0) f.hurtTime = f.maxHurtTime = 10;
                if (off.indexOf('p') < 0) {
                    // What a critical hit with a Sharpness sword throws off.
                    mc.effectRenderer.addEffect(new net.minecraft.client.particle.EntityCrit2FX(mc.theWorld, f));
                    mc.effectRenderer
                        .addEffect(new net.minecraft.client.particle.EntityCrit2FX(mc.theWorld, f, "magicCrit"));
                }
                if (sounds) mc.theWorld.playSound(f.posX, f.posY, f.posZ, "game.player.hurt", 1f, 1f, false);
            }
        }
        // Only on request: saving a screenshot takes a fraction of a second, which would show up in the numbers.
        if (!fighters.isEmpty() && fightShot == 0 && secs > 3f && System.getenv("CLIENTM_FIGHT_SHOT") != null) {
            fightShot = 1;
            shot("fight-" + System.getenv("CLIENTM_BENCH_LABEL"));
        }
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = baseYaw + secs * 18f;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 10f;
        if (secs >= 20f) finishBench(secs);
    }

    private final java.util.List<net.minecraft.client.entity.EntityOtherPlayerMP> fighters = new java.util.ArrayList<>();
    private int fightShot;
    private int fightBeat = -1;

    /**
     * CLIENTM_TOUR=benchfight: the bench spin with eight players in enchanted diamond armour standing around the
     * camera, half of them flashing as if hit. They exist on the client only.
     */
    private void spawnFighters() {
        net.minecraft.item.Item[] kit = { net.minecraft.init.Items.diamond_sword,
            net.minecraft.init.Items.diamond_boots, net.minecraft.init.Items.diamond_leggings,
            net.minecraft.init.Items.diamond_chestplate, net.minecraft.init.Items.diamond_helmet };
        // CLIENTM_FIGHT_COUNT sets how many fighters; CLIENTM_FIGHT_KIT strips parts of them to see what each costs:
        // a no armour, i no held sword, g no enchant glint.
        String kitOff = System.getenv("CLIENTM_FIGHT_KIT");
        if (kitOff == null) kitOff = "";
        int count = 8;
        try {
            count = Integer.parseInt(System.getenv("CLIENTM_FIGHT_COUNT"));
        } catch (Exception ignored) {}
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI / 4 + (i / 8) * 0.39;
            double radius = 4 + (i / 8) * 2;
            net.minecraft.client.entity.EntityOtherPlayerMP p = new net.minecraft.client.entity.EntityOtherPlayerMP(
                mc.theWorld,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Fighter" + i));
            p.setPositionAndRotation(
                mc.thePlayer.posX + Math.cos(angle) * radius,
                mc.thePlayer.boundingBox.minY,
                mc.thePlayer.posZ + Math.sin(angle) * radius,
                (float) Math.toDegrees(angle) + 90f,
                0f);
            for (int slot = 0; slot < kit.length; slot++) {
                if (kitOff.indexOf(slot == 0 ? 'i' : 'a') >= 0) continue;
                net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(kit[slot]);
                if (kitOff.indexOf('g') < 0) stack.addEnchantment(net.minecraft.enchantment.Enchantment.unbreaking, 1);
                p.setCurrentItemOrArmor(slot, stack);
            }
            mc.theWorld.addEntityToWorld(-1000 - i, p);
            fighters.add(p);
        }
    }

    /**
     * CLIENTM_FIGHT_MOBS=n: n real zombie pigmen on the built-in server around the camera, so the client also has
     * entity packets, sounds and per-entity mod hooks to deal with, as on a busy server.
     */
    private void spawnMobs() {
        int count;
        try {
            count = Integer.parseInt(System.getenv("CLIENTM_FIGHT_MOBS"));
        } catch (Exception e) {
            return;
        }
        if (mc.getIntegratedServer() == null || count <= 0) return;
        MobSpawner spawner = new MobSpawner(count, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        spawner.clearFirst = true; // the world is saved: earlier runs' mobs must not pile up
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(spawner);
    }

    /** Runs once on the server thread, where spawning is safe. */
    public static final class MobSpawner {

        private final int count;
        private final double x, y, z;
        private boolean done;

        MobSpawner(int count, double x, double y, double z) {
            this.count = count;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** Removes earlier test mobs first, so repeated tests do not pile them up in the saved world. */
        boolean clearFirst;

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onServerTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
            if (done) return;
            done = true;
            net.minecraft.world.World world = net.minecraft.server.MinecraftServer.getServer().worldServers[0];
            if (clearFirst) clearTestMobs(world);
            for (int i = 0; i < count; i++) {
                double angle = i * 2.399963; // golden angle: an even spiral
                double radius = 3 + Math.sqrt(i) * 1.5;
                net.minecraft.entity.monster.EntityPigZombie mob = new net.minecraft.entity.monster.EntityPigZombie(
                    world);
                mob.setLocationAndAngles(x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius, 0f, 0f);
                mob.setCurrentItemOrArmor(0, new net.minecraft.item.ItemStack(net.minecraft.init.Items.golden_sword));
                world.spawnEntityInWorld(mob);
            }
        }
    }

    /** CLIENTM_TOUR=chat: stacked repeats, timestamps, longer history and hiding the chat. */
    private void chatTest() {
        wait = 20;
        net.minecraft.client.gui.GuiNewChat chat = mc.ingameGUI.getChatGUI();
        Module m = module("chat");
        switch (step++) {
            case 0: {
                noonAndClear();
                m.setEnabled(true);
                if ("1".equals(System.getenv("CLIENTM_NO_HUD_CACHE"))) module("hudcaching").setEnabled(false);
                setBool(m, "show", true);
                setBool(m, "timestamps", false);
                setBool(m, "stack", true);
                chat.clearChatMessages();
                for (int i = 0; i < 3; i++) chat.printChatMessage(new net.minecraft.util.ChatComponentText("hello"));
                chat.printChatMessage(new net.minecraft.util.ChatComponentText("world"));
                return;
            }
            case 1: {
                java.util.List<?> lines = chatLines(chat);
                String first = lines.size() > 1 ? lineText(lines.get(1)) : "?";
                chatReport.append("stack: ")
                    .append(lines.size())
                    .append(" lines, first = '")
                    .append(first)
                    .append("'\n");
                shot("chat-1-stacked");
                setBool(m, "timestamps", true);
                chat.printChatMessage(new net.minecraft.util.ChatComponentText("timed"));
                return;
            }
            case 2: {
                chatReport.append("timestamp: '")
                    .append(lineText(chatLines(chat).get(0)))
                    .append("'\n");
                setBool(m, "timestamps", false);
                for (int i = 0; i < 250; i++)
                    chat.printChatMessage(new net.minecraft.util.ChatComponentText("line " + i));
                chatReport.append("history: ")
                    .append(chatLines(chat).size())
                    .append(" lines kept\n");
                setBool(m, "show", false);
                return;
            }
            case 3:
                shot("chat-2-hidden");
                setBool(m, "background", true);
                setBool(m, "shadow", false);
                chat.printChatMessage(new net.minecraft.util.ChatComponentText("background on, no shadow"));
                chatReport.append("hidden: visible() = ")
                    .append(com.clientm.module.impl.ChatModule.visible())
                    .append('\n');
                setBool(m, "show", true);
                return;
            case 4: {
                shot("chat-3-plain");
                setBool(m, "background", false);
                setBool(m, "shadow", true);
                try {
                    java.nio.file.Files.write(
                        new java.io.File(mc.mcDataDir, "clientm-chat.txt").toPath(),
                        chatReport.toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    ClientM.LOG.error("Could not write the chat test result", e);
                }
                ClientM.LOG.info("Chat test:\n{}", chatReport);
                mc.shutdown();
            }
        }
    }

    private final StringBuilder chatReport = new StringBuilder();

    private static void setBool(Module m, String id, boolean v) {
        for (com.clientm.setting.Setting s : m.settings)
            if (s.id.equals(id)) ((com.clientm.setting.BoolSetting) s).value = v;
        m.onSettingsChanged();
    }

    private static java.util.List<?> chatLines(net.minecraft.client.gui.GuiNewChat chat) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.gui.GuiNewChat.class.getDeclaredField("chatLines");
            f.setAccessible(true);
            return (java.util.List<?>) f.get(chat);
        } catch (ReflectiveOperationException e) {
            return java.util.Collections.emptyList();
        }
    }

    private static String lineText(Object line) {
        return ((net.minecraft.client.gui.ChatLine) line).func_151461_a()
            .getUnformattedText();
    }

    /** Logs how Angelica's GL state manager sees the game thread (which context lookup path it takes). */
    static void glsmDiag() {
        if ("1".equals(System.getenv("CLIENTM_NO_HUD_CACHE")))
            for (Module m : ClientM.modules.all()) if (m.id.equals("hudcaching")) m.setEnabled(false);
        try {
            Class<?> c = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager");
            StringBuilder b = new StringBuilder(
                "GLSM diag: current=" + Thread.currentThread()
                    .getName());
            for (String f : new String[] { "MainThread", "workerContextsActive", "workerContextCount", "splashComplete",
                "drawableGLHolder", "mainCtx", "primaryContext" }) {
                java.lang.reflect.Field fl = c.getDeclaredField(f);
                fl.setAccessible(true);
                Object v = fl.get(null);
                b.append(", ")
                    .append(f)
                    .append('=')
                    .append(
                        v instanceof Thread ? ((Thread) v).getName() + (v == Thread.currentThread() ? "(this)" : "")
                            : String.valueOf(v));
            }
            java.lang.reflect.Field tl = c.getDeclaredField("tlWorkerContext");
            tl.setAccessible(true);
            Object mine = ((ThreadLocal<?>) tl.get(null)).get();
            java.lang.reflect.Field pf = c.getDeclaredField("primaryContext");
            pf.setAccessible(true);
            b.append(", thisThreadWorkerCtx=")
                .append(mine)
                .append(mine == pf.get(null) ? "(primary)" : "");
            ClientM.LOG.info(b.toString());
        } catch (Throwable t) {
            ClientM.LOG.warn("GLSM diag failed", t);
        }
    }

    /** Removes every zombie pigman (the test mob) from the loaded world. */
    static int clearTestMobs(net.minecraft.world.World world) {
        int removed = 0;
        for (Object o : new java.util.ArrayList<Object>(world.loadedEntityList)) {
            if (o instanceof net.minecraft.entity.monster.EntityPigZombie) {
                ((net.minecraft.entity.Entity) o).setDead();
                removed++;
            }
        }
        ClientM.LOG.info("Removed {} test mobs", removed);
        return removed;
    }

    /** Benchmarks must not depend on the weather the world happens to have: rain alone costs hundreds of FPS. */
    private void noonAndClear() {
        if (mc.getIntegratedServer() == null) return;
        net.minecraft.world.WorldServer world = mc.getIntegratedServer().worldServers[0];
        world.setWorldTime(6000L);
        world.getWorldInfo()
            .setRaining(false);
        world.getWorldInfo()
            .setThundering(false);
        world.getWorldInfo()
            .setRainTime(1000000);
        world.getWorldInfo()
            .setThunderTime(1000000);
    }

    private void finishBench(float secs) {
        benching = false;
        System.clearProperty("clientm.benching");
        int[] sorted = java.util.Arrays.copyOf(frameNanos, frames);
        java.util.Arrays.sort(sorted);
        String line = String.format(
            java.util.Locale.ROOT,
            "%-28s java %-10s avg %7.1f fps   1%% low %7.1f   0.1%% low %7.1f   worst frame %5.1f ms   frames %d",
            String.valueOf(System.getenv("CLIENTM_BENCH_LABEL")),
            System.getProperty("java.version"),
            frames / secs,
            lowFps(sorted, 0.01),
            lowFps(sorted, 0.001),
            sorted[frames - 1] / 1e6,
            frames);
        ClientM.LOG.info("Benchmark: {}", line);
        try {
            java.nio.file.Files.write(
                new java.io.File(mc.mcDataDir, "clientm-bench.txt").toPath(),
                (line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            ClientM.LOG.error("Could not write benchmark result", e);
        }
        mc.shutdown();
    }

    /** FPS of the slowest share of frames (0.01 = the "1% low"). */
    private static double lowFps(int[] sorted, double share) {
        int n = Math.max(1, (int) (sorted.length * share));
        long sum = 0;
        for (int i = sorted.length - n; i < sorted.length; i++) sum += sorted[i];
        return 1e9 / (sum / (double) n);
    }

    /** CLIENTM_TOUR=hit: third-person view of the player mid-hit, with a green hit colour, then one picture. */
    private void hitTest() {
        wait = 40;
        if (step++ == 0) {
            noonAndClear();
            Module m = module("hitcolour");
            for (com.clientm.setting.Setting s : m.settings) {
                if (s instanceof com.clientm.setting.IntSetting) ((com.clientm.setting.IntSetting) s)
                    .set(s.id.equals("green") ? 255 : s.id.equals("strength") ? 60 : 0);
            }
            m.setEnabled(true);
            mc.gameSettings.thirdPersonView = 2;
            forceHurt = true;
            return;
        }
        shot("hit-colour");
        mc.gameSettings.thirdPersonView = 0;
        mc.shutdown();
    }

    private boolean forceHurt;

    /** CLIENTM_TOUR=world: the Low preset as it comes, then without foliage, then with fog back on. */
    private void worldTest() {
        wait = 120;
        switch (step++) {
            case 0:
                noonAndClear();
                PerformancePreset.LOW.apply();
                break;
            case 1:
                shot("world-1-low-preset");
                com.clientm.module.impl.HideFoliageModule.set(true);
                break;
            case 2:
                shot("world-2-foliage-hidden");
                com.clientm.module.impl.NoFogModule.set(false);
                break;
            case 3:
                shot("world-3-fog-on");
                PerformancePreset.POTATO.apply(); // render distance 2, so fog would sit right in front
                break;
            case 4:
                shot("world-4-potato-no-fog");
                com.clientm.module.impl.NoFogModule.set(false);
                break;
            default:
                shot("world-5-potato-fog-on");
                mc.shutdown();
        }
    }

    private net.minecraft.entity.Entity tagged;
    private double tagX, tagY, tagZ;

    /** CLIENTM_TOUR=nametag: a named villager seen as vanilla, with Textured Backgrounds, then with the box hidden. */
    private void nametagTest() {
        wait = 40;
        Module m = module("texturedbg");
        Module cache = module("nametagcache");
        switch (step++) {
            case 0:
                net.minecraft.world.WorldServer world = mc.getIntegratedServer().worldServers[0];
                world.setWorldTime(6000L);
                m.setEnabled(false);
                cache.setEnabled(false);
                mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0f;
                net.minecraft.util.Vec3 look = mc.thePlayer.getLookVec();
                net.minecraft.entity.passive.EntityVillager v = new net.minecraft.entity.passive.EntityVillager(world);
                v.setCustomNameTag("§cRed §fNametag §aTest");
                v.setAlwaysRenderNameTag(true);
                tagX = mc.thePlayer.posX + look.xCoord * 3;
                tagY = mc.thePlayer.boundingBox.minY - 1;
                tagZ = mc.thePlayer.posZ + look.zCoord * 3;
                v.setPosition(tagX, tagY, tagZ);
                world.spawnEntityInWorld(v);
                tagged = v;
                break;
            case 1:
                shot("nametag-1-vanilla");
                cache.setEnabled(true);
                break;
            case 2:
                shot("nametag-2-cached");
                m.setEnabled(true);
                for (com.clientm.setting.Setting s : m.settings)
                    if (s.id.equals("hideNametagBox")) ((com.clientm.setting.BoolSetting) s).value = true;
                break;
            default:
                shot("nametag-3-cached-box-hidden");
                for (com.clientm.setting.Setting s : m.settings)
                    if (s.id.equals("hideNametagBox")) ((com.clientm.setting.BoolSetting) s).value = false;
                m.setEnabled(false);
                tagged.setDead();
                mc.shutdown();
        }
    }

    /** CLIENTM_TOUR=glass: a small glass wall in front of the player, vanilla and then with Clear Glass. */
    private void glassTest() {
        wait = 60;
        Module m = module("clearglass");
        net.minecraft.world.WorldServer world = mc.getIntegratedServer().worldServers[0];
        int n = step++;
        if (n == 0) {
            world.setWorldTime(6000L);
            m.setEnabled(false);
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0f;
            net.minecraft.util.Vec3 look = mc.thePlayer.getLookVec();
            glassX = (int) Math.floor(mc.thePlayer.posX + look.xCoord * 3);
            glassY = (int) Math.floor(mc.thePlayer.posY) - 1;
            glassZ = (int) Math.floor(mc.thePlayer.posZ + look.zCoord * 3);
        }
        if (n == 0 || n == 3) {
            // A 3x3x3 block of glass with one stained corner column, removed again at the end.
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                net.minecraft.block.Block b = n == 3 ? net.minecraft.init.Blocks.air
                    : x == 1 && z == 1 ? net.minecraft.init.Blocks.stained_glass : net.minecraft.init.Blocks.glass;
                world.setBlock(glassX + x, glassY + y, glassZ + z, b, b == net.minecraft.init.Blocks.air ? 0 : 14, 3);
            }
        }
        if (n == 1) {
            shot("glass-1-vanilla");
            m.setEnabled(true);
            mc.refreshResources();
        }
        if (n == 2) shot("glass-2-clear");
        if (n == 3) m.setEnabled(false);
        if (n == 4) mc.shutdown();
    }

    private boolean packsStarted, packsDone;
    private int packTicks;
    private net.minecraft.client.gui.GuiScreen packsMenu;

    /** CLIENTM_TOUR=packs: the resource pack screen at the top, inside a folder and inside a folder in it. */
    private void packsMenu() {
        java.io.File root = mc.getResourcePackRepository()
            .getDirResourcepacks();
        int t = ++packTicks;
        if (t == 20) {
            packsStarted = true;
            packsMenu = mc.currentScreen;
            mc.displayGuiScreen(new net.minecraft.client.gui.GuiScreenResourcePacks(packsMenu));
        }
        if (t == 40) shot("packs-1-top");
        if (t == 44) ((com.clientm.gui.PackFolderEntry.Opener) mc.currentScreen)
            .clientm$openFolder(new java.io.File(root, "PvP Packs"));
        if (t == 60) shot("packs-2-folder");
        if (t == 64) ((com.clientm.gui.PackFolderEntry.Opener) mc.currentScreen)
            .clientm$openFolder(new java.io.File(root, "PvP Packs/Old"));
        if (t == 80) shot("packs-3-subfolder");
        if (t == 84) {
            packsDone = true;
            mc.displayGuiScreen(packsMenu);
            launchFirstWorld();
        }
    }

    /** Second half of CLIENTM_TOUR=packs: the sky with Custom Skies on, then off. */
    private void skyTest() {
        wait = 60;
        Module m = module("customskies");
        int n = step++;
        if (n == 0) {
            noonAndClear();
            m.setEnabled(true);
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -35f;
        }
        if (n == 1) {
            shot("sky-1-pack");
            m.setEnabled(false);
        }
        if (n == 2) shot("sky-2-normal");
        if (n == 3) mc.shutdown();
    }

    private int glassX, glassY, glassZ;

    /** CLIENTM_TOUR=hud2: potion effects and a sidebar scoreboard on the HUD, then the inventory. */
    private void hudTest() {
        wait = 40;
        net.minecraft.server.MinecraftServer server = mc.getIntegratedServer();
        net.minecraft.entity.player.EntityPlayerMP p = (net.minecraft.entity.player.EntityPlayerMP) server
            .getConfigurationManager().playerEntityList.get(0);
        net.minecraft.scoreboard.Scoreboard board = server.worldServers[0].getScoreboard();
        switch (step++) {
            case 0:
                server.worldServers[0].setWorldTime(6000L);
                p.addPotionEffect(new net.minecraft.potion.PotionEffect(1, 1800, 1));
                p.addPotionEffect(new net.minecraft.potion.PotionEffect(5, 600, 0));
                p.addPotionEffect(new net.minecraft.potion.PotionEffect(12, 4800, 0));
                if (board.getObjective("clientmtest") != null) board.func_96519_k(board.getObjective("clientmtest"));
                net.minecraft.scoreboard.ScoreObjective o = board
                    .addScoreObjective("clientmtest", net.minecraft.scoreboard.IScoreObjectiveCriteria.field_96641_b);
                o.setDisplayName("\u00a7b\u00a7lPractice");
                String[] rows = { "\u00a7fOnline: \u00a7b148", "\u00a7fFighting: \u00a7b62",
                    "\u00a77play.example.net" };
                for (int i = 0; i < rows.length; i++) board.func_96529_a(rows[i], o)
                    .setScorePoints(rows.length - i);
                board.func_96530_a(1, o);
                for (HudModule h : ClientM.modules.hud()) h.setEnabled(false);
                module("fps").setEnabled(true);
                module("potions").setEnabled(true);
                module("scoreboard").setEnabled(false);
                break;
            case 1:
                shot("hud2-1-vanilla-scoreboard");
                module("scoreboard").setEnabled(true);
                break;
            case 2:
                shot("hud2-2-scoreboard-mod");
                mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiInventory(mc.thePlayer));
                break;
            default:
                shot("hud2-3-inventory");
                board.func_96519_k(board.getObjective("clientmtest"));
                p.clearActivePotions();
                mc.shutdown();
        }
    }

    private void advance() {
        if ("hud2".equals(System.getenv("CLIENTM_TOUR"))) {
            hudTest();
            return;
        }
        if ("packs".equals(System.getenv("CLIENTM_TOUR"))) {
            skyTest();
            return;
        }
        if ("glass".equals(System.getenv("CLIENTM_TOUR"))) {
            glassTest();
            return;
        }
        if ("nametag".equals(System.getenv("CLIENTM_TOUR"))) {
            nametagTest();
            return;
        }
        if ("world".equals(System.getenv("CLIENTM_TOUR"))) {
            worldTest();
            return;
        }
        if ("chat".equals(System.getenv("CLIENTM_TOUR"))) {
            chatTest();
            return;
        }
        if ("features".equals(System.getenv("CLIENTM_TOUR"))) {
            if (step++ > 0) return; // set up once; onFeatureTest drives the rest
            noonAndClear();
            mc.gameSettings.pauseOnLostFocus = false;
            for (String id : new String[] { "crosshair", "serverinfo", "hitmarker", "zoom" })
                module(id).setEnabled(true);
            MobSpawner spawner = new MobSpawner(4, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            spawner.clearFirst = true;
            cpw.mods.fml.common.FMLCommonHandler.instance()
                .bus()
                .register(spawner);
            featureStart = System.nanoTime();
            // The test walks up to the mobs; the saved world keeps where the player stands, and the fight
            // benchmark depends on it - so it goes back here at the end.
            homeX = mc.thePlayer.posX;
            homeY = mc.thePlayer.posY;
            homeZ = mc.thePlayer.posZ;
            homeYaw = mc.thePlayer.rotationYaw;
            homePitch = mc.thePlayer.rotationPitch;
            return;
        }
        if ("clicks".equals(System.getenv("CLIENTM_TOUR"))) {
            noonAndClear();
            mc.gameSettings.pauseOnLostFocus = false;
            clicking = true;
            return;
        }
        if ("hit".equals(System.getenv("CLIENTM_TOUR"))) {
            hitTest();
            return;
        }
        if ("benchfight".equals(System.getenv("CLIENTM_TOUR"))) {
            noonAndClear();
            mc.gameSettings.particleSetting = 0; // All
            spawnFighters();
            spawnMobs();
            glsmDiag();
            benching = true;
            System.setProperty("clientm.benching", "1");
            return;
        }
        if ("bench".equals(System.getenv("CLIENTM_TOUR")) || FLY) {
            noonAndClear();
            if (FLY && mc.getIntegratedServer() != null) {
                // Let the server-side player fly, or the server would pull the client back down.
                for (Object o : mc.getIntegratedServer()
                    .getConfigurationManager().playerEntityList) {
                    net.minecraft.entity.player.EntityPlayerMP p = (net.minecraft.entity.player.EntityPlayerMP) o;
                    p.capabilities.allowFlying = true;
                    p.capabilities.isFlying = true;
                    p.sendPlayerAbilities();
                }
                String speed = System.getenv("CLIENTM_BENCH_SPEED");
                if ("off".equals(speed)) module("lazychunks").setEnabled(false);
                else if (speed != null) {
                    Module lazy = module("lazychunks");
                    lazy.setEnabled(true);
                    for (com.clientm.setting.Setting s : lazy.settings)
                        if (s.id.equals("speed")) ((com.clientm.setting.IntSetting) s).set(Integer.parseInt(speed));
                    lazy.onSettingsChanged();
                }
            }
            glsmDiag();
            benching = true;
            System.setProperty("clientm.benching", "1");
            return;
        }
        if ("diag".equals(System.getenv("CLIENTM_TOUR"))) {
            diagnose();
            return;
        }
        wait = 12;
        switch (step++) {
            case 0:
                // Daylight, a mid preset and every HUD module on, so the pictures show something.
                noonAndClear();
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
