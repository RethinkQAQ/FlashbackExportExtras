import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    // Applies the correct Loom variant for the active Minecraft version.
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group directly; each Stonecutter version supplies it from its gradle.properties.
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava = if (sc.current.parsed >= "26.1") {
    JavaVersion.VERSION_25
} else {
    JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
    maven("https://jitpack.io") { name = "JitPack" }
    maven("https://maven.bawnorton.com/releases") { name = "Bawnorton" }
    maven("https://maven.shedaniel.me/")
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()


    val lwjglVersion = if (sc.current.parsed >= "26.1") "3.4.1" else "3.3.3"
//    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion")

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    val fabricApiVersion = property("deps.fabric_api") as String
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("maven.modrinth:flashback:${property("deps.flashback")}-fabric,${sc.current.version}")

    // Optional compatibility targets: available to the compiler and local
    // development runtime, but never declared as production requirements.
    modCompileOnly("maven.modrinth:sodium:${property("deps.sodium")}")
    modLocalRuntime("maven.modrinth:sodium:${property("deps.sodium")}")
    modCompileOnly("maven.modrinth:iris:${property("deps.iris")}")
    modLocalRuntime("maven.modrinth:iris:${property("deps.iris")}")

    // Iris ships JCPP as a nested jar. Loom's development runtime does not
    // expose nested mod jars on the runClient classpath, so provide the same
    // library explicitly for shaderpack parsing. This is runtime-only and is
    // not bundled into Flashback Plus or declared as a mod dependency.
    runtimeOnly("org.anarres:jcpp:1.4.14")
    // Iris also embeds these shader transformation libraries. Loom does not
    // put nested Iris jars on runClient's classpath, so expose them explicitly
    // for development without adding them to the produced mod.
    runtimeOnly("io.github.douira:glsl-transformer:3.0.0-pre3")
    runtimeOnly("org.antlr:antlr4-runtime:4.13.1")
    runtimeOnly("org.antlr:antlr4:4.13.1")

    modLocalRuntime("com.moulberry:mixinconstraints:1.0.8")
    if (sc.current.parsed < "26.1") {
        // Flashback 0.42.x embeds its 26.1-specific Lattice version.
        modLocalRuntime("com.moulberry:lattice:1.3.1")
    }
    modLocalRuntime("com.github.bawnorton.mixinsquared:mixinsquared-fabric:0.3.7-beta.1")

    val hdrMod = findProperty("deps.hdr_mod") as String?
    if (!hdrMod.isNullOrBlank()) {
        // HDR is integrated through an optional Mixin, so its classes are now
        // regular compile/runtime dependencies for HDR-enabled versions.
        modImplementation("maven.modrinth:tycXenOB:$hdrMod")
        modImplementation("me.shedaniel.cloth:cloth-config-fabric:${property("deps.cloth_config")}")
        modImplementation("dev.architectury:architectury-fabric:${property("deps.architectury")}")
    }

    // 26.x ships LWJGL 3.4.1. TinyEXR must use the same binding version as
    // Minecraft's LWJGL core; mixing 3.3.3 TinyEXR with 3.4.1 core fails at
    // runtime with EXRHeader.NoSuchFieldError (Unsafe field layout changed).
    val lwjglNatives = when {
        org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
            if (org.gradle.internal.os.OperatingSystem.current().nativePrefix.contains("aarch64")) {
                "natives-macos-arm64"
            } else {
                "natives-macos"
            }
        org.gradle.internal.os.OperatingSystem.current().isLinux -> "natives-linux"
        else -> "natives-windows"
    }

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl-tinyexr")
    runtimeOnly("org.lwjgl:lwjgl-tinyexr::$lwjglNatives")
    include("org.lwjgl:lwjgl-tinyexr:$lwjglVersion")
    include(dependencies.create("org.lwjgl:lwjgl-tinyexr:$lwjglVersion:$lwjglNatives"))
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")
    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        // Each Minecraft version needs an isolated Loom/Fabric runtime state.
        // Sharing `run` causes classTweaker namespace mismatches when switching versions.
        runDirectory = rootProject.file("run/${sc.current.version}")
        jvmArguments.add("-Dmixin.debug.export=true")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

if ((findProperty("deps.hdr_mod") as String?).isNullOrBlank()) {
    // HDR export implementation is unavailable on the intermediate 1.21.4-1.21.8 versions.
    sourceSets.named("main") {
        java.exclude("**/HdrColorTransformShader.java")
        java.exclude("**/HdrFrameCapture.java")
        java.exclude("**/HdrVideoWriter.java")
    }
}

// 26.1.2 has no complete abstract HDR readback. Keep its export surface
// honest instead of compiling the obsolete raw-OpenGL HDR implementation.
if (sc.current.parsed >= "26.1") {
    sourceSets.named("main") {
        java.exclude("**/HdrColorTransformShader.java")
        java.exclude("**/HdrFrameCapture.java")
        java.exclude("**/gpu/LegacyOpenGlExportBackend.java")
    }
}

// Read Stonecutter's version properties before entering the task action.
// The values are plain strings, so the task configuration remains safe for
// Gradle's configuration cache while still seeing properties injected by the
// version subproject.
val minecraftCompatibility = findProperty("mod.mc_compat") as String?
if (minecraftCompatibility != null) {
    val resourceProperties = mapOf(
        "id" to (findProperty("mod.id") as String),
        "name" to (findProperty("mod.name") as String),
        "version" to (findProperty("mod.version") as String),
        "minecraft" to minecraftCompatibility,
        "loader" to (findProperty("deps.fabric_loader") as String)
    )
    val mixinResourceProperties = mapOf(
        "java" to "JAVA_${requiredJava.majorVersion}"
    )

    tasks.withType<ProcessResources>().configureEach {
        inputs.properties(resourceProperties)
        inputs.properties(mixinResourceProperties)

        filesMatching("fabric.mod.json") {
            expand(resourceProperties)
        }

        // The mixin list is fixed; only the Java compatibility placeholder is
        // version-dependent.
        filesMatching("*.mixins.json") {
            expand(mixinResourceProperties)
        }

    }
}

tasks {

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to build/libs/{mod version}/"
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        val modVersion = providers.gradleProperty("mod.version").get()
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }
}
