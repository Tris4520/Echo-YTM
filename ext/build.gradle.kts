plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.gradle.shadow)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ktlint)
}

dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
    // Includes the extractor and the Kotlin-only Faraday/QuackJS cipher backend.
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.11.2")
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.echo.common)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.ktor.mock)
}

// Echo supplies Kotlin's runtime, as required by the template.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
}

ktlint {
    version.set("1.8.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// Extension properties goto `gradle.properties` to set values

val extType: String by project
val extId: String by project
val extClass: String by project

val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project

val extAuthor: String by project
val extAuthorUrl: String? by project

val extRepoUrl: String? by project
val extUpdateUrl: String? by project

val extensionVersionCode: Int by rootProject.extra
val extensionVersionName: String by rootProject.extra

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "dev.brahmkshatriya.echo.extension"
            artifactId = extId
            version = extensionVersionName

            from(components["java"])
        }
    }
}

tasks {
    shadowJar {
        archiveBaseName.set(extId)
        archiveVersion.set(extensionVersionName)
        mergeServiceFiles()
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        // Echo supplies older kotlinx.coroutines (1.10.x) and kotlinx.serialization (1.9.x) than InnerTubeX/Ktor need.
        // Echo's classloader prefers its own copies, so ship private, relocated ones instead.
        relocate("kotlinx.coroutines", "ytm.shaded.kotlinx.coroutines")
        relocate("kotlinx.serialization", "ytm.shaded.kotlinx.serialization")
        // These libraries exchange types with each other. Relocating Okio alone changes
        // ResponseBody.source()'s signature while Echo still supplies its own OkHttp.
        relocate("io.ktor", "ytm.shaded.io.ktor")
        relocate("okhttp3", "ytm.shaded.okhttp3")
        relocate("okio", "ytm.shaded.okio")
        relocate("kotlinx.io", "ytm.shaded.kotlinx.io")
        manifest {
            attributes(
                mapOf(
                    "Extension-Id" to extId,
                    "Extension-Type" to extType,
                    "Extension-Class" to extClass,
                    "Extension-Version-Code" to extensionVersionCode,
                    "Extension-Version-Name" to extensionVersionName,
                    "Extension-Icon-Url" to extIconUrl,
                    "Extension-Name" to extName,
                    "Extension-Description" to extDescription,
                    "Extension-Author" to extAuthor,
                    "Extension-Author-Url" to extAuthorUrl,
                    "Extension-Repo-Url" to extRepoUrl,
                    "Extension-Update-Url" to extUpdateUrl,
                ),
            )
        }
    }
}
