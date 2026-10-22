import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The app's data layer + Android shims, in its own module so its huge
// generated tables compile once instead of on every UI edit.
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

val app = rootDir.resolve("../app/src/main/kotlin/com/pokedaisy/app")
val appSrc = fileTree(app) {
    listOf("companion/data/**", "companion/*.kt", "companion/i18n/**", "MgbaCore.kt", "Prefs.kt", "cheats/Cheats.kt", "GbaControls.kt", "Hotkeys.kt")
        .forEach { include(it) }
}
sourceSets["main"].kotlin.srcDir("shims")
tasks.named<KotlinCompile>("compileKotlin") { source(appSrc) }
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { api(compose.desktop.currentOs) }

// Fast JVM checks for the Black 2 reader, without an Android SDK or a supplied ROM.
sourceSets["test"].kotlin.apply {
    srcDir(rootDir.resolve("../app/src/test/kotlin"))
    include("**/Gen5DecodeTest.kt")
}
dependencies { testImplementation("junit:junit:4.13.2") }
