plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// Lets a launch try other Java settings without editing this file:
//   CLIENTM_HEAP=3G            fixed heap size
//   CLIENTM_JVM_ARGS="-XX:..."  extra JVM flags, separated by spaces
tasks.withType<JavaExec>().matching { it.name.startsWith("runClient") }.configureEach {
    System.getenv("CLIENTM_HEAP")?.let {
        minHeapSize = it
        maxHeapSize = it
    }
    System.getenv("CLIENTM_JVM_ARGS")?.let { jvmArgs(it.trim().split(" ")) }
}
