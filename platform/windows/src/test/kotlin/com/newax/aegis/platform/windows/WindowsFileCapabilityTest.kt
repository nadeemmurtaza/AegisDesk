package com.newax.aegis.platform.windows

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.getOrNull
import com.newax.aegis.platform.isSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowsFileCapabilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = OperationContext.create("test", ActionOrigin.USER)

    private fun capability(base: File): WindowsFileCapability = WindowsFileCapability(baseDir = base)

    @Test
    fun writeReadStatListRoundTrip() {
        val capability = capability(temp.root)
        val hello = capability.write("notes/hello.txt", "hello windows", overwrite = true, context = context)
        assertTrue("expected write success, got $hello", hello.isSuccess())

        val read = capability.readText("notes/hello.txt")
        assertTrue("expected read success, got $read", read.isSuccess())
        assertEquals("hello windows", (read as CapabilityResult.Success).value)

        val stat = capability.stat("notes/hello.txt")
        assertTrue("expected stat success, got $stat", stat.isSuccess())
        val meta = (stat as CapabilityResult.Success).value
        assertEquals("hello windows".toByteArray(Charsets.UTF_8).size.toLong(), meta.sizeBytes)
        assertFalse(meta.isDirectory)

        val list = capability.list("notes")
        assertTrue("expected list success, got $list", list.isSuccess())
        assertEquals(listOf("hello.txt"), (list as CapabilityResult.Success).value.map { File(it.path).name })
    }

    @Test
    fun overwriteFalseRejectsExistingFiles() {
        val capability = capability(temp.root)
        assertTrue(capability.write("a.txt", "one", context = context).isSuccess())
        val second = capability.write("a.txt", "two", overwrite = false, context = context)
        assertTrue("expected typed failure, got $second", second is CapabilityResult.Failed)
        val value = capability.readText("a.txt").getOrNull()
        assertEquals("one", value) // first write survived
    }

    @Test
    fun moveCopyAndDelete() {
        val capability = capability(temp.root)
        assertTrue(capability.write("src.txt", "payload", context = context).isSuccess())

        assertTrue(capability.copy("src.txt", "copy.txt", context = context).isSuccess())
        assertEquals("payload", capability.readText("copy.txt").getOrNull())

        assertTrue(capability.move("copy.txt", "moved/dst.txt", context = context).isSuccess())
        assertEquals("payload", capability.readText("moved/dst.txt").getOrNull())
        assertTrue(capability.stat("copy.txt") is CapabilityResult.Failed)

        assertTrue(capability.delete("src.txt", context = context).isSuccess())
        assertTrue(capability.stat("src.txt") is CapabilityResult.Failed)
    }

    @Test
    fun sha256IsStableAndHex() {
        val capability = capability(temp.root)
        assertTrue(capability.write("hash.txt", "abc", context = context).isSuccess())
        val first = capability.sha256("hash.txt").getOrNull()
        val second = capability.sha256("hash.txt").getOrNull()
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            first,
        )
        assertEquals(first, second)
    }

    @Test
    fun pathsCannotEscapeTheBaseDirectory() {
        // Confine the capability to a subfolder; the file sits beside it, outside the base.
        val capability = capability(temp.newFolder("base"))
        val outside = temp.newFile("outside.txt")
        val escape = capability.readText(outside.absolutePath)
        assertTrue("expected traversal rejection, got $escape", escape is CapabilityResult.Failed)
        assertTrue((escape as CapabilityResult.Failed).error.contains("escapes the configured base directory"))
    }

    @Test
    fun missingFileFailsWithTypedResult() {
        val capability = capability(temp.root)
        assertTrue(capability.readText("nope.txt") is CapabilityResult.Failed)
        assertTrue(capability.stat("nope.txt") is CapabilityResult.Failed)
    }
}
