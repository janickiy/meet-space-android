package com.meetspace.core.ssh

import com.meetspace.core.model.SshFailure
import com.meetspace.core.model.SshFailureKind
import java.nio.ByteBuffer
import java.util.Base64

/** Bound attacker-controlled KDF work before JSch parses or decrypts an imported key. */
internal object PrivateKeyPolicy {
    fun validate(bytes: ByteArray) {
        if (bytes.size !in 32..256 * 1024) throw formatFailure()
        when {
            startsWith(bytes, "-----BEGIN OPENSSH PRIVATE KEY-----") -> validateOpenSsh(bytes)
            startsWith(bytes, "-----BEGIN RSA PRIVATE KEY-----") -> validateConventionalPem(bytes, "RSA")
            startsWith(bytes, "-----BEGIN EC PRIVATE KEY-----") -> validateConventionalPem(bytes, "EC")
            else -> throw formatFailure()
        }
    }

    private fun validateOpenSsh(bytes: ByteArray) {
        val decoded = decodePem(bytes, "OPENSSH", allowEncryptionHeaders = false)
        try {
            val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
            if (decoded.size < magic.size || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) throw formatFailure()
            val reader = WireReader(ByteBuffer.wrap(decoded).apply { position(magic.size) })
            val cipher = reader.ascii(64)
            val kdf = reader.ascii(32)
            val options = reader.bytes(256)
            try {
                when {
                    cipher == "none" && kdf == "none" && options.isEmpty() -> Unit
                    cipher in setOf("aes128-ctr", "aes192-ctr", "aes256-ctr", "aes128-cbc", "aes192-cbc", "aes256-cbc") && kdf == "bcrypt" -> {
                        val kdfReader = WireReader(ByteBuffer.wrap(options))
                        val salt = kdfReader.bytes(64)
                        try {
                            if (salt.size !in 16..64) throw formatFailure()
                            val rounds = kdfReader.integer()
                            if (rounds !in 1..64) throw SshFailure(SshFailureKind.KEY_FORMAT,
                                "OpenSSH-ключ использует слишком дорогую KDF. Поддерживается bcrypt от 1 до 64 раундов; сохраните отдельную копию ключа с совместимыми параметрами.")
                            kdfReader.end()
                        } finally { salt.fill(0) }
                    }
                    else -> throw formatFailure()
                }
                if (reader.integer() != 1) throw formatFailure()
                reader.skip(16, 8192) // One public key.
                reader.skip(16, 64 * 1024) // Encrypted or clear private key payload.
                reader.end()
            } finally { options.fill(0) }
        } catch (failure: SshFailure) { throw failure }
        catch (_: Exception) { throw formatFailure() }
        finally { decoded.fill(0) }
    }

    private fun validateConventionalPem(bytes: ByteArray, type: String) {
        // Traditional RSA/EC PEM has no user-controlled PBKDF iteration count. PKCS8 and PuTTY
        // are intentionally rejected rather than accepting unbounded PBKDF2/scrypt/Argon2.
        val decoded = decodePem(bytes, type, allowEncryptionHeaders = true)
        try {
            if (decoded.size !in 32..16 * 1024) throw formatFailure()
            validateDer(decoded, type)
        }
        finally { decoded.fill(0) }
    }

    private fun decodePem(bytes: ByteArray, type: String, allowEncryptionHeaders: Boolean): ByteArray {
        val begin = "-----BEGIN $type PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
        val end = "-----END $type PRIVATE KEY-----".toByteArray(Charsets.US_ASCII)
        if (!bytes.take(begin.size).toByteArray().contentEquals(begin)) throw formatFailure()
        var position = begin.size
        if (position < bytes.size && bytes[position] == '\r'.code.toByte()) position++
        if (position >= bytes.size || bytes[position++] != '\n'.code.toByte()) throw formatFailure()
        val encoded = ByteArray(bytes.size)
        var encodedSize = 0
        var encryptionHeader = false
        var encryptionCipher = false
        var payloadStarted = false
        try {
            while (position < bytes.size) {
                val lineStart = position
                while (position < bytes.size && bytes[position] != '\n'.code.toByte()) position++
                var lineEnd = position
                if (lineEnd > lineStart && bytes[lineEnd - 1] == '\r'.code.toByte()) lineEnd--
                if (position < bytes.size) position++
                val length = lineEnd - lineStart
                if (length == end.size && bytes.copyOfRange(lineStart, lineEnd).contentEquals(end)) {
                    if ((encryptionHeader != encryptionCipher) || !payloadStarted || bytes.copyOfRange(position, bytes.size).any { !isWhitespace(it) }) throw formatFailure()
                    if (encryptionHeader) throw SshFailure(SshFailureKind.KEY_FORMAT,
                        "Зашифрованный традиционный PEM не поддерживается. Используйте отдельную копию ключа в формате OpenSSH v1 с парольной фразой.")
                    val compact = encoded.copyOf(encodedSize)
                    return try { Base64.getDecoder().decode(compact) }
                    catch (_: Exception) { throw formatFailure() }
                    finally { compact.fill(0) }
                }
                if (length == 0) continue
                if (allowEncryptionHeaders && !payloadStarted && length <= 256) {
                    // These ASCII lines contain cipher/IV metadata, never private key bytes.
                    if (startsWith(bytes, "Proc-Type: 4,ENCRYPTED", lineStart) && length == 22) {
                        if (encryptionHeader) throw formatFailure()
                        encryptionHeader = true
                        continue
                    }
                    if (startsWith(bytes, "DEK-Info: ", lineStart)) {
                        if (encryptionCipher) throw formatFailure()
                        val metadata = bytes.copyOfRange(lineStart, lineEnd).toString(Charsets.US_ASCII)
                        if (!metadata.matches(Regex("DEK-Info: (AES-(128|192|256)-CBC|DES-EDE3-CBC),[0-9A-Fa-f]{16,32}"))) throw formatFailure()
                        encryptionCipher = true
                        continue
                    }
                }
                payloadStarted = true
                for (index in lineStart until lineEnd) {
                    val byte = bytes[index]
                    if (byte.toInt().toChar() !in 'A'..'Z' && byte.toInt().toChar() !in 'a'..'z' && byte.toInt().toChar() !in '0'..'9' && byte.toInt().toChar() !in "+/=") throw formatFailure()
                    encoded[encodedSize++] = byte
                }
            }
            throw formatFailure()
        } finally { encoded.fill(0) }
    }

    private fun startsWith(bytes: ByteArray, text: String, offset: Int = 0): Boolean {
        val prefix = text.toByteArray(Charsets.US_ASCII)
        return bytes.size >= offset + prefix.size && prefix.indices.all { bytes[offset + it] == prefix[it] }
    }
    private fun isWhitespace(value: Byte) = value.toInt().toChar() in " \t\r\n"

    private fun validateDer(bytes: ByteArray, type: String) {
        val reader = DerReader(bytes)
        val root = reader.element(0, bytes.size)
        if (root.tag != 0x30 || root.end != bytes.size) throw formatFailure()
        val children = reader.children(root)
        if (type == "RSA") {
            val maximum = listOf(1, 1025, 8, 1025, 513, 513, 513, 513, 513)
            if (children.size != 9 || children.any { it.tag != 0x02 }) throw formatFailure()
            children.forEachIndexed { index, value ->
                if (value.size !in 1..maximum[index] || bytes[value.start].toInt() and 0x80 != 0) throw formatFailure()
            }
            if (children[0].size != 1 || bytes[children[0].start] != 0.toByte()) throw formatFailure()
        } else {
            if (children.size !in 2..4 || children[0].tag != 0x02 || children[0].size != 1 || bytes[children[0].start] != 1.toByte()) throw formatFailure()
            if (children[1].tag != 0x04 || children[1].size !in 32..66) throw formatFailure()
            if (children.drop(2).any { it.tag !in setOf(0xa0, 0xa1) || it.size !in 1..160 }) throw formatFailure()
            children.drop(2).forEach { reader.validateNested(it, 1) }
        }
    }

    private data class DerElement(val tag: Int, val start: Int, val end: Int) { val size: Int get() = end - start }
    private class DerReader(private val bytes: ByteArray) {
        fun element(offset: Int, limit: Int): DerElement {
            if (offset < 0 || offset + 2 > limit) throw formatFailure()
            var position = offset
            val tag = bytes[position++].toInt() and 0xff
            if (tag and 0x1f == 0x1f) throw formatFailure()
            var length = bytes[position++].toInt() and 0xff
            if (length and 0x80 != 0) {
                val count = length and 0x7f
                if (count !in 1..3 || position + count > limit) throw formatFailure()
                var value = 0L
                repeat(count) { value = (value shl 8) or (bytes[position++].toLong() and 0xff) }
                if (value > limit - position || value < 128) throw formatFailure()
                length = value.toInt()
            }
            if (length > limit - position) throw formatFailure()
            return DerElement(tag, position, position + length)
        }
        fun children(parent: DerElement): List<DerElement> {
            val children = mutableListOf<DerElement>()
            var position = parent.start
            while (position < parent.end) {
                if (children.size >= 16) throw formatFailure()
                val child = element(position, parent.end)
                children += child
                position = child.end
            }
            return children
        }
        fun validateNested(parent: DerElement, depth: Int) {
            if (depth > 8) throw formatFailure()
            if (parent.tag and 0x20 != 0) children(parent).forEach { validateNested(it, depth + 1) }
        }
    }

    private class WireReader(private val buffer: ByteBuffer) {
        fun integer(): Int {
            if (buffer.remaining() < 4) throw formatFailure()
            return buffer.int
        }
        fun bytes(maximum: Int): ByteArray {
            val size = integer()
            if (size !in 0..maximum || size > buffer.remaining()) throw formatFailure()
            return ByteArray(size).also(buffer::get)
        }
        fun ascii(maximum: Int): String {
            val bytes = bytes(maximum)
            return try {
                if (bytes.any { it !in 32..126 }) throw formatFailure()
                bytes.toString(Charsets.US_ASCII)
            } finally { bytes.fill(0) }
        }
        fun skip(minimum: Int, maximum: Int) {
            val size = integer()
            if (size !in minimum..maximum || size > buffer.remaining()) throw formatFailure()
            buffer.position(buffer.position() + size)
        }
        fun end() { if (buffer.hasRemaining()) throw formatFailure() }
    }

    private fun formatFailure() = SshFailure(SshFailureKind.KEY_FORMAT,
        "Поддерживаются OpenSSH v1 (bcrypt до 64 раундов) и незашифрованные традиционные RSA/EC PEM. PKCS8, PuTTY/PPK, повреждённые и другие форматы не поддерживаются.")
}
