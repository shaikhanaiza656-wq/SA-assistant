package com.sa.assistant.core.assistant

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [com.sa.assistant.data.repository.ChatRepository.responses] is a shared
 * broadcast: both [com.sa.assistant.ui.chat.ChatViewModel] (when the Chat
 * screen is open) and [AssistantForegroundService] (always running, for
 * voice-triggered commands) observe every response independently. Without
 * a guard, a single voice command answered while the Chat screen happens
 * to be open would get its automation action executed twice and its
 * confirmation spoken twice.
 *
 * Each response id gets claimed at most once per side effect — whichever
 * observer sees it first "wins" and the other silently skips. Two
 * independent sets because a given response can need both effects
 * (speak the text AND run its action) and each must be claimable
 * separately.
 */
@Singleton
class VoiceReplyDedupe @Inject constructor() {
    private val spokenIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val actionedIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /** Returns true only the first time [id] is claimed for speaking — caller should call [SaTextToSpeech.speak] iff this returns true. */
    fun claimSpeak(id: Long): Boolean = spokenIds.add(id)

    /** Returns true only the first time [id] is claimed for automation execution — caller should run the action iff this returns true. */
    fun claimAction(id: Long): Boolean = actionedIds.add(id)
}
