package com.clientm.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;

import org.lwjgl.opengl.GL11;

import com.clientm.ClientM;
import com.clientm.mixins.AccessorMinecraft_Session;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;

/**
 * The accounts the ClientM launcher signed in before starting the game, for switching on the main menu. The launcher
 * passes a file named in CLIENTM_ACCOUNTS with each account's name, id, Minecraft session token and face picture; it
 * is read once and deleted. Started from another launcher, only the current account is known and nothing can be
 * switched.
 */
public final class Accounts {

    public static final class Account {

        public final String name;
        public final String uuid;
        final String token;
        final String facePath;
        ResourceLocation face;

        Account(String name, String uuid, String token, String facePath) {
            this.name = name;
            this.uuid = uuid;
            this.token = token;
            this.facePath = facePath;
        }
    }

    private static final ResourceLocation STEVE = new ResourceLocation("textures/entity/steve.png");
    private static final List<Account> LIST = new ArrayList<>();
    /** Skins fetched in the game, by account id, for accounts without a face picture from the launcher. */
    private static final Map<String, ResourceLocation> SKINS = new HashMap<>();
    private static boolean loaded;

    private Accounts() {}

    /** Every account that can be switched to, the current one included. Empty when not started by ClientM. */
    public static List<Account> all() {
        load();
        return Collections.unmodifiableList(LIST);
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        String path = System.getenv("CLIENTM_ACCOUNTS");
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        try {
            JsonObject root = new JsonParser()
                .parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonArray list = root.getAsJsonArray("list");
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                LIST.add(
                    new Account(
                        o.get("name")
                            .getAsString(),
                        o.get("uuid")
                            .getAsString(),
                        o.get("token")
                            .getAsString(),
                        o.has("face") && !o.get("face")
                            .isJsonNull() ? o.get("face")
                                .getAsString() : null));
            }
            ClientM.LOG.info("Account switcher: {} account(s) from the launcher", LIST.size());
        } catch (Throwable t) {
            ClientM.LOG.warn("Account switcher could not read the launcher's account list ({})", t.toString());
            LIST.clear();
        } finally {
            // The session tokens in it are not left lying around.
            if (!file.delete()) file.deleteOnExit();
        }
    }

    public static boolean isCurrent(Account a) {
        return a.uuid.equalsIgnoreCase(
            Minecraft.getMinecraft()
                .getSession()
                .getPlayerID());
    }

    /** Signs the game in as this account. Only used on the main menu, never inside a world or on a server. */
    public static void switchTo(Account a) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null || isCurrent(a)) return;
        ((AccessorMinecraft_Session) mc).clientm$setSession(new Session(a.name, a.uuid, a.token, "mojang"));
        ClientM.LOG.info("Switched account to {}", a.name);
    }

    /** Draws the head of the given account (null for the current one) as a size x size square. */
    public static void drawFace(Account a, int x, int y, int size) {
        Minecraft mc = Minecraft.getMinecraft();
        String uuid = a != null ? a.uuid
            : mc.getSession()
                .getPlayerID();
        if (a == null) for (Account o : all()) if (isCurrent(o)) a = o;
        if (a != null && a.facePath != null) {
            if (a.face == null) a.face = loadFace(a);
            if (a.face != null) {
                quad(a.face, x, y, size, 0F, 0F, 1F, 1F);
                return;
            }
        }
        // No picture from the launcher: fetch the skin, and show the default head until it arrives.
        ResourceLocation skin = skin(
            uuid,
            a != null ? a.name
                : mc.getSession()
                    .getUsername());
        ResourceLocation tex = skin != null ? skin : STEVE;
        // Skins are 64 x 32 in 1.7: the face is at (8, 8), the hat layer at (40, 8).
        quad(tex, x, y, size, 8F / 64F, 8F / 32F, 16F / 64F, 16F / 32F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        quad(tex, x, y, size, 40F / 64F, 8F / 32F, 48F / 64F, 16F / 32F);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private static ResourceLocation loadFace(Account a) {
        try {
            BufferedImage image = ImageIO.read(new File(a.facePath));
            if (image == null) return null;
            return Minecraft.getMinecraft()
                .getTextureManager()
                .getDynamicTextureLocation("clientm_face", new DynamicTexture(image));
        } catch (Throwable t) {
            return null;
        }
    }

    private static ResourceLocation skin(String uuid, String name) {
        if (uuid == null) return null;
        if (SKINS.containsKey(uuid)) return SKINS.get(uuid);
        SKINS.put(uuid, null);
        try {
            GameProfile profile = new GameProfile(com.mojang.util.UUIDTypeAdapter.fromString(uuid), name);
            Minecraft.getMinecraft()
                .func_152342_ad()
                .func_152790_a(profile, new SkinManager.SkinAvailableCallback() {

                    @Override
                    public void func_152121_a(MinecraftProfileTexture.Type type, ResourceLocation location) {
                        if (type == MinecraftProfileTexture.Type.SKIN) SKINS.put(uuid, location);
                    }
                }, false);
        } catch (Throwable t) {
            // stays the default head
        }
        return null;
    }

    private static void quad(ResourceLocation tex, int x, int y, int size, float u0, float v0, float u1, float v1) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(tex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + size, 0, u0, v1);
        t.addVertexWithUV(x + size, y + size, 0, u1, v1);
        t.addVertexWithUV(x + size, y, 0, u1, v0);
        t.addVertexWithUV(x, y, 0, u0, v0);
        t.draw();
    }
}
