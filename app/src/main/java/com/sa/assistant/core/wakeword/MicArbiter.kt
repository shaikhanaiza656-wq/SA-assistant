package com.sa.assistant.core.wakeword

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guarantees the microphone has exactly one real owner at a time.
 *
 * [WakeWordListener] juggles up to two things that each open the mic:
 * Porcupine's own continuous audio pipeline (wake-word spotting) and a
 * one-shot [android.speech.SpeechRecognizer] session (command capture, or
 * the legacy loop-based spotting fallback). Requirement: "only one
 * microphone instance active at a time". This is a plain, synchronized
 * single-owner lock — not a coroutine mutex — because both callers already
 * do their start/stop work on the main [android.os.Handler] thread and a
 * blocking coroutine mutex would be the wrong tool there.
 */
@Singleton
class MicArbiter @Inject constructor() {

    enum class Owner { NONE, PORCUPINE, SPEECH_RECOGNIZER }

    @Volatile
    private var owner: Owner = Owner.NONE

    /** Returns true and takes ownership only if the mic is free or already held by [requester]. */
    @Synchronized
    fun acquire(requester: Owner): Boolean {
        if (owner == Owner.NONE || owner == requester) {
            owner = requester
            return true
        }
        return false
    }

    /** Releases the mic only if [requester] is the current holder — avoids one owner
     *  accidentally releasing a lock it never held (e.g. a stale callback firing late). */
    @Synchronized
    fun release(requester: Owner) {
        if (owner == requester) owner = Owner.NONE
    }

    @Synchronized
    fun currentOwner(): Owner = owner
}
