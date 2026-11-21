// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ed25519 as specified by RFC 8032, implemented with [BigInteger] and
 * SHA-512 only. No third-party code and no Android APIs are used.
 *
 * The scheme is deterministic: the same seed and message always produce the
 * same signature. Everything here is a local value; the object keeps no
 * mutable state and is safe to use from several threads.
 */
object Ed25519 {

    const val PUBLIC_KEY_BYTES: Int = 32
    const val PRIVATE_SEED_BYTES: Int = 32
    const val SIGNATURE_BYTES: Int = 64

    private const val SCALAR_BYTES = 32

    /** 32 bytes from [random]. */
    fun generatePrivateSeed(random: SecureRandom = SecureRandom()): ByteArray {
        val seed = ByteArray(PRIVATE_SEED_BYTES)
        random.nextBytes(seed)
        return seed
    }

    /**
     * RFC 8032 section 5.1.5: hash the seed, clamp the first half into the
     * secret scalar and return the compressed encoding of `[a]B`.
     */
    fun derivePublicKey(seed: ByteArray): ByteArray {
        requireSeed(seed)
        val h = sha512(seed)
        val scalar = clampScalar(h)
        return encodePoint(multiplyByBase(scalar))
    }

    /**
     * RFC 8032 section 5.1.6: deterministic signature `R || S` over
     * [message] using the 32-byte [seed].
     */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        requireSeed(seed)
        val h = sha512(seed)
        val scalar = clampScalar(h)
        val prefix = h.copyOfRange(32, 64)
        val publicKey = encodePoint(multiplyByBase(scalar))

        val r = fromLittleEndian(sha512(prefix, message)).mod(L)
        val rEncoded = encodePoint(multiplyByBase(r))
        val k = fromLittleEndian(sha512(rEncoded, publicKey, message)).mod(L)
        val s = r.add(k.multiply(scalar)).mod(L)

        return rEncoded + toLittleEndian(s, SCALAR_BYTES)
    }

    /**
     * RFC 8032 section 5.1.7. Returns false — never throws — for malformed
     * keys or signatures, non-canonical `S`, failed point decompression or a
     * signature that does not satisfy `[S]B = R + [k]A`.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES) return false
        if (signature.size != SIGNATURE_BYTES) return false

        val rEncoded = signature.copyOfRange(0, 32)
        val s = fromLittleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false

        val a = decompress(publicKey) ?: return false
        val r = decompress(rEncoded) ?: return false

        val k = fromLittleEndian(sha512(rEncoded, publicKey, message)).mod(L)
        val expected = addPoints(r, multiply(a, k))
        val actual = multiplyByBase(s)
        return encodePoint(actual).contentEquals(encodePoint(expected))
    }

    /** First 8 bytes of SHA-256 as 16 lowercase hex characters. */
    fun keyId(publicKey: ByteArray): String {
        require(publicKey.size == PUBLIC_KEY_BYTES) {
            "public key must be $PUBLIC_KEY_BYTES bytes, got ${publicKey.size}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val value = digest[i].toInt() and 0xff
            sb.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return sb.toString()
    }

    private fun requireSeed(seed: ByteArray) {
        require(seed.size == PRIVATE_SEED_BYTES) {
            "seed must be $PRIVATE_SEED_BYTES bytes, got ${seed.size}"
        }
    }

    // Field and group parameters, RFC 8032 section 5.1.

    private val ZERO: BigInteger = BigInteger.ZERO
    private val ONE: BigInteger = BigInteger.ONE
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val THREE: BigInteger = BigInteger.valueOf(3)
    private val FOUR: BigInteger = BigInteger.valueOf(4)
    private val FIVE: BigInteger = BigInteger.valueOf(5)

    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.valueOf(2).pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(ONE).shiftRight(2), P)

    // Base point B: y = 4/5, x recovered by decompression with sign bit 0.
    private val BASE_Y: BigInteger = FOUR.multiply(FIVE.modInverse(P)).mod(P)
    private val BASE_X: BigInteger = recoverX(BASE_Y, sign = false)
        ?: throw IllegalStateException("Ed25519 base point failed to decompress")
    private val BASE_POINT: Point = Point(BASE_X, BASE_Y, ONE, BASE_X.multiply(BASE_Y).mod(P))

    private val IDENTITY: Point = Point(ZERO, ONE, ONE, ZERO)

    private val BYTE_MASK: BigInteger = BigInteger.valueOf(0xff)

    private val HEX = "0123456789abcdef".toCharArray()

    /** Extended homogeneous coordinates: x = X/Z, y = Y/Z, x*y = T/Z. */
    private data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger
    )

    /** RFC 8032 section 5.1.4, complete addition. */
    private fun addPoints(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).mod(P).multiply(q.y.subtract(q.x).mod(P)).mod(P)
        val b = p.y.add(p.x).mod(P).multiply(q.y.add(q.x).mod(P)).mod(P)
        val c = p.t.multiply(TWO_D).mod(P).multiply(q.t).mod(P)
        val d = p.z.multiply(q.z).shiftLeft(1).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P)
        )
    }

    /** RFC 8032 section 5.1.4, dedicated doubling. */
    private fun doublePoint(p: Point): Point {
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c = p.z.multiply(p.z).shiftLeft(1).mod(P)
        val h = a.add(b).mod(P)
        val xy = p.x.add(p.y).mod(P)
        val e = h.subtract(xy.multiply(xy).mod(P)).mod(P)
        val g = a.subtract(b).mod(P)
        val f = c.add(g).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P)
        )
    }

    /** Simple double-and-add; no modular inversion inside the loop. */
    private fun multiply(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var remaining = scalar
        while (remaining.signum() > 0) {
            if (remaining.testBit(0)) result = addPoints(result, addend)
            addend = doublePoint(addend)
            remaining = remaining.shiftRight(1)
        }
        return result
    }

    private fun multiplyByBase(scalar: BigInteger): Point = multiply(BASE_POINT, scalar)

    /** Compresses a point to `y` little-endian with the low bit of `x` on top. */
    private fun encodePoint(point: Point): ByteArray {
        val zInverse = point.z.modInverse(P)
        val x = point.x.multiply(zInverse).mod(P)
        val y = point.y.multiply(zInverse).mod(P)
        val encoded = toLittleEndian(y, PUBLIC_KEY_BYTES)
        if (x.testBit(0)) {
            encoded[PUBLIC_KEY_BYTES - 1] = (encoded[PUBLIC_KEY_BYTES - 1].toInt() or 0x80).toByte()
        }
        return encoded
    }

    /** RFC 8032 section 5.1.3. Returns null when the encoding is invalid. */
    private fun decompress(encoded: ByteArray): Point? {
        if (encoded.size != PUBLIC_KEY_BYTES) return null
        val copy = encoded.copyOf()
        val sign = (copy[PUBLIC_KEY_BYTES - 1].toInt() and 0x80) != 0
        copy[PUBLIC_KEY_BYTES - 1] = (copy[PUBLIC_KEY_BYTES - 1].toInt() and 0x7f).toByte()
        val y = fromLittleEndian(copy)
        if (y >= P) return null
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, ONE, x.multiply(y).mod(P))
    }

    /**
     * Recovers `x` for `x^2 = (y^2 - 1) / (d*y^2 + 1)` using the p = 5 (mod 8)
     * square-root formula, then selects the root matching [sign].
     */
    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        if (y.signum() < 0 || y >= P) return null
        val ySquared = y.multiply(y).mod(P)
        val u = ySquared.subtract(ONE).mod(P)
        val v = D.multiply(ySquared).add(ONE).mod(P)

        if (u.signum() == 0) {
            return if (sign) null else ZERO
        }

        val xSquared = u.multiply(v.modInverse(P)).mod(P)
        var x = xSquared.modPow(P.add(THREE).shiftRight(3), P)
        val vxSquared = v.multiply(x.multiply(x).mod(P)).mod(P)
        if (vxSquared != u) {
            if (vxSquared != P.subtract(u).mod(P)) return null
            x = x.multiply(SQRT_M1).mod(P)
            if (v.multiply(x.multiply(x).mod(P)).mod(P) != u) return null
        }

        if (x.signum() == 0) return if (sign) null else ZERO
        if (x.testBit(0) != sign) x = P.subtract(x)
        return x
    }

    /** RFC 8032 section 5.1.5 pruning of the first half of the digest. */
    private fun clampScalar(digest: ByteArray): BigInteger {
        val scalar = digest.copyOfRange(0, SCALAR_BYTES)
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[SCALAR_BYTES - 1] = (scalar[SCALAR_BYTES - 1].toInt() and 127).toByte()
        scalar[SCALAR_BYTES - 1] = (scalar[SCALAR_BYTES - 1].toInt() or 64).toByte()
        return fromLittleEndian(scalar)
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun toLittleEndian(value: BigInteger, length: Int): ByteArray {
        require(value.signum() >= 0) { "cannot encode a negative value" }
        val out = ByteArray(length)
        var remaining = value
        for (i in 0 until length) {
            out[i] = remaining.and(BYTE_MASK).toByte()
            remaining = remaining.shiftRight(8)
        }
        return out
    }

    private fun fromLittleEndian(bytes: ByteArray): BigInteger {
        if (bytes.isEmpty()) return ZERO
        val bigEndian = ByteArray(bytes.size)
        for (i in bytes.indices) {
            bigEndian[i] = bytes[bytes.size - 1 - i]
        }
        return BigInteger(1, bigEndian)
    }
}
