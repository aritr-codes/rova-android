package com.aritr.rova.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Config-cache-safe, cacheable host for every Rova static gate.
 *
 * The @TaskAction NEVER references Project/rootDir/file(...). It reads the
 * declared [sources] into pure [SourceFile]s and dispatches to the verbatim-
 * lifted rule in [RovaGateRules] by [checkId]. Behaviour (rule, comment-skip,
 * scope, message) is identical to the in-script gate it replaces.
 *
 * NOTE: no @SkipWhenEmpty — REQUIRE/single-file/count gates must be able to fail
 * on empty input (codex review). Forbid gates pass on empty input via their rule.
 */
@CacheableTask
abstract class SourceCheckTask : DefaultTask() {

    /** Exactly the file set the old gate scanned; fingerprinted for UP-TO-DATE. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Selects the verbatim rule; part of the cache key so two gates with the
     *  same sources never share a result. */
    @get:Input
    abstract val checkId: Property<String>

    /** Base for relPath ONLY (reproduces old `relativeTo(rootDir)`).
     *  @Internal so a checkout path never affects pass/fail or the cache key. */
    @get:Internal
    abstract val reportBaseDir: DirectoryProperty

    /** Success-only sentinel: written ONLY when the rule passes, so a failed gate
     *  is never UP-TO-DATE and a later violating edit re-runs (and re-fails). */
    @get:OutputFile
    abstract val sentinel: RegularFileProperty

    @TaskAction
    fun run() {
        val base = reportBaseDir.get().asFile.toPath()
        // Deterministic order (codex): sort by relPath so multi-offender reports
        // are stable across OS/FS instead of inheriting walkTopDown() order.
        val files = sources.files
            .filter { it.isFile }
            .map { f ->
                val text = f.readText(Charsets.UTF_8)
                SourceFile(
                    relPath = base.relativize(f.toPath()).toString().replace('\\', '/'),
                    lines = f.readLines(Charsets.UTF_8),
                    text = text,
                )
            }
            .sortedBy { it.relPath }

        val message = RovaGateRules.run(checkId.get(), files)
        if (message != null) {
            // Throw BEFORE writing the sentinel: a failed gate must never cache success.
            throw GradleException(message)
        }
        sentinel.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}
