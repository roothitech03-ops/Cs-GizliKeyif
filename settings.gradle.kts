rootProject.name = "CloudstreamPlugins"

// Disabled plugins - jo compile nahi ho rahe
val disabled = listOf("__Temel", "ExampleProvider", "Sxyprn")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
