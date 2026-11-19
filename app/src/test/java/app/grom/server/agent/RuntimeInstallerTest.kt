// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class RuntimeInstallerTest {

    private lateinit var root: File
    private lateinit var target: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("grom-runtime").toFile()
        target = File(root, "node")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun elf(machine: Int): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2          // 64-bit
        bytes[5] = 1          // little-endian
        bytes[18] = (machine and 0xFF).toByte()
        bytes[19] = ((machine shr 8) and 0xFF).toByte()
        return bytes
    }

    private fun sources(files: Map<String, ByteArray>): (String) -> InputStream? =
        { name -> files[name]?.let { ByteArrayInputStream(it) } }

    @Test
    fun `installs the script into the runtime directory`() {
        val outcome = RuntimeInstaller.install(
            target,
            listOf(RuntimeInstaller.SCRIPT_NAME),
            sources(mapOf(RuntimeInstaller.SCRIPT_NAME to "console.log(1)\n".toByteArray()))
        )
        assertEquals(listOf(RuntimeInstaller.SCRIPT_NAME), outcome.installed)
        assertEquals("console.log(1)\n", File(target, RuntimeInstaller.SCRIPT_NAME).readText())
    }

    @Test
    fun `installs an aarch64 binary and marks it executable`() {
        val outcome = RuntimeInstaller.install(
            target,
            listOf(RuntimeInstaller.BINARY_NAME),
            sources(mapOf(RuntimeInstaller.BINARY_NAME to elf(183)))
        )
        val binary = File(target, RuntimeInstaller.BINARY_NAME)
        assertEquals(listOf(RuntimeInstaller.BINARY_NAME), outcome.installed)
        assertTrue(binary.isFile)
        assertTrue(binary.canExecute())
    }

    @Test
    fun `rejects a non-aarch64 binary and leaves the existing one untouched`() {
        val binary = File(target.also { it.mkdirs() }, RuntimeInstaller.BINARY_NAME)
        binary.writeBytes(elf(183))
        assertTrue(binary.setExecutable(true))

        val outcome = RuntimeInstaller.install(
            target,
            listOf(RuntimeInstaller.BINARY_NAME),
            sources(mapOf(RuntimeInstaller.BINARY_NAME to elf(62)))
        )

        assertEquals(listOf(RuntimeInstaller.BINARY_NAME), outcome.rejected)
        assertFalse(outcome.complete)
        assertEquals(183, binary.readBytes()[18].toInt() and 0xFF)
    }

    @Test
    fun `rejects a file that is not an ELF at all`() {
        val outcome = RuntimeInstaller.install(
            target,
            listOf(RuntimeInstaller.BINARY_NAME),
            sources(mapOf(RuntimeInstaller.BINARY_NAME to "not an elf".toByteArray()))
        )
        assertEquals(listOf(RuntimeInstaller.BINARY_NAME), outcome.rejected)
        assertFalse(File(target, RuntimeInstaller.BINARY_NAME).exists())
    }

    @Test
    fun `a second run does not rewrite identical files`() {
        val files = mapOf(
            RuntimeInstaller.BINARY_NAME to elf(183),
            RuntimeInstaller.SCRIPT_NAME to "x\n".toByteArray()
        )
        RuntimeInstaller.install(target, files.keys.toList(), sources(files))
        val again = RuntimeInstaller.install(target, files.keys.toList(), sources(files))
        assertTrue(again.installed.isEmpty())
        assertEquals(files.keys.toList(), again.skipped)
    }

    @Test
    fun `missing assets do not delete an installed runtime`() {
        val files = mapOf(RuntimeInstaller.BINARY_NAME to elf(183))
        RuntimeInstaller.install(target, files.keys.toList(), sources(files))

        val outcome = RuntimeInstaller.install(target, files.keys.toList(), sources(emptyMap()))
        assertEquals(listOf(RuntimeInstaller.BINARY_NAME), outcome.skipped)
        assertTrue(File(target, RuntimeInstaller.BINARY_NAME).isFile)
    }

    @Test
    fun `an asset opener that throws is treated as absent`() {
        val outcome = RuntimeInstaller.install(
            target,
            listOf(RuntimeInstaller.SCRIPT_NAME)
        ) { throw IllegalStateException("boom") }
        assertEquals(listOf(RuntimeInstaller.SCRIPT_NAME), outcome.rejected)
        assertFalse(outcome.complete)
    }

    @Test
    fun `empty streams are rejected`() {
        val outcome = RuntimeInstaller.install(
            target,
            listOf(RuntimeInstaller.SCRIPT_NAME),
            sources(mapOf(RuntimeInstaller.SCRIPT_NAME to ByteArray(0)))
        )
        assertEquals(listOf(RuntimeInstaller.SCRIPT_NAME), outcome.rejected)
    }

    @Test
    fun `elf detection understands big-endian headers too`() {
        val bytes = elf(183).copyOf()
        bytes[5] = 2 // big-endian: e_machine is the same 183 on the wire
        bytes[18] = 0
        bytes[19] = 183.toByte()
        val file = File(root, "be.so")
        file.writeBytes(bytes)
        assertTrue(RuntimeInstaller.isAarch64Elf(file))
    }
}
