package com.clientm.vulkan;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Development aid (CLIENTM_VK_STATS): per-frame counts of the things that cost GPU time, logged every 2000 frames. */
final class Stats {

    static final boolean ON = System.getenv("CLIENTM_VK_STATS") != null;
    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan/Stats");
    static long begins, barriers, stagedWrites, bufferCopies, uploads, draws, clears, frames;

    private Stats() {}

    static void frame() {
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
