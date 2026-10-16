package com.tremendoushand.repeatodo.sync

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google sign-in for Drive, limited to the app's own hidden folder (appDataFolder). */
object DriveAuth {
    const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

    val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE_APPDATA))).build()

    /** Starts authorization; the result may need user interaction ([AuthorizationResult.hasResolution]). */
    suspend fun authorize(context: Context): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request).await()

    /** A token without showing any UI, or null if the user has to connect again. */
    suspend fun tokenSilently(context: Context): String? {
        val result = authorize(context)
        return if (result.hasResolution()) null else result.accessToken
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

class DriveAuthException(message: String) : Exception(message)

/** The few Drive REST calls the app needs, against a single JSON file in appDataFolder. */
class DriveClient(private val token: String, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun findFileId(): String? {
        val url = "$API/files?spaces=appDataFolder&fields=files(id,name)&q=" +
            java.net.URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val body = execute(Request.Builder().url(url).get())
        val files = json.parseToJsonElement(body).jsonObject["files"]?.jsonArray ?: return null
        return files.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    fun download(fileId: String): String = execute(Request.Builder().url("$API/files/$fileId?alt=media").get())

    fun create(content: String): String {
        val metadata = JsonObject(
            mapOf(
                "name" to kotlinx.serialization.json.JsonPrimitive(FILE_NAME),
                "parents" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("appDataFolder"))),
            ),
        ).toString()
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(JSON))
            .addPart(content.toRequestBody(JSON))
            .build()
        val body = execute(Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id").post(multipart))
        return json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content
    }

    fun update(fileId: String, content: String) {
        execute(Request.Builder().url("$UPLOAD/files/$fileId?uploadType=media").patch(content.toRequestBody(JSON)))
    }

    private fun execute(builder: Request.Builder): String {
        val request = builder.header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                throw DriveAuthException("구글 드라이브 권한이 없어요. 설정에서 다시 연결해 주세요. (${response.code})")
            }
            if (!response.isSuccessful) throw IOException("Drive ${response.code}: ${text.take(200)}")
            return text
        }
    }

    companion object {
        const val FILE_NAME = "repeatodo-sync.json"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
