package com.newax.aegis.platform.android

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.getOrNull
import com.newax.aegis.platform.isSuccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidFileCapabilityTest {

    private lateinit var baseDir: File
    private lateinit var capability: AndroidFileCapability
    private val context = OperationContext.create("test", ActionOrigin.USER)

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("aegis-file-test").toFile()
        capability = AndroidFileCapability(baseDir, resolver = null)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun writeReadStatListAndDeleteRoundTrip() {
        val path = "notes/hello.txt"
        assertTrue(capability.write(path, "hello aegis", overwrite = true, context = context).isSuccess())

        val read = capability.readText(path)
        assertTrue(read.isSuccess())
        assertEquals("hello aegis", (read as CapabilityResult.Success).value)

        val stat = capability.stat(path)
        assertTrue(stat.isSuccess())
        val metadata = (stat as CapabilityResult.Success).value
        assertEquals(11L, metadata.sizeBytes)
        assertTrue(!metadata.isDirectory)
        assertTrue(metadata.path.endsWith(path))

        val listing = capability.list("notes")
        assertTrue(listing.isSuccess())
        assertEquals(1, (listing as CapabilityResult.Success).value.size)

        assertTrue(capability.delete(path, context).isSuccess())
        assertTrue(capability.stat(path) is CapabilityResult.Failed)
    }

    @Test
    fun overwriteFalseRejectsExistingFile() {
        val path = "only-once.txt"
        assertTrue(capability.write(path, "first", overwrite = true, context = context).isSuccess())
        val second = capability.write(path, "second", overwrite = false, context = context)
        assertTrue(second is CapabilityResult.Failed)
        assertEquals("first", (capability.readText(path) as CapabilityResult.Success).value)
    }

    @Test
    fun copyAndMoveWork() {
        assertTrue(capability.write("a.txt", "payload", overwrite = true, context = context).isSuccess())
        assertTrue(capability.copy("a.txt", "b.txt", context).isSuccess())
        assertEquals("payload", (capability.readText("b.txt") as CapabilityResult.Success).value)

        assertTrue(capability.move("b.txt", "c.txt", context).isSuccess())
        assertTrue(capability.stat("b.txt") is CapabilityResult.Failed)
        assertEquals("payload", (capability.readText("c.txt") as CapabilityResult.Success).value)
    }

    @Test
    fun sha256MatchesKnownDigest() {
        assertTrue(capability.write("sum.txt", "abc", overwrite = true, context = context).isSuccess())
        val digest = capability.sha256("sum.txt")
        assertTrue(digest.isSuccess())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            (digest as CapabilityResult.Success).value,
        )
    }

    @Test
    fun pathEscapingBaseDirIsRejected() {
        val escape = File(baseDir.parentFile, "escape.txt")
        val write = capability.write("../escape.txt", "should not land", overwrite = true, context = context)
        assertTrue(write is CapabilityResult.Failed)
        assertTrue(!escape.exists())
    }

    @Test
    fun operationsOnMissingFilesFailWithTypedResults() {
        assertTrue(capability.readText("missing.txt") is CapabilityResult.Failed)
        assertTrue(capability.sha256("missing.txt") is CapabilityResult.Failed)
        assertTrue(capability.delete("missing.txt", context) is CapabilityResult.Failed)
        assertNull(capability.readBytes("missing.txt").getOrNull())
    }
}
