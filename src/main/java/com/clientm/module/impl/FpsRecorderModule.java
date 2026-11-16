package com.clientm.module.impl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import net.minecraft.entity.player.EntityPlayer;

import com.clientm.ClientM;
import com.clientm.module.Category;
import com.clientm.module.Module;

/**
 * Writes one line per second to clientm-fps-log.csv in the game folder while a world is open: the time, the frame
 * rate, the slowest frames of that second, how much there was to draw, and whether a fight was going on. It is for
 * looking at a play session afterwards, so nobody has to watch the FPS counter while fighting.
 */
public class FpsRecorderModule extends Module {

    private static FpsRecorderModule instance;

    private final SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final java.util.concurrent.ExecutorService disk = java.util.concurrent.Executors
        .newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ClientM FPS Recorder");
            t.setDaemon(true);
            return t;
        });
    /** Only touched on the recorder's own thread. */
    private BufferedWriter out;
    private volatile boolean broken;
    private long last;
    private long secondStart;
    private float[] frames = new float[8192];
    private int count;
    private boolean hit;
    private boolean swung;

    public FpsRecorderModule() {
        super("fpsrecorder", "FPS Recorder", Category.PERFORMANCE, false);
        instance = this;
    }

    @Override
    public String description() {
        return "Logs FPS every second to clientm-fps-log.csv";
    }

    @Override
    public void onDisable() {
        close();
    }

    /** Called at the start of every rendered frame. */
    public static void frame() {
        FpsRecorderModule m = instance;
        if (m != null && m.enabled && !m.broken) m.record();
    }

    private void record() {
        long now = System.nanoTime();
        if (mc.theWorld == null || mc.thePlayer == null) {
            last = 0;
            count = 0;
            return;
        }
        if (last == 0) {
            last = secondStart = now;
            return;
        }
        if (count == frames.length) frames = Arrays.copyOf(frames, count * 2);
        frames[count++] = (now - last) / 1e6F;
        last = now;
        hit |= mc.thePlayer.hurtTime > 0;
        swung |= mc.thePlayer.isSwingInProgress;
        if (now - secondStart >= 1_000_000_000L) {
            write((now - secondStart) / 1e9F);
            secondStart = now;
            count = 0;
            hit = swung = false;
        }
    }

    private void write(float seconds) {
        try {
            Arrays.sort(frames, 0, count);
            float worst = frames[count - 1];
            // The slowest hundredth of this second's frames, as a frame rate.
            int slow = Math.max(1, count / 100);
            float sum = 0;
            for (int i = count - slow; i < count; i++) sum += frames[i];
            int over = 0;
            for (int i = count - 1; i >= 0 && frames[i] > 5F; i--) over++;
            int players = 0;
            for (Object o : mc.theWorld.playerEntities) if (o instanceof EntityPlayer && o != mc.thePlayer) players++;
            String server = mc.func_147104_D() != null ? mc.func_147104_D().serverIP : "singleplayer";
            final String line = clock.format(new Date()) + ","
                + Math.round(count / seconds)
                + ","
                + Math.round(1000F * slow / sum)
                + ","
                + String.format(java.util.Locale.ROOT, "%.1f", worst)
                + ","
                + over
                + ","
                + mc.theWorld.loadedEntityList.size()
                + ","
                + players
                + ","
                + mc.effectRenderer.getStatistics()
                + ","
                + (hit ? 1 : 0)
                + ","
                + (swung ? 1 : 0)
                + ","
                + server
                + ","
                + com.clientm.Tags.VERSION;
            final File file = new File(mc.mcDataDir, "clientm-fps-log.csv");
            // The disk is never touched on the game thread: a slow disk would freeze the picture.
            disk.execute(() -> save(file, line));
        } catch (Throwable t) {
            broken = true;
            ClientM.LOG.info("FPS Recorder stopped ({})", t.toString());
        }
    }

    /** Runs on the recorder's own thread. */
    private void save(File file, String line) {
        try {
            if (out == null) {
                boolean fresh = !file.isFile() || file.length() == 0;
                out = new BufferedWriter(new FileWriter(file, true));
                if (fresh) {
                    out.write(
                        "time,fps,low1_fps,worst_ms,over_5ms,entities,players,particles,hit,swinging,server,version");
                    out.newLine();
                }
            }
            out.write(line);
            out.newLine();
            out.flush();
        } catch (Throwable t) {
            broken = true;
        }
    }

    private void close() {
        last = 0;
        count = 0;
        disk.execute(() -> {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {}
            out = null;
        });
    }
}
