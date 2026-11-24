package com.wxgy.checkin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 加密逻辑必须和学校登录页的 cas_security.js 逐字节一致，
 * 否则服务端解出来是乱码，只会回「认证信息无效」。
 *
 * 期望值是用仓库里的 cas_security.js 对同一把公钥算出来的原始结果
 * （见 tools/rsa-vector.js）。
 */
class CasCryptoTest {

    // 学校 CAS 当前使用的公钥（512 位，取自 /cas/v2/getPubKey）
    private val modulus =
        "de23ddc7387872e921bb242de5a92e9bcaf8bd0a1b4761505c32b91cbd6b1b30" +
        "e555446e0749016657b421f684715331cb7978f6db60abba35e7a661834f819d"
    private val exponent = "10001"

    @Test
    fun matchesSchoolFrontend() {
        assertEquals(
            "870efa7f56067b00bc4b3f785d621a27cc8c73f318a28d4cf96cba9c35bb5645" +
            "27b762c1b204a0df022e8f31499b5b5a7a563eb98aa69db49d7e5ecfaca20313",
            CasCrypto.rsaEncrypt(modulus, exponent, "Test1234"),
        )
        assertEquals(
            "227e13662d6d51e15a56d704f348f68ea7f286a88e267779d3d78cecd88bc0dc" +
            "b23ab6b79efce939eeb0e5d3a95729d227757e4237ae5c24c23b252c3caa7aa1",
            CasCrypto.rsaEncrypt(modulus, exponent, "abc"),
        )
        assertEquals(
            "5b6cf489f38fc264659194dec6e553c289fefa26b5e423accfd228289d45925e" +
            "cbb0e2d380c8a8e4b044d33e80e16d9af7572cb90f1cca87c2d580dfb0905cc1",
            CasCrypto.rsaEncrypt(modulus, exponent, "Wxgy2026"),
        )
        assertEquals(
            "17fbc5ce7dd256b34f8c6085bbcaeaadcd20cdc468b407557b849a856d0eb92b" +
            "692dcccdcd52f153778f4c9c44f4266c90c67e7c3b7db8cafb859d3c6d066627",
            CasCrypto.rsaEncrypt(modulus, exponent, "z9!Qw2#Lm"),
        )
    }

    @Test
    fun outputIsLowercaseHexOfModulusLength() {
        val out = CasCrypto.rsaEncrypt(modulus, exponent, "Test1234")
        assertEquals(128, out.length)
        assertEquals(out.lowercase(), out)
    }
}
