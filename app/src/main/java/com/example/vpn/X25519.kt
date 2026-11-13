package com.example.vpn

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Чистая реализация RFC 7748 Curve25519 (X25519) для генерации реальной пары ключей
 * при регистрации устройства в Cloudflare WARP API (`POST /v0a2158/reg`).
 */
object X25519 {

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT_U: BigInteger = BigInteger.valueOf(9)

    data class KeyPair(
        val privateKeyBase64: String,
        val publicKeyBase64: String
    )

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val privateKey = ByteArray(32)
        random.nextBytes(privateKey)
        clampPrivateKey(privateKey)
        val publicKey = publicFromPrivate(privateKey)
        return KeyPair(
            privateKeyBase64 = Base64.encodeToString(privateKey, Base64.NO_WRAP),
            publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        )
    }

    fun publicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        val k = privateKey.copyOf()
        clampPrivateKey(k)
        return scalarMult(k, encodeLittleEndian(BASE_POINT_U))
    }

    private fun clampPrivateKey(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
    }

    private fun scalarMult(scalarBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val k = decodeScalar25519(scalarBytes)
        val x1 = decodeLittleEndian(uBytes).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val daPlusCb = da.add(cb).mod(P)
            val daMinusCb = da.subtract(cb).mod(P)

            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }

        if (swap != 0) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val result = x2.multiply(z2.modInverse(P)).mod(P)
        return encodeLittleEndian(result)
    }

    private fun decodeScalar25519(bytes: ByteArray): BigInteger {
        val rev = ByteArray(32)
        for (i in 0 until 32) {
            rev[i] = bytes[31 - i]
        }
        return BigInteger(1, rev)
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val masked = bytes.copyOf(32)
        masked[31] = (masked[31].toInt() and 0x7F).toByte()
        val rev = ByteArray(32)
        for (i in 0 until 32) {
            rev[i] = masked[31 - i]
        }
        return BigInteger(1, rev)
    }

    private fun encodeLittleEndian(value: BigInteger): ByteArray {
        val normalized = value.mod(P)
        val bigEndian = normalized.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val idxFromEnd = bigEndian.size - 1 - i
            out[i] = if (idxFromEnd >= 0) bigEndian[idxFromEnd] else 0
        }
        return out
    }
}
