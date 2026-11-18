package com.clientm.vulkan;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/** OpenGL's fixed render state that a draw depends on, kept per context like the rest of {@link GLResources.Ctx}. */
@Lwjgl3Aware
final class RenderState {

    boolean blend, depthTest, cullFace, scissorTest, polygonOffsetFill, stencilTest, primitiveRestart;
    int srcRGB = 1, dstRGB = 0, srcAlpha = 1, dstAlpha = 0; // GL_ONE, GL_ZERO
    int eqRGB = 0x8006, eqAlpha = 0x8006; // FUNC_ADD
    float[] blendColor = new float[4];
    int depthFunc = 0x0201; // LESS
    boolean depthMask = true;
    boolean[] colorMask = { true, true, true, true };
    int cullMode = 0x0405; // BACK
    int frontFace = 0x0901; // CCW
    int polygonMode = 0x1B02; // FILL
    float offsetFactor, offsetUnits;
    float lineWidth = 1f;
    int[] viewport = new int[4];
    int[] scissor = new int[4];
    int stencilFunc = 0x0207, stencilRef, stencilValueMask = 0xFF, stencilWriteMask = 0xFF;
    int stencilFail = 0x1E00, stencilDepthFail = 0x1E00, stencilPass = 0x1E00; // KEEP
    /** Current values of generic vertex attributes (glVertexAttrib4f), used when an attribute array is off. */
    final float[][] attribValues = new float[16][];

    RenderState() {
        for (int i = 0; i < 16; i++) attribValues[i] = new float[] { 0, 0, 0, 1 };
    }

    void set(int cap, boolean on) {
        switch (cap) {
            case 0x0BE2:
                blend = on;
                break;
            case 0x0B71:
                depthTest = on;
                break;
            case 0x0B44:
                cullFace = on;
                break;
            case 0x0C11:
                scissorTest = on;
                break;
            case 0x8037:
                polygonOffsetFill = on;
                break;
            case 0x0B90:
                stencilTest = on;
                break;
            case 0x8D69:
            case 0x8F9D:
                primitiveRestart = on;
                break;
            default:
                break;
        }
    }

    boolean get(int cap) {
        switch (cap) {
            case 0x0BE2:
                return blend;
            case 0x0B71:
                return depthTest;
            case 0x0B44:
                return cullFace;
            case 0x0C11:
                return scissorTest;
            case 0x8037:
                return polygonOffsetFill;
            case 0x0B90:
                return stencilTest;
            default:
                return false;
        }
    }
}
