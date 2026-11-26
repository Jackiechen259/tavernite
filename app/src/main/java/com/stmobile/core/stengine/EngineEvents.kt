package com.stmobile.core.stengine

import com.stmobile.core.model.Character
import com.stmobile.core.model.Message

sealed interface EngineEvent {
    data class Ready(val version: String?, val currentUser: String?) : EngineEvent
    data class CharactersSync(val characters: List<Character>) : EngineEvent
    data class ChatLoaded(
        val chatId: String,
        val characterId: String,
        val messages: List<Message>
    ) : EngineEvent
    data class StreamToken(
        val messageId: Int,
        val swipeId: Int,
        val rawText: String,
        val displayText: String
    ) : EngineEvent
    data class MessageUpdated(val messageId: Int, val message: Message) : EngineEvent
    data class GenerationState(val isGenerating: Boolean, val reason: String? = null) : EngineEvent
    data class PresetsSync(
        val activePreset: String?,
        val presets: List<String>,
        val activeWorldInfo: List<String>,
        val personas: List<String>
    ) : EngineEvent
    data class SlashResult(val requestId: String?, val ok: Boolean, val output: String?) : EngineEvent
    data class EngineError(val message: String) : EngineEvent
    data class Unknown(val type: String, val raw: String) : EngineEvent
}

/** Commands the native layer can push into the ST engine. */
object EngineCommand {
    const val SELECT_CHARACTER = "SELECT_CHARACTER"
    const val SEND_MESSAGE = "SEND_MESSAGE"
    const val STOP_GENERATION = "STOP_GENERATION"
    const val SWIPE_MESSAGE = "SWIPE_MESSAGE"
    const val EDIT_MESSAGE = "EDIT_MESSAGE"
    const val DELETE_MESSAGE = "DELETE_MESSAGE"
    const val CONTINUE_GENERATION = "CONTINUE_GENERATION"
    const val REGENERATE = "REGENERATE"
    const val EXECUTE_SLASH_CMD = "EXECUTE_SLASH_CMD"
    const val UPDATE_AUTHOR_NOTE = "UPDATE_AUTHOR_NOTE"
    const val SET_PRESET = "SET_PRESET"
    const val TOGGLE_WORLD_INFO = "TOGGLE_WORLD_INFO"
    const val REQUEST_CHARACTERS = "REQUEST_CHARACTERS"
    const val REQUEST_CURRENT_CHAT = "REQUEST_CURRENT_CHAT"
}
