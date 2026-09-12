package com.bugcord.annotations

/**
 * Annotates the entrypoint of a plugin, used by manifest.json generation
 */
@Target(AnnotationTarget.CLASS)
annotation class BugcordPlugin(
    /**
     * Prompts the user to restart Bugcord after:
     * - Enabling manually
     * - Disabling manually
     * - Updating
     * - Uninstalling
     */
    val requiresRestart: Boolean = false
)
