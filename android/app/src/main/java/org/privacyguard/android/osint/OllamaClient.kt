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

class OllamaClient(
    private val baseUrl: String = "http://127.0.0.1:11434"
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun queryClassification(
        packageName: String,
        certSha256: String,
        dangerousPermissions: List<String>,
        detectedTrackers: List<String>
    ): OsintVerdict? {
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
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "PrivacyGuard/1.0")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true

            conn.outputStream.use { os ->
                os.write(reqBody.toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val respBody = conn.inputStream.bufferedReader().use { it.readText() }
                val rawResp = json.decodeFromString(OllamaGenerateResponse.serializer(), respBody)
                json.decodeFromString(OsintVerdict.serializer(), rawResp.response)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
