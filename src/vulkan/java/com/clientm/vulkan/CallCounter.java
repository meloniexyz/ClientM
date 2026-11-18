package com.clientm.vulkan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Diagnostic: how often each backend command is called, logged every 10 seconds when CLIENTM_VK_COUNT is set. */
@me.eigenraven.lwjgl3ify.api.Lwjgl3Aware
final class CallCounter {

    private static final boolean ON = System.getenv("CLIENTM_VK_COUNT") != null;
    private static final Logger LOG = LogManager.getLogger("ClientM/Vulkan");
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static long last = System.nanoTime();

    private CallCounter() {}

    static void hit(String name) {
        if (!ON) return;
        COUNTS.computeIfAbsent(name, k -> new LongAdder())
            .increment();
        long now = System.nanoTime();
        if (now - last > 10_000_000_000L) {
            last = now;
            StringBuilder sb = new StringBuilder("Backend calls so far:");
            COUNTS.entrySet()
                .stream()
                .sorted(
                    (a, b) -> Long.compare(
                        b.getValue()
                            .sum(),
                        a.getValue()
                            .sum()))
                .forEach(
                    e -> sb.append(' ')
                        .append(e.getKey())
                        .append('=')
                        .append(
                            e.getValue()
                                .sum()));
            LOG.info(sb.toString());
        }
    }
}
