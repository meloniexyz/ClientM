plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// The setup that measured best (2026-11-15, see ScreenshotTour's benchmark mode): Java 21 + LWJGL3 on OpenGL with a
// fixed 3 GB heap and these G1 flags. Same average FPS as Java 8, clearly higher 1% lows. `runClient21` is therefore
// the standard launch and gets these by default; plain `runClient` (Java 8, LWJGL2) is left untouched.
val tunedHeap = "3G"
val tunedFlags = listOf(
    "-XX:+UseG1GC",
    "-XX:MaxGCPauseMillis=30",
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:G1NewSizePercent=30",
    "-XX:+AlwaysPreTouch",
    "-XX:+DisableExplicitGC",
    "-XX:+ParallelRefProcEnabled",
)

// A launch can still try other settings without editing this file:
//   CLIENTM_HEAP=4G             fixed heap size
//   CLIENTM_JVM_ARGS="-XX:..."  extra JVM flags, separated by spaces (replaces the tuned set on runClient21)
tasks.withType<JavaExec>().matching { it.name.startsWith("runClient") }.configureEach {
    val modernJava = name != "runClient"
    val heap = System.getenv("CLIENTM_HEAP") ?: if (modernJava) tunedHeap else null
    heap?.let {
        minHeapSize = it
        maxHeapSize = it
    }
    val extra = System.getenv("CLIENTM_JVM_ARGS")?.trim()?.split(" ") ?: if (modernJava) tunedFlags else null
    extra?.let { jvmArgs(it) }
}
