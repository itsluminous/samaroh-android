package com.itsluminous.samaroh.core.help

import java.io.File

/** Reads the canonical shared files the Gradle test config points at (never the asset copy). */
object SharedHelpFiles {
    private fun dir(property: String): File =
        File(checkNotNull(System.getProperty(property)) { "system property $property not set (see core/help/build.gradle.kts)" })

    val helpDir: File get() = dir("samaroh.sharedHelpDir")
    val permissionsDir: File get() = dir("samaroh.sharedPermissionsDir")
    val stringsDir: File get() = dir("samaroh.sharedStringsDir")

    fun guide(): HelpGuide = HelpGuide.parse(File(helpDir, "guide.json").readText())

    fun routes(): HelpRoutes = HelpRoutes.parse(File(helpDir, "routes.json").readText())

    /** Every catalog key of locale [locale] across the base catalog and all fragments (what gen-android merges). */
    fun catalogKeys(locale: String): Set<String> {
        val files =
            listOf(File(stringsDir, "catalog.$locale.json")) +
                (File(stringsDir, "fragments").listFiles { f -> f.name.endsWith(".$locale.json") }?.toList() ?: emptyList())
        val keyRegex = Regex("^\\s{2}\"([a-z0-9_.]+)\"\\s*:\\s*\\{", RegexOption.MULTILINE)
        return files.flatMap { file -> keyRegex.findAll(file.readText()).map { it.groupValues[1] }.toList() }.toSet()
    }
}
