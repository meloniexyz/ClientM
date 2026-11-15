package com.clientm.module;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.clientm.ClientM;
import com.clientm.compat.PerformancePreset;
import com.clientm.gui.ClientScreen;
import com.clientm.module.impl.ArmourStatusModule;
import com.clientm.module.impl.ChunkLoadingModule;
import com.clientm.module.impl.CoordinatesModule;
import com.clientm.module.impl.CpsModule;
import com.clientm.module.impl.FpsModule;
import com.clientm.module.impl.GlintModule;
import com.clientm.module.impl.HideFoliageModule;
import com.clientm.module.impl.HitColourModule;
import com.clientm.module.impl.HudCachingModule;
import com.clientm.module.impl.KeystrokesModule;
import com.clientm.module.impl.LazyChunksModule;
import com.clientm.module.impl.NoFogModule;
import com.clientm.module.impl.PerformanceModule;
import com.clientm.module.impl.PotionStatusModule;
import com.clientm.module.impl.TexturedBackgroundsModule;
import com.clientm.module.impl.ToggleSprintModule;
import com.clientm.setting.BoolSetting;
import com.clientm.setting.IntSetting;
import com.clientm.setting.Setting;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class ModuleManager {

    private final File configFile;
    private final List<Module> modules = new ArrayList<>();
    // Plain arrays so the per-frame and per-tick loops never create iterators.
    private Module[] all = new Module[0];
    private HudModule[] hud = new HudModule[0];
    private final KeyBinding menuKey = new KeyBinding("Open ClientM menu", Keyboard.KEY_RSHIFT, "ClientM");

    /** True while the HUD editor is open: every module ticks so it has something to preview. */
    public boolean editing;
    private boolean appliedOnJoin;
    private boolean firstRun;

    public ModuleManager(File configFile) {
        this.configFile = configFile;
    }

    public void init() {
        register(new FpsModule());
        register(new CpsModule());
        register(new CoordinatesModule());
        register(new KeystrokesModule());
        register(new PotionStatusModule());
        register(new ArmourStatusModule());
        register(new ToggleSprintModule());
        register(new GlintModule());
        register(new HitColourModule());
        register(new PerformanceModule());
        register(new HudCachingModule());
        register(new LazyChunksModule());
        register(new NoFogModule());
        register(new HideFoliageModule());
        register(new ChunkLoadingModule());
        register(new TexturedBackgroundsModule());
        load();
        ClientRegistry.registerKeyBinding(menuKey);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void register(Module m) {
        modules.add(m);
        all = modules.toArray(new Module[0]);
        List<HudModule> h = new ArrayList<>();
        for (Module x : modules) if (x instanceof HudModule) h.add((HudModule) x);
        hud = h.toArray(new HudModule[0]);
    }

    public List<Module> all() {
        return Collections.unmodifiableList(modules);
    }

    public HudModule[] hud() {
        return hud;
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (menuKey.isPressed() && mc.currentScreen == null && mc.thePlayer != null) {
            mc.displayGuiScreen(new ClientScreen());
        }
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.button < 0) return;
        for (Module m : all) if (m.enabled) m.onMouse(event.button, event.buttonstate);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (firstRun) {
            // No config yet: this is the first launch, so start from the best-performance settings.
            firstRun = false;
            PerformancePreset.LOW.apply();
            save();
        }
        if (Minecraft.getMinecraft().thePlayer == null) return;
        if (!appliedOnJoin) {
            // Saved settings that reach into other mods are applied once the game is fully up.
            appliedOnJoin = true;
            for (Module m : all) if (m.enabled) m.onSettingsChanged();
        }
        for (Module m : all) if (m.enabled || editing) m.onTick();
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo || editing) return;
        ScaledResolution res = event.resolution;
        int sw = res.getScaledWidth();
        int sh = res.getScaledHeight();
        for (HudModule m : hud) {
            if (!m.enabled) continue;
            GL11.glPushMatrix();
            // Same clamp as the editor, so a module never ends up off-screen after a resize.
            int x = Math.max(0, Math.min((int) (m.x * sw), sw - m.scaledWidth()));
            int y = Math.max(0, Math.min((int) (m.y * sh), sh - m.scaledHeight()));
            GL11.glTranslatef(x, y, 0f);
            float scale = m.scale();
            if (scale != 1f) GL11.glScalef(scale, scale, 1f);
            m.render(mc.fontRenderer);
            GL11.glPopMatrix();
        }
    }

    public void load() {
        if (!configFile.isFile()) {
            firstRun = true;
            return;
        }
        try (Reader r = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(r)
                .getAsJsonObject();
            for (Module m : all) {
                if (!root.has(m.id)) continue;
                JsonObject o = root.getAsJsonObject(m.id);
                if (o.has("enabled")) m.enabled = o.get("enabled")
                    .getAsBoolean();
                if (m instanceof HudModule) {
                    HudModule h = (HudModule) m;
                    if (o.has("x")) h.x = o.get("x")
                        .getAsFloat();
                    if (o.has("y")) h.y = o.get("y")
                        .getAsFloat();
                    if (o.has("background")) h.background = o.get("background")
                        .getAsBoolean();
                }
                if (o.has("settings")) {
                    JsonObject st = o.getAsJsonObject("settings");
                    for (Setting s : m.settings) {
                        if (!st.has(s.id)) continue;
                        if (s instanceof BoolSetting) ((BoolSetting) s).value = st.get(s.id)
                            .getAsBoolean();
                        else if (s instanceof IntSetting) ((IntSetting) s).set(
                            st.get(s.id)
                                .getAsInt());
                    }
                }
            }
        } catch (Exception e) {
            ClientM.LOG.warn("Could not read {}, using defaults", configFile, e);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        for (Module m : all) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", m.enabled);
            if (m instanceof HudModule) {
                HudModule h = (HudModule) m;
                o.addProperty("x", h.x);
                o.addProperty("y", h.y);
                o.addProperty("background", h.background);
            }
            if (!m.settings.isEmpty()) {
                JsonObject st = new JsonObject();
                for (Setting s : m.settings) {
                    if (s instanceof BoolSetting) st.addProperty(s.id, ((BoolSetting) s).value);
                    else if (s instanceof IntSetting) st.addProperty(s.id, ((IntSetting) s).value);
                }
                o.add("settings", st);
            }
            root.add(m.id, o);
        }
        try (Writer w = Files.newBufferedWriter(configFile.toPath(), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(root, w);
        } catch (Exception e) {
            ClientM.LOG.warn("Could not save {}", configFile, e);
        }
    }
}
