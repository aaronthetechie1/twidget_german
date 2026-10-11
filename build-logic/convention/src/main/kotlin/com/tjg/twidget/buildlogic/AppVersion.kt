package com.tjg.twidget.buildlogic

import org.gradle.api.Project
import java.util.Properties

/**
 * Beta and stable retain their ordered Play version codes. Debug uses a separate
 * package and a fixed code, preserving upgrades from the first local debug APK.
 */
internal class AppVersion private constructor(
    val versionName: String,
    private val versionCodeBase: Int,
    private val betaNumber: Int,
) {
    val stableVersionCode: Int get() = versionCodeBase + 99

    fun versionNameFor(buildType: String?): String = when (buildType) {
        "beta" -> "$versionName-beta.$betaNumber"
        else -> versionName
    }

    fun versionCodeFor(buildType: String?): Int = when (buildType) {
        "debug" -> DEBUG_VERSION_CODE
        "beta" -> versionCodeBase + 79 + betaNumber
        else -> stableVersionCode
    }

    companion object {
        // Keep the code already installed on local phones, avoiding a downgrade.
        private const val DEBUG_VERSION_CODE = 100300198

        fun load(project: Project): AppVersion = with(project) {
            val versionProperties = Properties().apply {
                rootProject.file("version.properties").inputStream().use { load(it) }
            }
            val versionName = versionProperties.getProperty("versionName")
                ?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
                ?: error("version.properties must contain a semantic version such as versionName=1.0.0")
            val (major, minor, patch) = versionName.split('.').map(String::toInt)
            require(minor < 1_000 && patch < 1_000) {
                "Android version codes require version minor and patch components below 1000"
            }

            // The pre-release workflow supplies the beta number for each base version.
            val betaNumber = intProperty("betaNumber") ?: 1
            require(betaNumber > 0) { "betaNumber must be greater than zero" }
            require(betaNumber <= 18) {
                "Beta build number $betaNumber exceeds this version's Play Store slot range; bump versionName"
            }

            // Play rejects version codes above 2,100,000,000.
            val versionCodeBase = major * 100_000_000 + minor * 100_000 + patch * 100 + 100
            require(major in 0..20 && versionCodeBase + 99 <= 2_100_000_000) {
                "versionName $versionName cannot be represented as a Play Store version code"
            }
            AppVersion(versionName, versionCodeBase, betaNumber)
        }

        private fun Project.intProperty(name: String): Int? = providers.gradleProperty(name).orNull?.let { value ->
            value.toIntOrNull() ?: error("-P$name must be a whole number, got \"$value\"")
        }

    }
}
