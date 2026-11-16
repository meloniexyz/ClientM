package com.clientm.mixins;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge multiplies the two pictures on its loading screen by the background colour, which suits a white screen and
 * turns everything invisible on a black one. The two pictures are drawn in their own colours instead.
 */
@Mixin(targets = "cpw.mods.fml.client.SplashProgress$3", remap = false)
public abstract class MixinSplashProgressThread_Theme {

    /** Test aid: with CLIENTM_SPLASH_SHOT set, the loading screen is saved to that file once. */
    @Unique
    private static final String CLIENTM_SHOT = System.getenv("CLIENTM_SPLASH_SHOT");

    @Unique
    private int clientm$frames;

    @ModifyArg(
        method = "run",
        at = @At(value = "INVOKE", target = "Lcpw/mods/fml/client/SplashProgress$3;setColor(I)V"),
        require = 0)
    private int clientm$picturesInOwnColours(int colour) {
        return 0xFFFFFF;
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V"), require = 0)
    private void clientm$saveForTests(CallbackInfo ci) {
        if (CLIENTM_SHOT == null || ++clientm$frames != 150) return;
        try {
            int w = Display.getWidth(), h = Display.getHeight();
            ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (x + (h - 1 - y) * w) * 4;
                    image.setRGB(
                        x,
                        y,
                        (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255));
                }
            }
            javax.imageio.ImageIO.write(image, "png", new File(CLIENTM_SHOT));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
