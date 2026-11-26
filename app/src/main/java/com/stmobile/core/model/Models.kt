package com.stmobile.core.model

import kotlinx.serialization.Serializable

/**
 * A SillyTavern server the user can connect to. [endpoints] is an ordered
 * priority list (LAN direct > mesh VPN > public reverse proxy).
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val endpoints: List<String> = emptyList(),
    val customHeaders: Map<String, String> = emptyMap(),
    val basicAuthUser: String? = null,
    val basicAuthPassword: String? = null,
    val caPem: String? = null,
    val allowInsecureTls: Boolean = false,
    val cacheEnabled: Boolean = true
) {
    val primaryEndpoint: String? get() = endpoints.firstOrNull()
}

enum class MessageRole { USER, ASSISTANT, SYSTEM;
    companion object {
        fun fromWire(value: String?): MessageRole = when (value?.lowercase()) {
            "user" -> USER
            "system" -> SYSTEM
            else -> ASSISTANT
        }
    }
    fun toWire(): String = name.lowercase()
}

data class Character(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val tags: List<String> = emptyList(),
    val lastMessageDate: Long? = null,
    val description: String? = null
)

data class ChatSession(
    val id: String,
    val characterId: String,
    val characterName: String = "",
    val characterAvatarUrl: String? = null,
    val title: String = "",
    val lastMessage: String = "",
    val lastMessageDate: Long = 0L,
    val pinned: Boolean = false,
    val archived: Boolean = false
)

data class Message(
    val id: Int,
    val chatId: String,
    val role: MessageRole,
    val rawText: String,
    val displayText: String,
    val swipeId: Int = 0,
    val swipeCount: Int = 1,
    val timestamp: Long = 0L,
    val isStreaming: Boolean = false,
    val characterName: String? = null
)

sealed interface MessageBlock {
    data class Markdown(val content: String) : MessageBlock
    data class Think(val content: String, val isStreaming: Boolean = false) : MessageBlock
    data class HtmlWidget(val html: String, val blockHash: String) : MessageBlock
}

data class EndpointHealth(
    val url: String,
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null
)

enum class ReadingMode { BUBBLE, NOVEL }

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class EngineStatus(
    val phase: EnginePhase = EnginePhase.IDLE,
    val detail: String? = null,
    val serverVersion: String? = null,
    val currentUser: String? = null
)

enum class EnginePhase { IDLE, STARTING_PROXY, LOADING_ENGINE, READY, ERROR }
