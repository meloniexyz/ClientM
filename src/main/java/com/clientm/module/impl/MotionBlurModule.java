package com.clientm.module.impl;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.IntSetting;

/**
 * Motion blur: the world leaves a short, fading trail when the camera moves. The HUD, crosshair and chat stay sharp
 * (this runs right after the world and the hand are drawn).
 *
 * The trail is kept in a 16-bit float image. It is updated at a fixed 240 times a second: the newest frame is blended
 * into it with a weight that depends on the real time passed, so the blur looks the same at 240 FPS and at 3000 FPS
 * (a fixed weight per frame would barely blur at all at high FPS, and an 8-bit trail updated thousands of times a
 * second would leave a stuck ghost image because each frame's share rounds away). Every frame then shows the trail
 * over the current frame, so what is on screen still changes on every frame.
 *
 * Strength 1 to 10 sets how long the trail lasts, about 20 ms to 150 ms.
 */
public class MotionBlurModule extends Module {

    private static MotionBlurModule instance;

    private final IntSetting strength = add(new IntSetting("strength", "Strength", 5, 1, 10));

    private static final long STEP_NS = 1_000_000_000L / 240;
    /** Debug (CLIENTM_BLUR_DEBUG=1): show only the trail image, to see whether each step draws. */
    private static final boolean DEBUG_TRAIL_ONLY = "1".equals(System.getenv("CLIENTM_BLUR_DEBUG"));
    /** Debug (CLIENTM_BLUR_DEBUG=2): show only the copy of the current frame. */
    private static final boolean DEBUG_COPY_ONLY = "2".equals(System.getenv("CLIENTM_BLUR_DEBUG"));
    /** After a longer gap (a pause, a loading screen) the trail starts again instead of smearing old frames in. */
    private static final long RESTART_NS = 250_000_000L;

    private int w, h;
    private int trailTex, trailFbo, copyTex, copyFbo;
    private long lastStep;
    private boolean fresh = true, failed, logged;
    private int creates, applies;
    private long applyNs;

    public MotionBlurModule() {
        super("motionblur", "Motion Blur", Category.VISUAL, false);
        instance = this;
    }

    @Override
    public String description() {
        return "Smooth camera trail, 1 = light, 10 = heavy";
    }

    @Override
    public void onDisable() {
        release();
    }

    /** Called right after the world (and the hand) is drawn, before the HUD (MixinEntityRenderer_MotionBlur). */
    public static void afterWorld() {
        MotionBlurModule m = instance;
        if (m == null || !m.enabled || m.failed) return;
        try {
            m.apply();
        } catch (Throwable t) {
            m.failed = true;
            m.release();
            ClientM.LOG.warn("Motion blur switched off after an error", t);
        }
    }

    /** How long the trail lasts, in milliseconds. */
    private float trailMs() {
        return 6f + strength.value * 14f;
    }

    private void apply() {
        if (mc.theWorld == null || !OpenGlHelper.isFramebufferEnabled()) return;
        Framebuffer main = mc.getFramebuffer();
        if (main == null || main.framebufferObject <= 0) return;
        int fw = main.framebufferWidth, fh = main.framebufferHeight;
        if (fw != w || fh != h || trailFbo == 0) {
            release();
            create(fw, fh);
            fresh = true;
            if (++creates <= 3) ClientM.LOG
                .info("Motion blur images {}x{}: trail {}/{} copy {}/{}", fw, fh, trailTex, trailFbo, copyTex, copyFbo);
            if (trailFbo == 0 || copyFbo == 0 || creates > 20) {
                failed = true;
                release();
                ClientM.LOG.warn("Motion blur switched off: its images could not be made");
                return;
            }
        }
        long t0 = System.nanoTime();
        long now = System.nanoTime();
        long elapsed = now - lastStep;
        if (fresh || elapsed > RESTART_NS) {
            // Start the trail from this frame.
            copy(main.framebufferObject);
            draw(trailFbo, copyTex, 1f, false);
            lastStep = now;
            fresh = false;
            main.bindFramebuffer(true);
            return;
        }
        float tau = trailMs() * 1_000_000f;
        if (elapsed >= STEP_NS) {
            // Blend the newest frame into the trail, weighted by the time since the last step.
            copy(main.framebufferObject);
            float keep = (float) Math.exp(-elapsed / tau);
            draw(trailFbo, copyTex, 1f - keep, true);
            lastStep = now;
        }
        // Show the trail over the current frame.
        float show = DEBUG_TRAIL_ONLY ? 1f : (float) Math.exp(-STEP_NS / tau);
        if (DEBUG_COPY_ONLY) {
            copy(main.framebufferObject);
            draw(main.framebufferObject, copyTex, 1f, false);
        } else draw(main.framebufferObject, trailTex, show, true);
        main.bindFramebuffer(true);
        applyNs += System.nanoTime() - t0;
        if (++applies == 5000)
            ClientM.LOG.info("Motion blur costs {} us a frame (processor side)", applyNs / 5000 / 1000);
        if (!logged) {
            logged = true;
            ClientM.LOG.info("Motion blur is active ({}x{})", fw, fh);
        }
    }

    /** Copies the game's frame into copyTex (a framebuffer blit, which keeps the right row order on any backend). */
    private void copy(int from) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Draws a texture over a whole framebuffer: blended with the given weight, or replacing it. The weight is a
     * constant
     * blend colour, not an alpha from the images: Minecraft leaves the frame's alpha channel mostly empty (zero over
     * the
     * sky), and weighting by it kept old frames stuck there as ghosts. The target's alpha is left as it is.
     */
    private void draw(int fbo, int texture, float weight, boolean blend) {
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_VIEWPORT_BIT
                | GL11.GL_TEXTURE_BIT
                | GL11.GL_CURRENT_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, w, h);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, 1, 0, 1, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (blend) {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(0f, 0f, 0f, weight);
            GL14.glBlendFuncSeparate(
                GL11.GL_CONSTANT_ALPHA,
                GL11.GL_ONE_MINUS_CONSTANT_ALPHA,
                GL11.GL_ZERO,
                GL11.GL_ONE);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(1f, 1f, 1f, 1f);
        t.addVertexWithUV(0, 0, 0, 0, 0);
        t.addVertexWithUV(1, 0, 0, 1, 0);
        t.addVertexWithUV(1, 1, 0, 1, 1);
        t.addVertexWithUV(0, 1, 0, 0, 1);
        t.draw();
        if (blend) GL14.glBlendColor(0f, 0f, 0f, 0f);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
    }

    private void create(int fw, int fh) {
        w = fw;
        h = fh;
        trailTex = texture(GL30.GL_RGBA16F, GL11.GL_FLOAT);
        trailFbo = framebuffer(trailTex);
        copyTex = texture(GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE);
        copyFbo = framebuffer(copyTex);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private int texture(int internalFormat, int type) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            internalFormat,
            w,
            h,
            0,
            GL11.GL_RGBA,
            type,
            (java.nio.ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int framebuffer(int tex) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        return fbo;
    }

    private void release() {
        if (trailFbo != 0) GL30.glDeleteFramebuffers(trailFbo);
        if (copyFbo != 0) GL30.glDeleteFramebuffers(copyFbo);
        if (trailTex != 0) GL11.glDeleteTextures(trailTex);
        if (copyTex != 0) GL11.glDeleteTextures(copyTex);
        trailFbo = copyFbo = trailTex = copyTex = 0;
        w = h = 0;
        fresh = true;
    }
}
