// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Launches and watches the Node agent (D11, D12, S3-R004…S3-R016).
 *
 * The token never leaves [AgentLauncher.LaunchSpec.stdin]: it is written to the
 * child's stdin and the pipe is closed immediately.
 */
class NodeAgentProcess(
    private val runtimeDirectory: File,
    private val homeDirectory: File,
    private val tmpDirectory: File,
    private val parentPid: Long,
    private val logger: (String) -> Unit = {},
    private val onStdoutLine: (String) -> Unit = {},
    private val onStderrLine: (String) -> Unit = {}
) {

    private var process: Process? = null
    private var stdoutReader: Thread? = null
    private var stderrReader: Thread? = null

    var pid: Long = -1L
        private set

    fun runtime(): AgentLauncher.RuntimeFiles = AgentLauncher.RuntimeFiles(runtimeDirectory)

    fun launch(port: Int, token: String, logLevel: String = AgentLauncher.LOG_LEVEL_DEFAULT): Boolean {
        if (process?.isAliveCompat() == true) return false

        val runtime = runtime()
        val missing = runtime.missingFile()
        if (missing != null) {
            logger("runtime incomplete: missing $missing")
            return false
        }

        val spec = AgentLauncher.build(
            runtime = runtime,
            port = port,
            token = token,
            logLevel = logLevel,
            parentPid = parentPid,
            homeDir = homeDirectory,
            tmpDir = tmpDirectory
        )

        val builder = ProcessBuilder(spec.argv)
            .directory(File(spec.workingDirectory))
            .redirectErrorStream(false)
        builder.environment().clear()
        builder.environment().putAll(spec.environment)

        val started = try {
            builder.start()
        } catch (failure: Exception) {
            logger("failed to start agent: ${failure.javaClass.simpleName}")
            return false
        }

        process = started
        pid = ProcessHandleCompat.pidOf(started)

        // Secrets go to stdin only (S3-R007), then the pipe is closed.
        try {
            OutputStreamWriter(started.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(spec.stdin)
                writer.flush()
            }
        } catch (failure: Exception) {
            logger("failed to deliver agent config over stdin: ${failure.javaClass.simpleName}")
            started.destroy()
            process = null
            return false
        }

        stdoutReader = pump(started.inputStream, "grom-agent-out", onStdoutLine)
        stderrReader = pump(started.errorStream, "grom-agent-err", onStderrLine)
        logger(AgentLauncher.describe(spec))
        return true
    }

    fun isAlive(): Boolean = process?.isAliveCompat() == true

    fun exitValue(): Int? = process?.let {
        if (it.isAliveCompat()) null else runCatching { it.exitValue() }.getOrNull()
    }

    fun stop() {
        val running = process ?: return
        runCatching { running.destroy() }
        val deadline = System.currentTimeMillis() + STOP_GRACE_MS
        while (running.isAliveCompat() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (running.isAliveCompat()) {
            runCatching { running.destroyForciblyCompat() }
        }
        process = null
        pid = -1L
        stdoutReader?.interrupt()
        stderrReader?.interrupt()
        stdoutReader = null
        stderrReader = null
    }

    private fun pump(
        stream: java.io.InputStream,
        name: String,
        sink: (String) -> Unit
    ): Thread = Thread({
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    sink(line.take(MAX_LINE_CHARS))
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // stream closed during shutdown
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    companion object {
        const val STOP_GRACE_MS: Long = 2_000
        const val MAX_LINE_CHARS: Int = 4 * 1024
    }
}

/** `Process.isAlive` is API 26+; degrade gracefully on anything older. */
internal object ProcessHandleCompat {

    fun pidOf(process: Process): Long = try {
        val method = process.javaClass.getMethod("pid")
        (method.invoke(process) as Number).toLong()
    } catch (_: Exception) {
        -1L
    }
}

internal fun Process.isAliveCompat(): Boolean = try {
    isAlive
} catch (_: NoSuchMethodError) {
    try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}

internal fun Process.destroyForciblyCompat(): Process = try {
    destroyForcibly()
} catch (_: NoSuchMethodError) {
    destroy()
    this
}
