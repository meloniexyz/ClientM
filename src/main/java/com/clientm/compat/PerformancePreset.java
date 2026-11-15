package com.clientm.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

import org.lwjgl.opengl.Display;

import com.clientm.ClientM;
import com.clientm.module.impl.GlintModule;

/**
 * One-click video configurations, from "squeeze every frame" to "look as good as possible". Each sets Minecraft's own
 * video settings plus Angelica's renderer options. They never touch controls, and the frame rate is always uncapped.
 *
 * Culling and the other Angelica optimisations that cost no visual quality stay on in every preset.
 */
public enum PerformancePreset {

    // spotless:off
    //      label        desc                                 dist fancy  ao clouds part mip  sort  blend smooth grass      filter              glint
    POTATO( "Potato",   "Every last frame, looks rough",        2, false, 0, false, 2,  0, false, 0, false, "FAST",    "NONE",             false),
    LOW(    "Low",      "Max FPS that still plays well",        8, false, 0, false, 1,  0, false, 0, false, "FAST",    "NONE",             true),
    BALANCED("Balanced", "Smooth lighting, modest distance",   10, false, 2, false, 0,  2, true,  1, true,  "DEFAULT", "NONE",             true),
    HIGH(   "High",     "Fancy graphics, clouds, filtering",   12, true,  2, true,  0,  4, true,  3, true,  "FANCY",   "RGSS_ANISOTROPIC", true),
    FANCY(  "Fancy",    "Everything on, longest distance",     16, true,  2, true,  0,  4, true,  7, true,  "FANCY",   "RGSS_ANISOTROPIC", true);
    // spotless:on

    /** The preset most recently applied this session, or null. */
    public static PerformancePreset last;

    public final String label;
    public final String description;
    private final int renderDistance;
    private final boolean fancyGraphics;
    private final int ambientOcclusion; // 0 off, 2 maximum
    private final boolean clouds;
    private final int particles; // 0 all, 1 decreased, 2 minimal
    private final int mipmaps;
    private final boolean translucencySorting;
    private final int biomeBlend;
    private final boolean smoothLighting;
    private final String grass;
    private final String textureFilter;
    private final boolean glint;

    PerformancePreset(String label, String description, int renderDistance, boolean fancyGraphics, int ambientOcclusion,
        boolean clouds, int particles, int mipmaps, boolean translucencySorting, int biomeBlend, boolean smoothLighting,
        String grass, String textureFilter, boolean glint) {
        this.label = label;
        this.description = description;
        this.renderDistance = renderDistance;
        this.fancyGraphics = fancyGraphics;
        this.ambientOcclusion = ambientOcclusion;
        this.clouds = clouds;
        this.particles = particles;
        this.mipmaps = mipmaps;
        this.translucencySorting = translucencySorting;
        this.biomeBlend = biomeBlend;
        this.smoothLighting = smoothLighting;
        this.grass = grass;
        this.textureFilter = textureFilter;
        this.glint = glint;
    }

    public void apply() {
        Minecraft mc = Minecraft.getMinecraft();
        GameSettings gs = mc.gameSettings;

        boolean texturesChanged = gs.mipmapLevels != mipmaps || gs.anisotropicFiltering != 1;
        boolean rendererChanged = gs.fancyGraphics != fancyGraphics || gs.ambientOcclusion != ambientOcclusion
            || gs.renderDistanceChunks != renderDistance;

        gs.fancyGraphics = fancyGraphics; // fast = plain leaves, no entity shadows
        gs.ambientOcclusion = ambientOcclusion;
        gs.clouds = clouds;
        gs.particleSetting = particles;
        gs.renderDistanceChunks = renderDistance;
        gs.mipmapLevels = mipmaps;
        gs.anisotropicFiltering = 1; // Angelica's texture filter mode handles this instead
        gs.enableVsync = false;
        gs.limitFramerate = 260; // 260 is "unlimited" in 1.7.10
        gs.advancedOpengl = false;
        gs.snooperEnabled = false;
        if (this == POTATO) gs.viewBobbing = false;
        gs.saveOptions();
        Display.setVSyncEnabled(false);

        // The glint is an extra draw pass on every enchanted item, so Potato drops it.
        GlintModule.setShown(glint);

        if (AngelicaBridge.available()) {
            boolean a = false;
            // Chunk building
            a |= rendererChanged |= AngelicaBridge.set("performance", "chunkBuilderThreads", 0); // auto
            a |= AngelicaBridge.set("performance", "alwaysDeferChunkUpdates", true);
            // Free wins: skip everything that cannot be seen, use the cheaper vertex layout
            a |= AngelicaBridge.set("performance", "useOcclusionCulling", true);
            a |= AngelicaBridge.set("performance", "useEntityCulling", true);
            a |= AngelicaBridge.set("performance", "useFogOcclusion", true);
            a |= AngelicaBridge.set("performance", "useBlockFaceCulling", true);
            a |= AngelicaBridge.set("performance", "animateOnlyVisibleTextures", true);
            a |= AngelicaBridge.set("advanced", "useParticleCulling", true);
            a |= AngelicaBridge.set("performance", "useCompactVertexFormat", true);
            a |= AngelicaBridge.set("performance", "useRenderPassOptimization", true);
            a |= AngelicaBridge.set("performance", "useNoErrorGLContext", true);
            // Quality features that cost chunk-build or GPU time
            a |= rendererChanged |= AngelicaBridge.set("performance", "translucencySorting", translucencySorting);
            a |= rendererChanged |= AngelicaBridge.set("quality", "biomeBlendRadius", biomeBlend);
            a |= rendererChanged |= AngelicaBridge.set("quality", "useCeleritasSmoothLighting", smoothLighting);
            a |= rendererChanged |= AngelicaBridge.set("quality", "grassQuality", grass);
            a |= texturesChanged |= AngelicaBridge.set("quality", "textureFilterMode", textureFilter);
            if (a) AngelicaBridge.save();
        }
        // Angelica has its own VSync switch, on by default, which caps FPS at the monitor refresh rate. Its own
        // toggle both saves the option and applies it at once; setting the field alone only works after a restart.
        AngelicaBridge.vsyncOff();
        Display.setVSyncEnabled(false);

        if (texturesChanged) mc.scheduleResourcesRefresh();
        else if (rendererChanged && mc.theWorld != null && mc.renderGlobal != null) mc.renderGlobal.loadRenderers();

        last = this;
        ClientM.LOG.info("Applied the {} preset (Angelica options: {})", label, AngelicaBridge.available());
    }
}
