plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Resolve once so the Android package and shaded JAR use the same Git version.
val gitRoot = gitOutput("rev-parse", "--show-toplevel")
val isGitCheckout = gitRoot.isNotBlank() && file(gitRoot).canonicalFile == rootProject.projectDir.canonicalFile
val gitRevision = if (isGitCheckout) gitOutput("rev-parse", "--verify", "HEAD") else ""
extra["extensionVersionName"] = "v${gitRevision.take(7).ifBlank { "1.0.0" }}"
extra["extensionVersionCode"] =
    if (gitRevision.isNotBlank()) gitOutput("rev-list", "--count", "HEAD").toIntOrNull()?.coerceAtLeast(1) ?: 1 else 1

fun gitOutput(vararg args: String): String =
    providers
        .exec {
            workingDir(rootProject.projectDir)
            isIgnoreExitValue = true
            commandLine("git", *args)
        }.standardOutput.asText
        .get()
        .trim()
