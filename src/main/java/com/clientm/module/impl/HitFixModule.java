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
import com.clientm.setting.IntSetting;

/**
 * Takes the 50 ms game tick out of both directions of a fight.
 *
 * Coming in: 1.7 handles what the server sends once per game tick (every 50 ms); 1.8 handles it on every rendered
 * frame. With this on, the start of every frame works through the waiting packets from the front, in the order they
 * arrived, the way 1.8 does. It stops at the first packet that has to wait for the tick, so nothing is ever handled out
 * of order. Each packet option holds back one group of packets for the tick when switched off. Two kinds always wait
 * for the tick: an ender pearl appearing and entities being removed.
 *
 * Going out: 1.7 also reads attack clicks only in the game tick, so a click waits up to 50 ms before the swing and the
 * attack are sent. "Instant clicks" carries the click out on the frame it happens instead; the tick then skips that
 * same click. "Send packets immediately" turns off Nagle's algorithm on the server connection (1.7 switches it on, 1.8
 * off), so a small packet like an attack is not held back to be sent together with later ones. "Raw mouse input"
 * reads the mouse without Windows' pointer handling.
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
    // Off by default: they send hits and right-clicks between two movement packets instead of in the vanilla order,
    // which strict anticheats (Grim's "Post", Vulcan and similar BadPackets checks) can flag.
    private final BoolSetting instantClicks = add(
        new BoolSetting("instantAttack", "Instant clicks (risky: strict anticheats may flag)", false));
    private final BoolSetting instantUse = add(
        new BoolSetting("instantUse", "Instant right-clicks (risky: strict anticheats may flag)", false));
    private final IntSetting smoothing = add(
        new IntSetting("smoothing", "Enemy movement smoothing (ticks, vanilla 3)", 2, 1, 3));
    private final BoolSetting noDelay = add(new BoolSetting("nodelay", "Send packets immediately", true));
    private final BoolSetting rawInput = add(new BoolSetting("rawinput", "Raw mouse input", true));

    /** Time stamps of attack presses already carried out on their frame, which the game tick must not count again. */
    private final java.util.Set<Long> handled = new java.util.HashSet<>();
    /** The same, for right-clicks. */
    private final java.util.Set<Long> handledUse = new java.util.HashSet<>();
    private long lastUseNanos;
    private boolean clicking;
    private Object noDelayChannel;
    private Boolean rawApplied;

    /** For the click test tour: when the last swing happened, and how many there were. */
    public static volatile long lastSwingNs;
    public static volatile int swings;

    private boolean broken;
    private boolean logged;

    public HitFixModule() {
        super("hitfix", "Hit Fix", Category.MOVEMENT, true);
        instance = this;
    }

    @Override
    public String description() {
        return "Server updates on every frame instead of every tick; instant clicks are opt-in (anticheat risk)";
    }

    /**
     * Called when the game tick counts a key press for a key binding, while it reads the mouse queue. True when it is
     * an attack press this module already carried out on its frame: the press is then not counted, so the tick does
     * not attack a second time. Presses are matched exactly, by the time stamp of their mouse event.
     */
    public static boolean skipTickPress(int keyCode) {
        HitFixModule m = instance;
        if (DEBUG && m != null && keyCode == m.mc.gameSettings.keyBindAttack.getKeyCode()) ClientM.LOG.info(
            "CLICKDBG tick reads press {} button {} state {}",
            org.lwjgl.input.Mouse.getEventNanoseconds(),
            org.lwjgl.input.Mouse.getEventButton(),
            org.lwjgl.input.Mouse.getEventButtonState());
        if (m == null) return false;
        java.util.Set<Long> set = keyCode == m.mc.gameSettings.keyBindAttack.getKeyCode() ? m.handled
            : keyCode == m.mc.gameSettings.keyBindUseItem.getKeyCode() ? m.handledUse : null;
        if (set == null || set.isEmpty()) return false;
        long nanos = org.lwjgl.input.Mouse.getEventNanoseconds();
        if (!set.remove(nanos)) return false;
        if (DEBUG) ClientM.LOG.info("CLICKDBG tick skips press {}", nanos);
        return true;
    }

    /** For the click test tour: right-click actions, and how many of them happened on their frame. */
    public static volatile int uses, earlyUses;

    /** Called whenever the game's right-click action runs (counted for the click test tour). */
    public static void useAction() {
        uses++;
    }

    /** Called whenever the game's left-click action runs (counted for the click test tour). */
    public static void clickAction() {
        HitFixModule m = instance;
        if (m != null && m.clicking) return;
        lastSwingNs = System.nanoTime();
        swings++;
        if (DEBUG) ClientM.LOG.info("CLICKDBG tick click");
    }

    /** Called at the end of every game tick. */
    public static void tickEnd() {
        HitFixModule m = instance;
        if (m == null) return;
        // A press the tick never read (for example cancelled by another mod) is forgotten after a second.
        if (!m.handled.isEmpty() || !m.handledUse.isEmpty()) {
            long old = System.nanoTime() - 1_000_000_000L;
            m.handled.removeIf(n -> n < old);
            m.handledUse.removeIf(n -> n < old);
        }
        m.syncRawInput();
    }

    /**
     * How many ticks an enemy takes to slide to the position the server sent (vanilla 3, about 150 ms). Fewer ticks
     * show players, and the hitboxes you aim at, closer to where the server has them. Only what you see changes;
     * nothing is sent differently. See MixinNetHandlerPlayClient_Smoothing.
     */
    public static int smoothingTicks(int vanilla) {
        HitFixModule m = instance;
        return m != null && m.enabled ? Math.min(vanilla, m.smoothing.value) : vanilla;
    }

    /** Whether the hurt camera should keep its 1.7 look; see MixinEntityRenderer_HurtCam17. */
    public static boolean hurtCamera17() {
        HitFixModule m = instance;
        return m != null && m.enabled && m.hurtCamera17.value;
    }

    /** Called at the start of every rendered frame. */
    public static void frame() {
        HitFixModule m = instance;
        if (m == null) return;
        if (m.enabled && !m.broken) m.run();
        m.instantClick();
        m.noDelay();
    }

    // lwjgl3ify's mouse event queue (a ring of events the game tick has not read yet), read without taking anything
    private static java.lang.reflect.Field queueField, buttonsField, statesField, nanosField;
    private static java.lang.reflect.Field countField, readField, maxField;
    private long lastPressNanos;
    private static final boolean DEBUG = "clicks".equals(System.getenv("CLIENTM_TOUR"));

    /**
     * Carries out new attack clicks now instead of at the next game tick. The presses are found in the mouse events
     * the game has received but not read yet; they stay there, and the tick skips them when it reads them.
     */
    private void instantClick() {
        if (!enabled || !instantClicks.value && !instantUse.value) return;
        int key = mc.gameSettings.keyBindAttack.getKeyCode();
        int button = key < 0 && key + 100 >= 0 ? key + 100 : -1; // -1: attack is on a keyboard key
        try {
            if (queueField == null) {
                Class<?> mouse = Class.forName("org.lwjglx.input.Mouse");
                queueField = open(mouse, "queue");
                buttonsField = open(mouse, "buttonEvents");
                statesField = open(mouse, "buttonEventStates");
                nanosField = open(mouse, "nanoTimeEvents");
                Class<?> queueClass = Class.forName("org.lwjglx.input.EventQueue");
                countField = open(queueClass, "eventCount");
                readField = open(queueClass, "readEventPos");
                maxField = open(queueClass, "maxEvents");
            }
            Object queue = queueField.get(null);
            int[] buttons = (int[]) buttonsField.get(null);
            boolean[] states = (boolean[]) statesField.get(null);
            long[] nanos = (long[]) nanosField.get(null);
            int count, read, max;
            synchronized (queue) {
                count = countField.getInt(queue);
                read = readField.getInt(queue);
                max = maxField.getInt(queue);
            }
            boolean ready = enabled && mc.thePlayer != null
                && mc.theWorld != null
                && mc.playerController != null
                && mc.currentScreen == null
                && mc.inGameHasFocus;
            int use = mc.gameSettings.keyBindUseItem.getKeyCode() + 100;
            for (int i = 0; i < count; i++) {
                // The queue moves its read position before reading, so unread event i sits one slot further on.
                int pos = (read + 1 + i) % max;
                if (!states[pos]) continue;
                if (buttons[pos] == button && nanos[pos] > lastPressNanos) {
                    lastPressNanos = nanos[pos];
                    // While eating, drinking or blocking the game ignores attack clicks; the tick does that as usual.
                    if (!ready || !instantClicks.value || mc.thePlayer.isUsingItem()) continue;
                    clicking = true;
                    try {
                        lastSwingNs = System.nanoTime();
                        swings++;
                        ((com.clientm.mixins.AccessorMinecraft_Click) mc).clientm$attackClick();
                        handled.add(nanos[pos]);
                        if (DEBUG) ClientM.LOG.info("CLICKDBG early click {}", nanos[pos]);
                    } finally {
                        clicking = false;
                    }
                } else if (use >= 0 && buttons[pos] == use && nanos[pos] > lastUseNanos) {
                    lastUseNanos = nanos[pos];
                    // Rod casts, potion throws and sword blocking, the same way; holding the button still repeats
                    // at the vanilla rate, from the game tick.
                    if (!ready || !instantUse.value || mc.thePlayer.isUsingItem()) continue;
                    earlyUses++;
                    ((com.clientm.mixins.AccessorMinecraft_Click) mc).clientm$useClick();
                    handledUse.add(nanos[pos]);
                    if (DEBUG) ClientM.LOG.info("CLICKDBG early use {}", nanos[pos]);
                }
            }
        } catch (Throwable t) {
            instantClicks.value = false;
            ClientM.LOG.warn("Instant clicks switched off after an error", t);
        }
    }

    private static java.lang.reflect.Field open(Class<?> c, String name) throws NoSuchFieldException {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Switches Nagle's algorithm off on the server connection, once per connection. */
    private void noDelay() {
        if (!enabled || !noDelay.value || mc.getNetHandler() == null) return;
        try {
            io.netty.channel.Channel channel = ((AccessorNetworkManager) mc.getNetHandler()
                .getNetworkManager()).clientm$channel();
            if (channel == null || channel == noDelayChannel) return;
            noDelayChannel = channel;
            if (channel.config()
                .setOption(io.netty.channel.ChannelOption.TCP_NODELAY, Boolean.TRUE))
                ClientM.LOG.info("Hit Fix: packets to the server are sent immediately");
        } catch (Throwable t) {
            noDelayChannel = null;
        }
    }

    /** Applies the raw mouse input option through lwjgl3ify (and its config file, for the next start). */
    private void syncRawInput() {
        boolean want = enabled && rawInput.value;
        if (rawApplied != null && rawApplied == want) return;
        rawApplied = want;
        try {
            java.lang.reflect.Field field = Class.forName("me.eigenraven.lwjgl3ify.core.Config")
                .getField("INPUT_RAW_MOUSE");
            if (field.getBoolean(null) != want) {
                field.setBoolean(null, want);
                Class.forName("org.lwjglx.opengl.Display")
                    .getMethod("lwjgl3ify$updateRawMouseMode", boolean.class)
                    .invoke(null, want);
                ClientM.LOG.info("Raw mouse input {}", want ? "on" : "off");
            }
            java.io.File file = new java.io.File(mc.mcDataDir, "config/lwjgl3ify.cfg");
            if (file.isFile()) {
                String text = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
                String changed = text.replaceAll("B:rawMouseInput=(true|false)", "B:rawMouseInput=" + want);
                if (!changed.equals(text))
                    java.nio.file.Files.write(file.toPath(), changed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            ClientM.LOG.info("Raw mouse input cannot be changed here ({})", t.toString());
        }
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
