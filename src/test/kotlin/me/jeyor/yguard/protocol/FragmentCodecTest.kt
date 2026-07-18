package me.jeyor.yguard.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FragmentCodecTest {
    private val sessionId = UUID.fromString("01020304-0506-0708-1112-131415161718")

    @Test
    fun `encodes exact big endian wire layout`() {
        val payload = FragmentCodec.encode(AttestationFragment(sessionId, 2, 0, 1, byteArrayOf(0x21, 0x22)))
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        assertEquals(sessionId.mostSignificantBits, buffer.long)
        assertEquals(sessionId.leastSignificantBits, buffer.long)
        assertEquals(2, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(1, buffer.int)
        assertEquals(2, buffer.int)
        assertEquals(0x21, buffer.get().toInt())
        assertEquals(0x22, buffer.get().toInt())
    }

    @Test
    fun `reassembles out of order fragments`() {
        val assembly = FragmentAssembly()

        assertNull(assembly.add(AttestationFragment(sessionId, 1, 1, 2, "world".toByteArray())))
        val result = assembly.add(AttestationFragment(sessionId, 1, 0, 2, "hello ".toByteArray()))

        assertContentEquals("hello world".toByteArray(), result)
    }

    @Test
    fun `rejects duplicate fragments and count changes`() {
        val duplicateAssembly = FragmentAssembly()
        val fragment = AttestationFragment(sessionId, 1, 0, 2, byteArrayOf(1))
        duplicateAssembly.add(fragment)

        assertFailsWith<ProtocolException> { duplicateAssembly.add(fragment) }

        val changedAssembly = FragmentAssembly()
        changedAssembly.add(AttestationFragment(sessionId, 1, 0, 2, byteArrayOf(1)))
        assertFailsWith<ProtocolException> {
            changedAssembly.add(AttestationFragment(sessionId, 1, 1, 3, byteArrayOf(2)))
        }
    }

    @Test
    fun `rejects aggregate envelope over limit`() {
        val assembly = FragmentAssembly()
        repeat(21) { index ->
            assembly.add(
                AttestationFragment(
                    sessionId,
                    1,
                    index,
                    22,
                    ByteArray(ProtocolConstants.MAX_FRAGMENT_DATA_BYTES),
                ),
            )
        }

        assertFailsWith<ProtocolException> {
            assembly.add(AttestationFragment(sessionId, 1, 21, 22, ByteArray(9_000)))
        }
    }

    @Test
    fun `rejects mismatched declared data length`() {
        val payload = FragmentCodec.encode(AttestationFragment(sessionId, 1, 0, 1, byteArrayOf(1, 2, 3)))
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(28, 2)

        assertFailsWith<ProtocolException> { FragmentCodec.decode(payload) }
    }
}
