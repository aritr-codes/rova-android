package com.aritr.rova.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Marker plugin whose ONLY job is to put [SourceCheckTask] (and the rest of this
 * build-logic module) on the applying project's buildscript classpath, so the
 * inline `tasks.register<SourceCheckTask>("checkX") { ... }` registrations in
 * app/build.gradle.kts compile.
 *
 * It deliberately does NO gate wiring: every gate is registered inline in
 * app/build.gradle.kts and wired into preBuild there (owner decision Q2). If a
 * future change wants to collapse the 46 registrations into this plugin, that is
 * a separate decision — keep apply() empty until then.
 */
class RovaChecksPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Intentionally empty — see class KDoc.
    }
}
