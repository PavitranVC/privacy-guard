package org.privacyguard.android.osint

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class OllamaGenerateRequest(
    val model: String = "llama3.2",
    val prompt: String,
    val stream: Boolean = false,
    val format: String = "json"
)

@Serializable
data class OllamaGenerateResponse(
    val response: String
)

@Serializable
data class OsintVerdict(
    val verdict: String,
    val confidence: Double,
    val reasoning: String
)

sealed class ClassificationResult {
    data class Success(val verdict: OsintVerdict) : ClassificationResult()
    object Unreachable : ClassificationResult()   // SocketTimeout, ConnectException, 404, 408, 429, 5xx, or offline
    object Invalid : ClassificationResult()       // 200-with-bad-body or 400
}

class OllamaClient(
    private val baseUrl: String = "http://127.0.0.1:11434"
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun classify(
        packageName: String,
        certSha256: String,
        dangerousPermissions: List<String>,
        detectedTrackers: List<String>
    ): ClassificationResult {
        var conn: HttpURLConnection? = null
        return try {
            val prompt = """
                You are an Android privacy & security analysis engine. Evaluate the following app metadata:
                - Package: $packageName
                - Cert Fingerprint SHA-256: $certSha256
                - Permissions: ${dangerousPermissions.joinToString(", ")}
                - Trackers: ${detectedTrackers.joinToString(", ")}

                Determine if this app displays malicious, suspicious, or clean privacy/security behavior.
                Respond strictly with a JSON object:
                {"verdict": "MALICIOUS" | "SUSPICIOUS" | "CLEAN", "confidence": 0.0 to 1.0, "reasoning": "brief summary"}
            """.trimIndent()

            val req = OllamaGenerateRequest(prompt = prompt)
            val reqBody = json.encodeToString(OllamaGenerateRequest.serializer(), req)

            val url = URL("$baseUrl/api/generate")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "PrivacyGuard/1.0")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true

            conn.outputStream.use { os ->
                os.write(reqBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val respBody = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Throwable) {
                    return ClassificationResult.Invalid
                }
                try {
                    val rawResp = json.decodeFromString(OllamaGenerateResponse.serializer(), respBody)
                    val verdict = json.decodeFromString(OsintVerdict.serializer(), rawResp.response)
                    val v = verdict.verdict.uppercase()
                    if (v in setOf("MALICIOUS", "SUSPICIOUS", "CLEAN") && verdict.confidence in 0.0..1.0) {
                        ClassificationResult.Success(verdict)
                    } else {
                        ClassificationResult.Invalid
                    }
                } catch (_: Throwable) {
                    ClassificationResult.Invalid
                }
            } else if (responseCode == 400) {
                ClassificationResult.Invalid
            } else if (responseCode in setOf(404, 408, 429) || responseCode >= 500) {
                ClassificationResult.Unreachable
            } else {
                ClassificationResult.Unreachable
            }
        } catch (_: java.net.SocketTimeoutException) {
            ClassificationResult.Unreachable
        } catch (_: java.net.ConnectException) {
            ClassificationResult.Unreachable
        } catch (_: java.net.UnknownHostException) {
            ClassificationResult.Unreachable
        } catch (_: java.io.IOException) {
            ClassificationResult.Unreachable
        } catch (_: Throwable) {
            ClassificationResult.Unreachable
        } finally {
            conn?.disconnect()
        }
    }
}
