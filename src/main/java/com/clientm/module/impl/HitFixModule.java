package com.clientm.module.impl;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S0FPacketSpawnMob;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S1DPacketEntityEffect;
import net.minecraft.network.play.server.S1EPacketRemoveEntityEffect;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.world.World;

import com.clientm.ClientM;
import com.clientm.mixins.AccessorNetworkManager;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;

/**
 * 1.7 handles what the server sends once per game tick, so a hit can show up to 50 ms after it arrived. 1.8 handles
 * it on the next frame. This brings the 1.8 timing to the packets that matter in a fight: at the start of every frame,
 * the ones in the switched-on groups are handled right away, and everything else still waits for the tick.
 *
 * Order is kept where it matters: once a packet about an entity has to wait, later packets about that same entity
 * wait too, and packets that reset the world or the player position stop the early handling for that frame.
 */
public class HitFixModule extends Module {

    private static HitFixModule instance;

    private final BoolSetting hits = add(new BoolSetting("hits", "Hits", true));
    private final BoolSetting potions = add(new BoolSetting("potions", "Potions", true));
    private final BoolSetting pearls = add(new BoolSetting("pearls", "Ender pearls", true));
    private final BoolSetting knockback = add(new BoolSetting("knockback", "Knockback (self knockback)", true));
    private final BoolSetting heads = add(new BoolSetting("heads", "Player head movement", true));

    private final Set<Integer> waiting = new HashSet<>();
    private boolean broken;
    private boolean logged;

    public HitFixModule() {
        super("hitfix", "Hit Fix", Category.MOVEMENT, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Hits, knockback and pearls show on the next frame, like 1.8";
    }

    /** Called at the start of every rendered frame. */
    public static void frame() {
        HitFixModule m = instance;
        if (m != null && m.enabled && !m.broken) m.run();
    }

    private void run() {
        if (mc.theWorld == null || mc.thePlayer == null || !(mc.getNetHandler() instanceof NetHandlerPlayClient))
            return;
        NetHandlerPlayClient handler = mc.getNetHandler();
        try {
            Queue<?> queue = ((AccessorNetworkManager) handler.getNetworkManager()).clientm$receivedPackets();
            if (queue.isEmpty()) return;
            waiting.clear();
            int looked = 0;
            for (Iterator<?> it = queue.iterator(); it.hasNext() && looked++ < 256;) {
                Packet packet = (Packet) it.next();
                if (resets(packet)) break;
                int id = entityId(packet);
                if (early(packet, mc.theWorld) && (id < 0 || !waiting.contains(id))) {
                    it.remove();
                    packet.processPacket(handler);
                    if (!logged) {
                        logged = true;
                        ClientM.LOG.info(
                            "Hit Fix is active: first early packet was {}",
                            packet.getClass()
                                .getSimpleName());
                    }
                    if (mc.theWorld == null || mc.thePlayer == null) break;
                } else if (id >= 0) {
                    waiting.add(id);
                }
            }
        } catch (Throwable t) {
            broken = true;
            ClientM.LOG.warn("Hit Fix switched off after an error", t);
        }
    }

    /** Packets after which nothing may be handled ahead of the tick. */
    private static boolean resets(Packet p) {
        return p instanceof S01PacketJoinGame || p instanceof S07PacketRespawn
            || p instanceof S08PacketPlayerPosLook
            || p instanceof S13PacketDestroyEntities
            || p instanceof S0CPacketSpawnPlayer
            || p instanceof S0FPacketSpawnMob
            || p instanceof S40PacketDisconnect;
    }

    /** The entity a packet is about, when that can be read without the world; -1 otherwise. */
    private static int entityId(Packet p) {
        if (p instanceof S12PacketEntityVelocity) return ((S12PacketEntityVelocity) p).func_149412_c();
        if (p instanceof S18PacketEntityTeleport) return ((S18PacketEntityTeleport) p).func_149451_c();
        if (p instanceof S0BPacketAnimation) return ((S0BPacketAnimation) p).func_148978_c();
        if (p instanceof S0EPacketSpawnObject) return ((S0EPacketSpawnObject) p).func_149001_c();
        if (p instanceof S1DPacketEntityEffect) return ((S1DPacketEntityEffect) p).func_149426_d();
        if (p instanceof S1EPacketRemoveEntityEffect) return ((S1EPacketRemoveEntityEffect) p).func_149076_c();
        if (p instanceof S1CPacketEntityMetadata) return ((S1CPacketEntityMetadata) p).func_149375_d();
        if (p instanceof S14PacketEntity || p instanceof S19PacketEntityHeadLook
            || p instanceof S19PacketEntityStatus) {
            Entity e = p instanceof S14PacketEntity ? ((S14PacketEntity) p).func_149065_a(mc.theWorld)
                : p instanceof S19PacketEntityHeadLook ? ((S19PacketEntityHeadLook) p).func_149381_a(mc.theWorld)
                    : ((S19PacketEntityStatus) p).func_149161_a(mc.theWorld);
            return e == null ? -1 : e.getEntityId();
        }
        return -1;
    }

    private boolean early(Packet p, World world) {
        if (p instanceof S19PacketEntityStatus) {
            // 2 = hurt
            return hits.value && ((S19PacketEntityStatus) p).func_149160_c() == 2;
        }
        if (p instanceof S0BPacketAnimation) {
            // 0 swing, 1 hurt, 4 critical hit, 5 enchanted hit
            int type = ((S0BPacketAnimation) p).func_148977_d();
            return hits.value && (type == 0 || type == 1 || type == 4 || type == 5);
        }
        if (p instanceof S1DPacketEntityEffect || p instanceof S1EPacketRemoveEntityEffect) return potions.value;
        if (p instanceof S0EPacketSpawnObject) {
            // 65 ender pearl, 73 thrown potion
            int type = ((S0EPacketSpawnObject) p).func_148993_l();
            return type == 65 && pearls.value || type == 73 && potions.value;
        }
        if (p instanceof S12PacketEntityVelocity) {
            int id = ((S12PacketEntityVelocity) p).func_149412_c();
            if (id == mc.thePlayer.getEntityId()) return knockback.value;
            return projectile(world.getEntityByID(id));
        }
        if (p instanceof S18PacketEntityTeleport) {
            return projectile(world.getEntityByID(((S18PacketEntityTeleport) p).func_149451_c()));
        }
        if (p instanceof S14PacketEntity) {
            Entity e = ((S14PacketEntity) p).func_149065_a(world);
            if (projectile(e)) return true;
            // Turning only, no movement: the head and body of another player.
            return heads.value && p instanceof S14PacketEntity.S16PacketEntityLook
                && e instanceof EntityPlayer
                && e != mc.thePlayer;
        }
        if (p instanceof S19PacketEntityHeadLook) {
            Entity e = ((S19PacketEntityHeadLook) p).func_149381_a(world);
            return heads.value && e instanceof EntityPlayer && e != mc.thePlayer;
        }
        return false;
    }

    private boolean projectile(Entity e) {
        return e instanceof EntityEnderPearl && pearls.value || e instanceof EntityPotion && potions.value;
    }
}
