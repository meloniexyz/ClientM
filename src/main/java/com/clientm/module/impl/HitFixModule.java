package com.clientm.module.impl;

import java.util.Queue;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S0EPacketSpawnObject;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1DPacketEntityEffect;
import net.minecraft.network.play.server.S1EPacketRemoveEntityEffect;

import com.clientm.ClientM;
import com.clientm.mixins.AccessorNetworkManager;
import com.clientm.module.Category;
import com.clientm.module.Module;
import com.clientm.setting.BoolSetting;

/**
 * 1.7 handles what the server sends once per game tick (every 50 ms); 1.8 handles it on every rendered frame. With
 * this on, the start of every frame works through the waiting packets from the front, in the order they arrived, the
 * way 1.8 does. It stops at the first packet that has to wait for the tick, so nothing is ever handled out of order.
 *
 * Each option holds back one group of packets for the tick when switched off. Two kinds always wait for the tick: an
 * ender pearl appearing and entities being removed.
 */
public class HitFixModule extends Module {

    private enum Group {
        OTHER,
        HITS,
        POTIONS,
        PEARLS,
        SELF_KNOCKBACK,
        HEAD_MOVEMENT
    }

    private static HitFixModule instance;

    private final BoolSetting hits = add(new BoolSetting("hits", "Hits", true));
    private final BoolSetting potions = add(new BoolSetting("potions", "Potions", true));
    private final BoolSetting pearls = add(new BoolSetting("pearls", "Ender pearls", true));
    private final BoolSetting knockback = add(new BoolSetting("knockback", "Knockback (self knockback)", true));
    private final BoolSetting heads = add(new BoolSetting("heads", "Player head movement", true));
    private final BoolSetting hurtCamera17 = add(new BoolSetting("hurtcam17", "1.7 hurt camera", true));

    private boolean broken;
    private boolean logged;

    public HitFixModule() {
        super("hitfix", "Hit Fix", Category.MOVEMENT, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Server updates on every frame instead of every tick, like 1.8";
    }

    /** Whether the hurt camera should keep its 1.7 look; see MixinEntityRenderer_HurtCam17. */
    public static boolean hurtCamera17() {
        HitFixModule m = instance;
        return m != null && m.enabled && m.hurtCamera17.value;
    }

    /** Called at the start of every rendered frame. */
    public static void frame() {
        HitFixModule m = instance;
        if (m != null && m.enabled && !m.broken) m.run();
    }

    private void run() {
        if (mc.theWorld == null || mc.thePlayer == null) return;
        // A paused singleplayer game handles nothing until it is unpaused.
        if (mc.isSingleplayer() && mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) return;
        if (!(mc.getNetHandler() instanceof NetHandlerPlayClient)) return;
        NetHandlerPlayClient handler = mc.getNetHandler();
        try {
            NetworkManager network = handler.getNetworkManager();
            if (!network.isChannelOpen()) return;
            Queue<?> queue = ((AccessorNetworkManager) network).clientm$receivedPackets();
            for (int left = 1000; left > 0; left--) {
                Packet next = (Packet) queue.peek();
                if (next == null || !allowed(next)) return;
                Packet packet = (Packet) queue.poll();
                if (packet == null) return;
                packet.processPacket(handler);
                if (!logged) {
                    logged = true;
                    ClientM.LOG.info("Hit Fix is active");
                }
            }
        } catch (Throwable t) {
            broken = true;
            ClientM.LOG.warn("Hit Fix switched off after an error", t);
        }
    }

    private boolean allowed(Packet p) {
        if (mc.theWorld == null || mc.thePlayer == null) return false;
        if (p instanceof S0EPacketSpawnObject && ((S0EPacketSpawnObject) p).func_148993_l() == 65) return false;
        if (p instanceof S13PacketDestroyEntities) return false;
        switch (group(p, entityOf(p), mc.thePlayer)) {
            case HITS:
                return hits.value;
            case POTIONS:
                return potions.value;
            case PEARLS:
                return pearls.value;
            case SELF_KNOCKBACK:
                return knockback.value;
            case HEAD_MOVEMENT:
                return heads.value;
            default:
                return true;
        }
    }

    /** The entity the packet is about, for the packet kinds the groups need it for. */
    private Entity entityOf(Packet p) {
        if (p instanceof S19PacketEntityHeadLook) return ((S19PacketEntityHeadLook) p).func_149381_a(mc.theWorld);
        if (p instanceof S12PacketEntityVelocity)
            return mc.theWorld.getEntityByID(((S12PacketEntityVelocity) p).func_149412_c());
        if (p instanceof S14PacketEntity) return ((S14PacketEntity) p).func_149065_a(mc.theWorld);
        if (p instanceof S18PacketEntityTeleport)
            return mc.theWorld.getEntityByID(((S18PacketEntityTeleport) p).func_149451_c());
        return null;
    }

    private static Group group(Packet p, Entity entity, Entity self) {
        // Status 2 is "was hurt"; animation 1 is the red hurt flash.
        if (p instanceof S19PacketEntityStatus && ((S19PacketEntityStatus) p).func_149160_c() == 2) return Group.HITS;
        if (p instanceof S0BPacketAnimation && ((S0BPacketAnimation) p).func_148977_d() == 1) return Group.HITS;
        if (p instanceof S1DPacketEntityEffect || p instanceof S1EPacketRemoveEntityEffect) return Group.POTIONS;
        if (p instanceof S0EPacketSpawnObject) {
            // 73 is a thrown potion, 65 an ender pearl.
            int type = ((S0EPacketSpawnObject) p).func_148993_l();
            if (type == 73) return Group.POTIONS;
            if (type == 65) return Group.PEARLS;
        }
        if (p instanceof S19PacketEntityHeadLook && entity instanceof EntityPlayer) return Group.HEAD_MOVEMENT;
        if (p instanceof S14PacketEntity && ((S14PacketEntity) p).func_149060_h() && entity instanceof EntityPlayer)
            return Group.HEAD_MOVEMENT;
        if (p instanceof S12PacketEntityVelocity && entity != null && entity == self) return Group.SELF_KNOCKBACK;
        if (p instanceof S12PacketEntityVelocity || p instanceof S14PacketEntity
            || p instanceof S18PacketEntityTeleport) {
            if (entity instanceof EntityPotion) return Group.POTIONS;
            if (entity instanceof EntityEnderPearl) return Group.PEARLS;
        }
        return Group.OTHER;
    }
}
