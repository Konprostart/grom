// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import app.grom.server.core.AgentConfig
import java.io.File

/**
 * Builds the exact process launch for the agent (D11, D24, S3-R004…S3-R009).
 *
 * Invariants enforced here and asserted by the JVM tests:
 *  - argv contains no secret and no user data;
 *  - the environment is constructed from scratch, never inherited;
 *  - the token travels only in [LaunchSpec.stdin].
 */
object AgentLauncher {

    const val BINARY_NAME: String = "libnode.so"
    const val SCRIPT_NAME: String = "grom-agent.js"
    const val LOG_LEVEL_DEFAULT: String = "info"

    data class RuntimeFiles(val directory: File) {
        val binary: File get() = File(directory, BINARY_NAME)
        val script: File get() = File(directory, SCRIPT_NAME)

        val present: Boolean get() = missingFile() == null

        /** The first missing prerequisite, or null when the runtime is complete. */
        fun missingFile(): String? = when {
            !directory.isDirectory -> directory.name
            !binary.isFile -> BINARY_NAME
            !binary.canExecute() -> "$BINARY_NAME (not executable)"
            !script.isFile -> SCRIPT_NAME
            else -> null
        }
    }

    data class LaunchSpec(
        val argv: List<String>,
        val environment: Map<String, String>,
        val stdin: String,
        val workingDirectory: String
    )

    fun build(
        runtime: RuntimeFiles,
        port: Int,
        token: String,
        logLevel: String,
        parentPid: Long,
        homeDir: File,
        tmpDir: File
    ): LaunchSpec {
        val missing = runtime.missingFile()
        require(missing == null) { "agent runtime incomplete: missing $missing" }

        val argv = listOf(
            runtime.binary.absolutePath,
            "--experimental-default-type=commonjs",
            runtime.script.absolutePath,
            "--port", port.toString(),
            "--parent-pid", parentPid.toString()
        )

        // Constructed from scratch (S3-R006): nothing is inherited.
        val environment = linkedMapOf(
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "/system/bin",
            "GROM_LOG_LEVEL" to logLevel,
            "LANG" to "C"
        )

        val stdin = AgentConfig.encode(token = token, port = port, logLevel = logLevel)

        return LaunchSpec(
            argv = argv,
            environment = environment,
            stdin = stdin,
            workingDirectory = runtime.directory.absolutePath
        )
    }

    /** Log-safe rendering of a launch: argv only, and argv never holds a secret. */
    fun describe(spec: LaunchSpec): String =
        "argv=${spec.argv.joinToString(" ")} envKeys=${spec.environment.keys.sorted()}"
}
