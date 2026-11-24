package com.wxgy.checkin

import java.math.BigInteger

/**
 * CAS 登录密码加密。
 *
 * 学校登录页用的是 David Shapiro RSA 库的一个裁剪版（见 cas_security.js），
 * 它和常见的 JSEncrypt / Java Cipher 都不一样，三处都必须照抄：
 *
 * 1. **没有 PKCS#1 v1.5 填充**，直接裸 RSA：c = m^e mod n；
 * 2. 密码先整体倒序，再按「两个字符打包进一个 16 位小端字」转成整数，
 *    不是 UTF-8，也不是 UTF-16LE（后者会插 0x00）；
 * 3. 结果输出**小写十六进制**，不是 Base64。
 *
 * 任何一处不一致，服务端解出来都是乱码，只会回「认证信息无效」。
 */
object CasCrypto {

    /** 返回小写 hex，长度对齐到模长的十六进制位数 */
    fun rsaEncrypt(modulusHex: String, exponentHex: String, password: String): String {
        val n = BigInteger(modulusHex, 16)
        val e = BigInteger(exponentHex, 16)

        // JS: chunkSize = 2 * biHighIndex(modulus)，即比模长少一个 16 位字（2 字节）
        val digits = (modulusHex.length + 3) / 4
        val chunkSize = 2 * (digits - 1)
        require(chunkSize >= 2) { "公钥格式异常" }

        val bytes = packLikeRsaUtils(password, chunkSize)
        // packLikeRsaUtils 产出的是小端字节序，BigInteger 按大端解释，转一下
        val m = BigInteger(1, bytes.reversedArray())
        require(m < n) { "密码过长，超出公钥可加密范围" }

        val hexLen = ((n.bitLength() + 15) / 16) * 4
        return m.modPow(e, n).toString(16).padStart(hexLen, '0')
    }

    /**
     * 复刻 RSAUtils.encryptedString 的装填方式：
     * 字符码按每两个一组塞进一个 16 位字（低字节在前），整体按小端排布。
     */
    private fun packLikeRsaUtils(password: String, chunkSize: Int): ByteArray {
        val rev = password.reversed()
        require(rev.length <= chunkSize) { "密码过长，超出公钥可加密范围" }

        val codes = IntArray(chunkSize)
        for (i in rev.indices) codes[i] = rev[i].code

        val out = ByteArray(chunkSize)
        var j = 0
        while (j < chunkSize) {
            val word = codes[j] + (codes[j + 1] shl 8)
            out[j] = (word and 0xFF).toByte()
            out[j + 1] = ((word shr 8) and 0xFF).toByte()
            j += 2
        }
        return out
    }
}
