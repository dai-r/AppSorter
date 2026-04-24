package dev.dair.appsorter.categorizer

import okhttp3.HttpUrl
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class SigV4Signer(
    private val region: String,
    private val service: String,
) {
    data class Credentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String?,
    )

    /**
     * POSTリクエストをSigV4で署名し、OkHttpに付けるヘッダ一覧を返す。
     * - Content-Type は固定で application/json
     * - 署名ヘッダ: host, x-amz-date, x-amz-content-sha256, (x-amz-security-token), content-type
     */
    fun signPost(
        url: HttpUrl,
        bodyBytes: ByteArray,
        creds: Credentials,
    ): Map<String, String> {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))

        val payloadHash = bodyBytes.sha256Hex()

        // 署名対象ヘッダ（小文字キー、ソート済み）
        val signed = sortedMapOf<String, String>()
        signed["content-type"] = "application/json"
        signed["host"] = url.host
        signed["x-amz-content-sha256"] = payloadHash
        signed["x-amz-date"] = amzDate
        creds.sessionToken?.let { signed["x-amz-security-token"] = it }

        val canonicalHeaders = buildString {
            signed.forEach { (k, v) ->
                append(k)
                append(':')
                append(v.trim())
                append('\n')
            }
        }
        val signedHeaderNames = signed.keys.joinToString(";")

        val canonicalUri = url.encodedPath.ifEmpty { "/" }
        val canonicalQuery = canonicalizeQuery(url)

        val canonicalRequest = buildString {
            append("POST\n")
            append(canonicalUri); append('\n')
            append(canonicalQuery); append('\n')
            append(canonicalHeaders); append('\n')
            append(signedHeaderNames); append('\n')
            append(payloadHash)
        }

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append(amzDate); append('\n')
            append(credentialScope); append('\n')
            append(canonicalRequest.toByteArray(Charsets.UTF_8).sha256Hex())
        }

        val kDate = hmacSha256("AWS4${creds.secretAccessKey}".toByteArray(Charsets.UTF_8), dateStamp.toByteArray(Charsets.UTF_8))
        val kRegion = hmacSha256(kDate, region.toByteArray(Charsets.UTF_8))
        val kService = hmacSha256(kRegion, service.toByteArray(Charsets.UTF_8))
        val kSigning = hmacSha256(kService, "aws4_request".toByteArray(Charsets.UTF_8))
        val signature = hmacSha256(kSigning, stringToSign.toByteArray(Charsets.UTF_8)).toHex()

        val authorization = "AWS4-HMAC-SHA256 " +
            "Credential=${creds.accessKeyId}/$credentialScope, " +
            "SignedHeaders=$signedHeaderNames, " +
            "Signature=$signature"

        // OkHttp に渡すヘッダ（Host は OkHttp が自動で付けるので除外）
        val out = mutableMapOf<String, String>()
        out["Content-Type"] = "application/json"
        out["X-Amz-Date"] = amzDate
        out["X-Amz-Content-Sha256"] = payloadHash
        creds.sessionToken?.let { out["X-Amz-Security-Token"] = it }
        out["Authorization"] = authorization
        return out
    }

    private fun canonicalizeQuery(url: HttpUrl): String {
        if (url.querySize == 0) return ""
        // 名前→値の辞書式ソート、名前同士が同じときは値でソート
        val pairs = (0 until url.querySize).map { i ->
            url.queryParameterName(i) to (url.queryParameterValue(i) ?: "")
        }.sortedWith(compareBy({ it.first }, { it.second }))
        return pairs.joinToString("&") { (n, v) ->
            "${awsUrlEncode(n)}=${awsUrlEncode(v)}"
        }
    }

    // RFC 3986 unreserved以外をパーセントエンコード
    private fun awsUrlEncode(input: String): String {
        val sb = StringBuilder(input.length)
        for (b in input.toByteArray(Charsets.UTF_8)) {
            val ub = b.toInt() and 0xff
            val isUnreserved = (ub in '0'.code..'9'.code) ||
                (ub in 'A'.code..'Z'.code) ||
                (ub in 'a'.code..'z'.code) ||
                ub == '-'.code || ub == '_'.code || ub == '.'.code || ub == '~'.code
            if (isUnreserved) sb.append(ub.toChar()) else sb.append('%').append("%02X".format(ub))
        }
        return sb.toString()
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
