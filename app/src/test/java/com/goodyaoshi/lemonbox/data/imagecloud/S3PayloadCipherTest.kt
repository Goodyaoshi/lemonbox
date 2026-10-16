package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置密文格式（F20）的加解密。
 *
 * 这里锁住两件事：
 * 1. 往返与失败路径：口令错、密文被改、格式不合法都必须安静地返回 null——
 *    界面只有「口令不对」一种处置，抛异常会把设置页炸掉；
 * 2. **密文格式钉死**：下面那段固定向量由另一份独立实现生成、由这里的 Kotlin/Java 解开；
 *    两端 PBKDF2 与 GCM 的参数只要有一处漂移，用户填再对的口令也解不开包，
 *    而那是发布后才发现的故障——所以用固定向量锁住。
 */
class S3PayloadCipherTest {

    private val passphrase = "柠檬口令-测试-2026".toCharArray()

    private val sample =
        """{"endpoint":"https://s3.example.com","bucket":"lemonbox","accessKey":"ak","secretKey":"sk"}"""

    @Test
    fun encryptThenDecrypt_roundTrips() {
        val payload = S3PayloadCipher.encrypt(sample, passphrase)
        assertTrue(payload.startsWith("v1:"))
        assertEquals(sample, S3PayloadCipher.decrypt(payload, passphrase))
    }

    @Test
    fun encrypt_randomizesSaltAndIvEachTime() {
        // salt / IV 每次随机：同一份配置加密两次的密文必须不同，泄露一次不牵连其他次。
        assertNotEquals(
            S3PayloadCipher.encrypt(sample, passphrase),
            S3PayloadCipher.encrypt(sample, passphrase)
        )
    }

    @Test
    fun decrypt_returnsNullOnWrongPassphrase() {
        val payload = S3PayloadCipher.encrypt(sample, passphrase)
        assertNull(S3PayloadCipher.decrypt(payload, "不是这个口令".toCharArray()))
    }

    @Test
    fun decrypt_returnsNullOnTamperedCiphertext() {
        val payload = S3PayloadCipher.encrypt(sample, passphrase)
        // 改掉密文末位（落在 GCM 认证标签里），认证必须失败而不是解出乱码。
        val tampered = payload.dropLast(1) + if (payload.last() == 'A') 'B' else 'A'
        assertNull(S3PayloadCipher.decrypt(tampered, passphrase))
    }

    @Test
    fun decrypt_returnsNullOnMalformedPayload() {
        assertNull(S3PayloadCipher.decrypt("", passphrase))
        assertNull(S3PayloadCipher.decrypt("v1", passphrase))
        assertNull(S3PayloadCipher.decrypt("v9:AAEC:AAEC:AAEC", passphrase))
        assertNull(S3PayloadCipher.decrypt("v1:!!:!!:!!", passphrase))
    }

    /**
     * 固定向量：一份带固定 salt / IV 的 v1 密文（由早期的一份独立实现生成），
     * 参数 salt = 000102030405060708090a0b0c0d0e0f、iv = 000102030405060708090a0b。
     *
     * 它钉住的是**格式**：迭代次数、口令编码、salt/iv 的位置或「密文||tag」的拼接顺序
     * 只要有一处变动，这条测试就会先炸，而不是等到用户反馈「口令不对」。
     */
    @Test
    fun decryptsFixedVector() {
        val vector = "v1:AAECAwQFBgcICQoLDA0ODw==:AAECAwQFBgcICQoL:" +
            "GIT6RQlMBgaS4SUvnMjVTNKINcYVXPyQHlN3aErxz3rgPGdBfELy0Mnb0SM5ktEaQQwr4F5C4VFAlcseYez8" +
            "O7NRCZuIh4NlsA8jSldFK9GwK+oZ5sH1Y3uUk923prAJcsbVcr8tMjxsX0hMupYg5LeoFjBoDhrdbX91F3eTmQR1" +
            "K2/QUE79JN+BhjBxZFaWSinpW9TgEeEFYNkigeIPlMi9ZY7Wa3Zp0BqSixSkHlhc7d4="
        assertEquals(
            """{"endpoint":"https://s3.example.com","region":"auto","bucket":"lemonbox-test",""" +
                """"prefix":"images/","accessKey":"AKIATESTVECTOR","secretKey":"secret-test-vector-key"}""",
            S3PayloadCipher.decrypt(vector, passphrase)
        )
    }
}
