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

// ---------------------------------------------------------------------------------------------------------------
// Experimental Vulkan renderer (src/vulkan). It plugs into Angelica as one more render backend. It is compiled on its
// own because it needs LWJGL 3 (Vulkan, SDL), whose package names clash with the LWJGL 2 the rest of the mod builds
// against. The main code only reaches it by class name, so the mod still loads where LWJGL 3 / Vulkan are missing.
// Sources live in src/vulkan/java and src/vulkan/resources (the defaults for a source set named "vulkan").
val vulkan: SourceSet by sourceSets.creating

dependencies {
    "vulkanCompileOnly"("com.github.GTNewHorizons:Angelica:2.2.46:dev") { isTransitive = false }
    "vulkanCompileOnly"("com.github.GTNewHorizons:lwjgl3ify:3.0.33:dev") { isTransitive = false }
    "vulkanCompileOnly"("org.lwjgl:lwjgl:3.4.2")
    "vulkanCompileOnly"("org.lwjgl:lwjgl-vulkan:3.4.2")
    "vulkanCompileOnly"("org.lwjgl:lwjgl-sdl:3.4.2")
    "vulkanCompileOnly"("org.lwjgl:lwjgl-shaderc:3.4.2")
    "vulkanCompileOnly"("org.lwjgl:lwjgl-spvc:3.4.2")
    // Angelica's records are compiled down to Java 8 and extend this library's Record stand-in.
    "vulkanCompileOnly"("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:1.3.5:downgraded-8")
    "vulkanCompileOnly"("org.apache.logging.log4j:log4j-api:2.0-beta9")
    // Vulkan's Java bindings for the Java 21/25 dev launches; on Windows they use the system's vulkan-1.dll.
    "lwjgl3Classpath"("org.lwjgl:lwjgl-vulkan:3.4.2")
    runtimeOnly(vulkan.output)
}

tasks.named<JavaCompile>("compileVulkanJava") {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    from(vulkan.output)
}
