package com.clientm.render;

import net.minecraft.client.Minecraft;

import com.clientm.ClientM;

/**
 * Practice servers (PotPvP and similar) move players between duels with a "dimension swap": the client is sent to the
 * Nether and straight back, often several times in a row, and the server then sends every chunk again. Each swap tears
 * down and rebuilds the terrain renderer. Players saw single chunks stay invisible after a duel started; walking away
 * or F3+A brought them back. So once the swaps stop (no new one for 1.5 seconds), the terrain is rebuilt once, as
 * F3+A does, and any chunk whose drawing got lost on the way is drawn again.
 */
public final class ChunkSafetyNet {

    private static final long SETTLE_MS = 1500;

    private static volatile long lastRespawn;

    private ChunkSafetyNet() {}

    /** A respawn packet (a dimension change or swap) was handled (MixinNetHandlerPlayClient_Respawn). */
    public static void respawned() {
        lastRespawn = System.currentTimeMillis();
    }

    /** Called every client tick. */
    public static void tick() {
        long at = lastRespawn;
        if (at == 0 || System.currentTimeMillis() - at < SETTLE_MS) return;
        lastRespawn = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.renderGlobal == null) return;
        mc.renderGlobal.loadRenderers();
        ClientM.LOG.info("Terrain rebuilt after a dimension change");
    }
}
