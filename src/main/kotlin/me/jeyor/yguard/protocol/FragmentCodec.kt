package me.jeyor.yguard.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class AttestationFragment(
    val sessionId: UUID,
    val attempt: Int,
    val index: Int,
    val count: Int,
    val data: ByteArray,
)

object FragmentCodec {
    fun decode(payload: ByteArray): AttestationFragment {
        if (payload.size !in (ProtocolConstants.FRAGMENT_HEADER_BYTES + 1)..ProtocolConstants.MAX_FRAGMENT_PAYLOAD_BYTES) {
            throw ProtocolException("Invalid fragment payload size")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val sessionId = UUID(buffer.long, buffer.long)
        val attempt = buffer.int
        val index = buffer.int
        val count = buffer.int
        val dataLength = buffer.int
        if (attempt !in 1..3) {
            throw ProtocolException("Invalid fragment attempt")
        }
        if (count !in 1..ProtocolConstants.MAX_FRAGMENT_COUNT || index !in 0 until count) {
            throw ProtocolException("Invalid fragment position")
        }
        if (dataLength <= 0 || dataLength > ProtocolConstants.MAX_FRAGMENT_DATA_BYTES || dataLength != buffer.remaining()) {
            throw ProtocolException("Invalid fragment data length")
        }
        val data = ByteArray(dataLength)
        buffer.get(data)
        return AttestationFragment(sessionId, attempt, index, count, data)
    }

    fun encode(fragment: AttestationFragment): ByteArray {
        if (fragment.attempt !in 1..3 || fragment.count !in 1..ProtocolConstants.MAX_FRAGMENT_COUNT || fragment.index !in 0 until fragment.count) {
            throw ProtocolException("Invalid fragment metadata")
        }
        if (fragment.data.isEmpty() || fragment.data.size > ProtocolConstants.MAX_FRAGMENT_DATA_BYTES) {
            throw ProtocolException("Invalid fragment data length")
        }
        return ByteBuffer.allocate(ProtocolConstants.FRAGMENT_HEADER_BYTES + fragment.data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(fragment.sessionId.mostSignificantBits)
            .putLong(fragment.sessionId.leastSignificantBits)
            .putInt(fragment.attempt)
            .putInt(fragment.index)
            .putInt(fragment.count)
            .putInt(fragment.data.size)
            .put(fragment.data)
            .array()
    }
}

class FragmentAssembly {
    private var expectedCount = -1
    private var totalBytes = 0
    private var completed = false
    private val fragments = HashMap<Int, ByteArray>()

    val fragmentCount: Int
        get() = fragments.size

    fun add(fragment: AttestationFragment): ByteArray? {
        if (completed) {
            throw ProtocolException("Fragment assembly is already complete")
        }
        if (expectedCount == -1) {
            expectedCount = fragment.count
        } else if (expectedCount != fragment.count) {
            throw ProtocolException("Fragment count changed")
        }
        if (fragments.containsKey(fragment.index)) {
            throw ProtocolException("Duplicate fragment")
        }
        if (totalBytes + fragment.data.size > ProtocolConstants.MAX_ENVELOPE_BYTES) {
            throw ProtocolException("Envelope exceeds maximum size")
        }
        fragments[fragment.index] = fragment.data.copyOf()
        totalBytes += fragment.data.size
        if (fragments.size != expectedCount) {
            return null
        }
        val envelope = ByteArray(totalBytes)
        var offset = 0
        for (index in 0 until expectedCount) {
            val data = fragments[index] ?: throw ProtocolException("Missing fragment")
            data.copyInto(envelope, offset)
            offset += data.size
        }
        completed = true
        fragments.clear()
        totalBytes = 0
        return envelope
    }

    fun clear() {
        fragments.clear()
        totalBytes = 0
        completed = true
    }
}
