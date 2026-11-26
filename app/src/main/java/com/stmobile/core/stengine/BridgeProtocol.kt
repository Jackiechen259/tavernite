package com.stmobile.core.stengine

import com.stmobile.core.model.Character
import com.stmobile.core.model.Message
import com.stmobile.core.model.MessageRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class BridgeEnvelope(
    val type: String,
    val requestId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class MessageDto(
    @SerialName("messageId") val messageId: Int = 0,
    @SerialName("role") val role: String = "assistant",
    @SerialName("rawText") val rawText: String = "",
    @SerialName("displayText") val displayText: String = "",
    @SerialName("swipeId") val swipeId: Int = 0,
    @SerialName("swipeCount") val swipeCount: Int = 1,
    @SerialName("timestamp") val timestamp: Long = 0L,
    @SerialName("isStreaming") val isStreaming: Boolean = false,
    @SerialName("characterName") val characterName: String? = null
)

@Serializable
data class CharacterDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("avatarUrl") val avatarUrl: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("lastMessageDate") val lastMessageDate: Long? = null,
    @SerialName("description") val description: String? = null
)

object BridgeProtocol {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun decodeEnvelope(raw: String): BridgeEnvelope? =
        runCatching { json.decodeFromString(BridgeEnvelope.serializer(), raw) }.getOrNull()

    fun encodeCommand(
        type: String,
        payload: JsonObject = JsonObject(emptyMap()),
        requestId: String? = null
    ): String {
        val envelope = BridgeEnvelope(type = type, requestId = requestId, payload = payload)
        return json.encodeToString(BridgeEnvelope.serializer(), envelope)
    }

    fun commandPayload(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    fun eventFrom(envelope: BridgeEnvelope, chatId: String = ""): EngineEvent {
        val p = envelope.payload
        return runCatching {
            when (envelope.type) {
                "ENGINE_READY" -> EngineEvent.Ready(
                    version = p["version"]?.jsonPrimitive?.contentOrNull,
                    currentUser = p["currentUser"]?.jsonPrimitive?.contentOrNull
                )
                "CHARACTERS_SYNC" -> EngineEvent.CharactersSync(
                    p["characters"]?.jsonArray?.mapNotNull { el ->
                        runCatching {
                            BridgeProtocol.json.decodeFromJsonElement(CharacterDto.serializer(), el)
                        }.getOrNull()?.toDomain()
                    } ?: emptyList()
                )
                "CHAT_LOADED" -> {
                    val characterId = p["characterId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val messages = p["messages"]?.jsonArray?.mapNotNull { el ->
                        runCatching {
                            BridgeProtocol.json.decodeFromJsonElement(MessageDto.serializer(), el)
                        }.getOrNull()?.toDomain(chatId)
                    } ?: emptyList()
                    EngineEvent.ChatLoaded(
                        chatId = p["chatId"]?.jsonPrimitive?.contentOrNull ?: chatId,
                        characterId = characterId,
                        messages = messages
                    )
                }
                "STREAM_TOKEN" -> EngineEvent.StreamToken(
                    messageId = p["messageId"]?.jsonPrimitive?.intOrNull ?: -1,
                    swipeId = p["swipeId"]?.jsonPrimitive?.intOrNull ?: 0,
                    rawText = p["rawText"]?.jsonPrimitive?.contentOrNull ?: "",
                    displayText = p["displayText"]?.jsonPrimitive?.contentOrNull ?: ""
                )
                "MESSAGE_UPDATED" -> {
                    val messageId = p["messageId"]?.jsonPrimitive?.intOrNull ?: -1
                    val dto = p["message"]?.let {
                        runCatching {
                            BridgeProtocol.json.decodeFromJsonElement(MessageDto.serializer(), it)
                        }.getOrNull()
                    }
                    if (dto != null) {
                        EngineEvent.MessageUpdated(messageId, dto.toDomain(chatId))
                    } else {
                        EngineEvent.Unknown(envelope.type, p.toString())
                    }
                }
                "GENERATION_STATE" -> EngineEvent.GenerationState(
                    isGenerating = p["isGenerating"]?.jsonPrimitive?.booleanOrNull ?: false,
                    reason = p["reason"]?.jsonPrimitive?.contentOrNull
                )
                "PRESETS_SYNC" -> EngineEvent.PresetsSync(
                    activePreset = p["activePreset"]?.jsonPrimitive?.contentOrNull,
                    presets = p["presets"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList(),
                    activeWorldInfo = p["activeWorldInfo"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    personas = p["personas"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                )
                "SLASH_RESULT" -> EngineEvent.SlashResult(
                    requestId = envelope.requestId,
                    ok = p["ok"]?.jsonPrimitive?.booleanOrNull ?: true,
                    output = p["output"]?.jsonPrimitive?.contentOrNull
                )
                "ERROR", "ENGINE_ERROR" -> EngineEvent.EngineError(
                    p["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error"
                )
                else -> EngineEvent.Unknown(envelope.type, rawOrEmpty(p))
            }
        }.getOrElse {
            EngineEvent.EngineError("Failed to parse ${envelope.type}: ${it.message}")
        }
    }

    private fun rawOrEmpty(p: JsonObject): String = p.toString()

    private fun CharacterDto.toDomain(): Character = Character(
        id = id,
        name = name,
        avatarUrl = avatarUrl,
        tags = tags,
        lastMessageDate = lastMessageDate,
        description = description
    )

    private fun MessageDto.toDomain(fallbackChatId: String): Message = Message(
        id = messageId,
        chatId = fallbackChatId,
        role = MessageRole.fromWire(role),
        rawText = rawText,
        displayText = displayText.ifEmpty { rawText },
        swipeId = swipeId,
        swipeCount = swipeCount.coerceAtLeast(1),
        timestamp = timestamp,
        isStreaming = isStreaming,
        characterName = characterName
    )
}
