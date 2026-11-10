package com.aniliphy.anilist

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder

class AccountStore(private val folder: File) {
    private val tokenFile get() = File(folder, "token.json")
    private val clientIdFile get() = File(folder, "client_id.json")
    private val guestFile get() = File(folder, "guest.json")
    private val viewerFile get() = File(folder, "profile.json")

    fun token(): String? = tokenFile.field("access_token")?.jsonPrimitive?.contentOrNull

    fun saveToken(token: String) = tokenFile.writeTextAtomically(
        buildJsonObject { put("access_token", token) }.toString(),
    )

    fun deleteToken() {
        tokenFile.delete()
    }

    fun clientId(): String? = clientIdFile.field("client_id")?.jsonPrimitive?.contentOrNull

    fun saveClientId(clientId: String) = clientIdFile.writeTextAtomically(
        buildJsonObject { put("client_id", clientId) }.toString(),
    )

    fun isGuest(): Boolean = (guestFile.field("guest") as? JsonPrimitive)?.booleanOrNull == true

    fun saveGuest(guest: Boolean) = guestFile.writeTextAtomically(
        buildJsonObject { put("guest", guest) }.toString(),
    )

    fun viewer(): Viewer? = try {
        viewerFile.readTextOrNull()?.let { VIEWER_JSON.decodeFromString<Viewer>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun saveViewer(viewer: Viewer) = viewerFile.writeTextAtomically(VIEWER_JSON.encodeToString(viewer))

    fun deleteViewer() {
        viewerFile.delete()
    }

    private fun File.field(name: String) = try {
        readTextOrNull()?.let { Json.parseToJsonElement(it).jsonObject[name] }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        private val VIEWER_JSON = Json { ignoreUnknownKeys = true }

        const val DEVELOPER_URL = "https://anilist.co/settings/developer"

        fun authUrl(clientId: String): String {
            val id = URLEncoder.encode(clientId.trim(), "UTF-8")
            return "https://anilist.co/api/v2/oauth/authorize?client_id=$id&response_type=token"
        }
    }
}
