package extensions.en.aniwave

import okio.ByteString.Companion.decodeBase64
import java.util.Base64
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniwaveUtils {

    fun vrfEncrypt(input: String): String {

//        val rc4Key = SecretKeySpec("tGn6kIpVXBEUmqjD".toByteArray(), "RC4")
        val rc4Key = SecretKeySpec("p01EDKu734HJP1Tm".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)

        var vrf = cipher.doFinal(input.toByteArray())
        vrf = Base64.getEncoder().encode(vrf)
//        vrf = Base64.getEncoder().encode(vrf)
//        vrf = vrfShift(vrf)
//        vrf = vrf.reversed().toByteArray()
//        vrf = Base64.getEncoder().encode(vrf)
//         vrf = rot13(vrf)
        val stringVrf = vrf.toString(Charsets.UTF_8)
        return "vrf=${URLEncoder.encode(stringVrf, "utf-8")}"
    }

    fun vrfDecrypt(input: String): String {
//        var vrf = input.toByteArray()
        var vrf = input.decodeBase64()?.toByteArray()

//        val rc4Key = SecretKeySpec("LUyDrL4qIxtIxOGs".toByteArray(), "RC4")
        val rc4Key = SecretKeySpec("ctpAbOz5u7S6OMkx".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        vrf = cipher.doFinal(vrf)

        return URLDecoder.decode(String(vrf), "utf-8")
    }

    private fun rot13(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val byte = vrf[i]
            if (byte in 'A'.code..'Z'.code) {
                vrf[i] = ((byte - 'A'.code + 13) % 26 + 'A'.code).toByte()
            } else if (byte in 'a'.code..'z'.code) {
                vrf[i] = ((byte - 'a'.code + 13) % 26 + 'a'.code).toByte()
            }
        }
        return vrf
    }

    private fun vrfShift(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val shift = arrayOf(-2, -4, -5, 6, 2, -3, 3, 6)[i % 8]
            vrf[i] = vrf[i].plus(shift).toByte()
        }
        return vrf
    }

    fun rc4(key: String, str: String): String {
        val s = IntArray(256)
        var j = 0
        var x: Int
        var res = StringBuilder()

        // Initialize the array
        for (i in 0 until 256) {
            s[i] = i
        }

        // Key-scheduling algorithm (KSA)
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.length].code) % 256
            x = s[i]
            s[i] = s[j]
            s[j] = x
        }

        var i = 0
        j = 0

        // Pseudo-random generation algorithm (PRGA)
        for (y in str.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            x = s[i]
            s[i] = s[j]
            s[j] = x
            res.append((str[y].code xor s[(s[i] + s[j]) % 256]).toChar())
        }

        return res.toString()
    }

    fun safeBtoa(s: String): String {
        val encoded = Base64.getEncoder().encodeToString(s.toByteArray())
        return encoded.replace('/', '_').replace('+', '-')
    }

    fun safeAtob(s: String): String {
        val modified = s.replace('_', '/').replace('-', '+')
        val decodedBytes = Base64.getDecoder().decode(modified)
        return String(decodedBytes)
    }
}
