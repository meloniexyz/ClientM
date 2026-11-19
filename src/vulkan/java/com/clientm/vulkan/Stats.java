package com.clientm.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Development aid (CLIENTM_VK_STATS): per-frame counts of the things that cost GPU time, logged every 2000 frames. */
final class Stats {

    static final boolean ON = System.getenv("CLIENTM_VK_STATS") != null;
    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan/Stats");
    static long begins, barriers, stagedWrites, bufferCopies, uploads, draws, clears, frames;
    /** Per frame, for explaining slow frames. */
    static long fPipelines, fImages, fBuffers, fStaged, fUploads, fFlushes, fFenceNs, fAcquireNs, fPresentNs, fSubmitNs;
    private static long lastFrameNs, spikes;

    private Stats() {}

    // bucket 0: normal frames, 1: slow frames (over 1.5 ms), during a benchmark only
    private static final long[] n = new long[2];
    private static final double[][] sums = new double[2][11];
    private static boolean wasBenching;

    static void frame() {
        long now = System.nanoTime();
        double ms = lastFrameNs == 0 ? 0 : (now - lastFrameNs) / 1e6;
        lastFrameNs = now;
        boolean benching = System.getProperty("clientm.benching") != null;
        if (benching && ms > 0) {
            int b = ms > 1.5 ? 1 : 0;
            n[b]++;
            double[] v = { ms, fFenceNs / 1e6, fAcquireNs / 1e6, fSubmitNs / 1e6, fPresentNs / 1e6, fPipelines, fImages,
                fBuffers, fStaged, fUploads, fFlushes };
            for (int i = 0; i < v.length; i++) sums[b][i] += v[i];
            if (b == 1 && ms > 8 && spikes++ < 60) LOG.info(
                "spike {} ms: fence {} acquire {} submit {} present {} pipelines {} images {} buffers {} staged {} uploads {} flushes {}",
                String.format("%.1f", ms),
                v[1],
                v[2],
                v[3],
                v[4],
                fPipelines,
                fImages,
                fBuffers,
                fStaged,
                fUploads,
                fFlushes);
        }
        if (wasBenching && !benching) {
            String[] names = { "frame ms", "fence ms", "acquire ms", "submit ms", "present ms", "pipelines", "images",
                "buffers", "staged writes", "uploads", "flushes" };
            for (int b = 0; b < 2; b++) {
                StringBuilder sb = new StringBuilder(b == 0 ? "normal frames " : "slow frames ").append(n[b])
                    .append(':');
                for (int i = 0; i < names.length; i++)
                    sb.append(String.format(" %s %.4f", names[i], n[b] == 0 ? 0 : sums[b][i] / n[b]));
                LOG.info(sb.toString());
            }
        }
        wasBenching = benching;
        fPipelines = fImages = fBuffers = fStaged = fUploads = fFlushes = fFenceNs = fAcquireNs = fPresentNs = fSubmitNs = 0;
        if (++frames % 2000 != 0) return;
        double f = 2000.0;
        LOG.info(
            "per frame: rendering begins {}, barriers {}, staged buffer writes {}, buffer copies {}, texture uploads {}, draws {}, clears {}",
            begins / f,
            barriers / f,
            stagedWrites / f,
            bufferCopies / f,
            uploads / f,
            draws / f,
            clears / f);
        begins = barriers = stagedWrites = bufferCopies = uploads = draws = clears = 0;
    }
}
