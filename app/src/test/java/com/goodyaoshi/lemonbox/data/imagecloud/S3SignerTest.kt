package com.goodyaoshi.lemonbox.data.imagecloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SigV4 签名的正确性验证（F20 图片云端存储）。
 *
 * 这里用的是 AWS 官方文档里给出的**公开测试向量**（密钥是文档中的示例密钥，
 * 非真实凭据），因此签名字符串可以逐字节比对——自实现的签名算法若有一处
 * 大小写、排序或编码偏差，测试必然失败。这是本模块最需要锚定的一环：
 * 签名错了，请求会被对象存储以 403 拒绝，而症状只是「上传失败」，很难定位。
 *
 * 参考：Amazon S3「Signature Calculations for the Authorization Header:
 * Transferring Payload in a Single Chunk」的 GET Object 示例。
 */
class S3SignerTest {

    private val accessKey = "AKIAIOSFODNN7EXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val host = "examplebucket.s3.amazonaws.com"
    private val amzDate = "20130524T000000Z"

    @Test
    fun authorization_matchesAwsOfficialGetObjectVector() {
        val authorization = S3Signer.authorization(
            method = "GET",
            host = host,
            canonicalPath = "/test.txt",
            query = emptyMap(),
            payloadHash = S3Signer.EMPTY_PAYLOAD_SHA256,
            region = "us-east-1",
            accessKey = accessKey,
            secretKey = secretKey,
            amzDate = amzDate,
            extraSignedHeaders = mapOf(
                "range" to "bytes=0-9",
                "x-amz-content-sha256" to S3Signer.EMPTY_PAYLOAD_SHA256,
                "x-amz-date" to amzDate
            )
        )

        assertEquals(
            "AWS4-HMAC-SHA256 " +
                "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date," +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            authorization
        )
    }

    /** 签头顺序必须按键名排序，与传入顺序无关——这是最容易写错的一处。 */
    @Test
    fun authorization_isIndependentOfSignedHeaderInsertionOrder() {
        fun sign(map: Map<String, String>) = S3Signer.authorization(
            method = "GET",
            host = host,
            canonicalPath = "/test.txt",
            query = emptyMap(),
            payloadHash = S3Signer.EMPTY_PAYLOAD_SHA256,
            region = "us-east-1",
            accessKey = accessKey,
            secretKey = secretKey,
            amzDate = amzDate,
            extraSignedHeaders = map
        )

        val forward = sign(
            linkedMapOf(
                "range" to "bytes=0-9",
                "x-amz-content-sha256" to S3Signer.EMPTY_PAYLOAD_SHA256,
                "x-amz-date" to amzDate
            )
        )
        val reversed = sign(
            linkedMapOf(
                "x-amz-date" to amzDate,
                "x-amz-content-sha256" to S3Signer.EMPTY_PAYLOAD_SHA256,
                "range" to "bytes=0-9"
            )
        )

        assertEquals(forward, reversed)
    }

    @Test
    fun sha256Hex_emptyPayloadMatchesKnownConstant() {
        assertEquals(S3Signer.EMPTY_PAYLOAD_SHA256, S3Signer.sha256Hex(ByteArray(0)))
    }

    // ---------- URI 编码：对象键里可能出现空格、中文、保留字符 ----------

    @Test
    fun uriEncode_keepsUnreservedCharactersVerbatim() {
        assertEquals(
            "abcXYZ0189-_.~",
            S3Signer.uriEncode("abcXYZ0189-_.~", encodeSlash = true)
        )
    }

    @Test
    fun uriEncode_percentEncodesReservedAndNonAscii() {
        // 空格与 '+' 是 S3 上最常见的坑：必须编码成 %20 / %2B，不能原样或编成 '+'。
        assertEquals("a%20b", S3Signer.uriEncode("a b", encodeSlash = true))
        assertEquals("a%2Bb", S3Signer.uriEncode("a+b", encodeSlash = true))
        assertEquals("%E4%B8%AD%E6%96%87", S3Signer.uriEncode("中文", encodeSlash = true))
    }

    @Test
    fun uriEncode_slashIsKeptInPathButEncodedInQuery() {
        assertEquals("images/a.jpg", S3Signer.uriEncode("images/a.jpg", encodeSlash = false))
        assertEquals("images%2Fa.jpg", S3Signer.uriEncode("images/a.jpg", encodeSlash = true))
    }

    /** 路径里的斜杠必须保留：编码成 %2F 会被对象存储当成「键名里带斜杠字符」而找不到对象。 */
    @Test
    fun canonicalPathFor_keepsSlashesAndEncodesSpaces() {
        assertEquals(
            "/mybucket/lemonbox/images/2026/a b.jpg",
            S3Signer.canonicalPathFor(
                key = "lemonbox/images/2026/a b.jpg",
                bucket = "mybucket",
                includeBucket = true
            ).replace("%20", " ")
        )
        assertEquals(
            "/mybucket/lemonbox/images/2026/a%20b.jpg",
            S3Signer.canonicalPathFor(
                key = "lemonbox/images/2026/a b.jpg",
                bucket = "mybucket",
                includeBucket = true
            )
        )
    }

    @Test
    fun canonicalQuery_sortsKeysAndEncodesValues() {
        // 通过签名间接验证：键序不同但内容相同，签名必须一致。
        fun sign(query: Map<String, String>) = S3Signer.authorization(
            method = "GET",
            host = host,
            canonicalPath = "/",
            query = query,
            payloadHash = S3Signer.EMPTY_PAYLOAD_SHA256,
            region = "us-east-1",
            accessKey = accessKey,
            secretKey = secretKey,
            amzDate = amzDate
        )

        assertEquals(
            sign(linkedMapOf("b" to "2", "a" to "1")),
            sign(linkedMapOf("a" to "1", "b" to "2"))
        )
    }

    @Test
    fun authorization_includesCredentialScopeAndSignedHeaders() {
        val authorization = S3Signer.authorization(
            method = "PUT",
            host = host,
            canonicalPath = "/mybucket/images/abc.jpg",
            query = emptyMap(),
            payloadHash = S3Signer.EMPTY_PAYLOAD_SHA256,
            region = "ap-east-1",
            accessKey = accessKey,
            secretKey = secretKey,
            amzDate = "20260101T101010Z",
            extraSignedHeaders = mapOf(
                "x-amz-content-sha256" to S3Signer.EMPTY_PAYLOAD_SHA256,
                "x-amz-date" to "20260101T101010Z"
            )
        )

        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=$accessKey/20260101/ap-east-1/s3/aws4_request,"))
        assertTrue(authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date,"))
        assertTrue(authorization.contains("Signature="))
    }

    // ---------- 预签名 URL：私有桶里 Coil 直读图片的依据 ----------

    private fun presign(
        amzDate: String = "20260101T101010Z",
        expiresSeconds: Long = 3600L,
        region: String = "auto",
        canonicalPath: String = "/demo-bucket/images/abc123.jpg"
    ): String = S3Signer.presignedUrl(
        method = "GET",
        host = "demo.r2.cloudflarestorage.com",
        baseUrl = "https://demo.r2.cloudflarestorage.com",
        canonicalPath = canonicalPath,
        region = region,
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        amzDate = amzDate,
        expiresSeconds = expiresSeconds
    )

    @Test
    fun presignedUrl_carriesAllRequiredQueryParameters() {
        val url = presign()

        assertTrue(url.startsWith("https://demo.r2.cloudflarestorage.com/demo-bucket/images/abc123.jpg?"))
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
        assertTrue(url.contains("X-Amz-Date=20260101T101010Z"))
        assertTrue(url.contains("X-Amz-Expires=3600"))
        assertTrue(url.contains("X-Amz-SignedHeaders=host"))
        assertTrue(url.contains("X-Amz-Signature="))
    }

    /** 凭据里的斜杠必须编码：原样发出去会被对象存储当成多余的路径分隔而验签失败。 */
    @Test
    fun presignedUrl_percentEncodesSlashesInsideCredentialValue() {
        val url = presign()

        assertTrue(
            url.contains(
                "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260101%2Fauto%2Fs3%2Faws4_request"
            )
        )
    }

    /** 查询参数按键名排序，保证「签的那份」与「发出去的那份」逐字一致。 */
    @Test
    fun presignedUrl_ordersQueryParametersByKey() {
        val query = presign().substringAfter('?').substringBefore("&X-Amz-Signature=")

        val keys = query.split('&').map { it.substringBefore('=') }
        assertEquals(keys.sorted(), keys)
        assertEquals(
            listOf(
                "X-Amz-Algorithm",
                "X-Amz-Credential",
                "X-Amz-Date",
                "X-Amz-Expires",
                "X-Amz-SignedHeaders"
            ),
            keys
        )
    }

    @Test
    fun presignedUrl_isDeterministicForSameInputs() {
        assertEquals(presign(), presign())
    }

    @Test
    fun presignedUrl_changesWithExpiryAndRegion() {
        // 有效期与区域都参与签名，改动任一项都必须产出不同签名。
        assertNotEquals(presign(expiresSeconds = 3600L), presign(expiresSeconds = 7200L))
        assertNotEquals(presign(region = "auto"), presign(region = "us-east-1"))
    }

    /** 超过 7 天要钳制：AWS 对预签名有效期有硬上限，超了会直接拒签。 */
    @Test
    fun presignedUrl_clampsExpiryToAwsMaximum() {
        val url = presign(expiresSeconds = S3Signer.MAX_PRESIGN_EXPIRES_SECONDS * 10)
        assertTrue(url.contains("X-Amz-Expires=${S3Signer.MAX_PRESIGN_EXPIRES_SECONDS}"))

        // 下限：非正数一律按 1 秒处理，避免签出「立即失效」的链接。
        assertTrue(presign(expiresSeconds = 0L).contains("X-Amz-Expires=1"))
    }

    /** 预签名与请求头式签名是同族但不同的两种签名，签名字面量不应相同。 */
    @Test
    fun presignedUrl_differsFromHeaderSignature() {
        val signedUrl = presign()
        val headerSignature = S3Signer.authorization(
            method = "GET",
            host = "demo.r2.cloudflarestorage.com",
            canonicalPath = "/demo-bucket/images/abc123.jpg",
            query = emptyMap(),
            payloadHash = S3Signer.EMPTY_PAYLOAD_SHA256,
            region = "auto",
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            amzDate = "20260101T101010Z"
        )

        val headerPart = headerSignature.substringAfter("Signature=")
        assertFalse(signedUrl.endsWith(headerPart))
    }
}
