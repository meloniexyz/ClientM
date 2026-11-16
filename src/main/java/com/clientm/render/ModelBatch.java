package com.clientm.render;

import java.util.List;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.Vec3;

import com.clientm.ClientM;
import com.clientm.mixins.AccessorModelBox;
import com.clientm.mixins.AccessorTexturedQuad;
import com.clientm.module.impl.ModelBatchModule;

/**
 * Draws a whole humanoid model (player, zombie, a worn armour piece) in one draw.
 *
 * Vanilla draws each limb by itself: move, rotate, draw six faces, undo. A player in full armour is about seventeen
 * such draws before the enchantment glint repeats the armour ones. Here the limb positions are worked out in Java and
 * every face goes into one batch, so the graphics driver is called once per model instead of once per limb. The
 * picture is the same: same vertices, same normals, same texture coordinates.
 */
public final class ModelBatch {

    private static final int MAX_DEPTH = 8;
    /** One 3x4 matrix per nesting level, row by row: x row, y row, z row; the fourth column is the translation. */
    private static final float[] STACK = new float[12 * MAX_DEPTH];
    private static int depth;
    private static boolean active;
    private static boolean logged;

    private ModelBatch() {}

    public static boolean active() {
        return active;
    }

    /** Called before a humanoid model draws its limbs. */
    public static void begin() {
        if (active || !ModelBatchModule.active()) return;
        active = true;
        depth = 0;
        for (int i = 0; i < 12; i++) STACK[i] = 0f;
        STACK[0] = STACK[5] = STACK[10] = 1f;
        Tessellator.instance.startDrawingQuads();
    }

    /** Called after the last limb. */
    public static void end() {
        if (!active) return;
        active = false;
        Tessellator.instance.draw();
        if (!logged) {
            logged = true;
            ClientM.LOG.info("Batched model drawing is active");
        }
    }

    /** Safety net at the start of a frame: a model that failed halfway must not leave the batch open. */
    public static void frameStart() {
        if (!active) return;
        active = false;
        try {
            Tessellator.instance.draw();
        } catch (Throwable ignored) {}
    }

    /** Adds one limb, and the limbs attached to it, in place of ModelRenderer.render. */
    public static void add(ModelRenderer part, float scale) {
        if (part.isHidden || !part.showModel || depth + 1 >= MAX_DEPTH) return;
        int from = depth * 12;
        int m = from + 12;
        depth++;
        System.arraycopy(STACK, from, STACK, m, 12);

        translate(
            m,
            part.offsetX + part.rotationPointX * scale,
            part.offsetY + part.rotationPointY * scale,
            part.offsetZ + part.rotationPointZ * scale);
        if (part.rotateAngleZ != 0f) rotate(m, 0, 1, part.rotateAngleZ);
        if (part.rotateAngleY != 0f) rotate(m, 2, 0, part.rotateAngleY);
        if (part.rotateAngleX != 0f) rotate(m, 1, 2, part.rotateAngleX);

        Tessellator t = Tessellator.instance;
        List<?> boxes = part.cubeList;
        for (int b = 0, n = boxes.size(); b < n; b++) {
            TexturedQuad[] quads = ((AccessorModelBox) boxes.get(b)).clientm$quads();
            for (TexturedQuad quad : quads) emit(t, m, quad, scale);
        }
        List<?> children = part.childModels;
        if (children != null) {
            for (int c = 0, n = children.size(); c < n; c++) add((ModelRenderer) children.get(c), scale);
        }
        depth--;
    }

    private static void emit(Tessellator t, int m, TexturedQuad quad, float scale) {
        PositionTextureVertex[] v = quad.vertexPositions;
        Vec3 p0 = v[0].vector3D;
        Vec3 p1 = v[1].vector3D;
        Vec3 p2 = v[2].vector3D;
        // Same face normal as TexturedQuad.draw: (p2 - p1) x (p0 - p1).
        float ax = (float) (p2.xCoord - p1.xCoord);
        float ay = (float) (p2.yCoord - p1.yCoord);
        float az = (float) (p2.zCoord - p1.zCoord);
        float bx = (float) (p0.xCoord - p1.xCoord);
        float by = (float) (p0.yCoord - p1.yCoord);
        float bz = (float) (p0.zCoord - p1.zCoord);
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1.0E-4f) {
            nx /= length;
            ny /= length;
            nz /= length;
        }
        if (((AccessorTexturedQuad) quad).clientm$invertNormal()) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float[] s = STACK;
        t.setNormal(
            s[m] * nx + s[m + 1] * ny + s[m + 2] * nz,
            s[m + 4] * nx + s[m + 5] * ny + s[m + 6] * nz,
            s[m + 8] * nx + s[m + 9] * ny + s[m + 10] * nz);
        for (int i = 0; i < 4; i++) {
            PositionTextureVertex vertex = v[i];
            float x = (float) vertex.vector3D.xCoord * scale;
            float y = (float) vertex.vector3D.yCoord * scale;
            float z = (float) vertex.vector3D.zCoord * scale;
            t.addVertexWithUV(
                s[m] * x + s[m + 1] * y + s[m + 2] * z + s[m + 3],
                s[m + 4] * x + s[m + 5] * y + s[m + 6] * z + s[m + 7],
                s[m + 8] * x + s[m + 9] * y + s[m + 10] * z + s[m + 11],
                vertex.texturePositionX,
                vertex.texturePositionY);
        }
    }

    private static void translate(int m, float x, float y, float z) {
        float[] s = STACK;
        for (int row = 0; row < 12; row += 4) {
            s[m + row + 3] += s[m + row] * x + s[m + row + 1] * y + s[m + row + 2] * z;
        }
    }

    /**
     * Multiplies by a rotation in the plane of columns a and b, turning a towards b. (0,1) is a turn about z, (2,0)
     * about y and (1,2) about x, matching glRotatef.
     */
    private static void rotate(int m, int a, int b, float radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float[] s = STACK;
        for (int row = 0; row < 12; row += 4) {
            float ca = s[m + row + a];
            float cb = s[m + row + b];
            s[m + row + a] = cos * ca + sin * cb;
            s[m + row + b] = cos * cb - sin * ca;
        }
    }
}
