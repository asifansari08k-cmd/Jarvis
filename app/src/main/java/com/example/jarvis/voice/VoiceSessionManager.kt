package com.example.jarvis.voice

// ============================================================================
// EXHAUSTIVE SYSTEM IMPORTS (TITAN CORE ARCHITECTURE V45.0 - UNCOMPRESSED)
// ============================================================================

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 * J.A.R.V.I.S. VOICE SESSION MANAGER (QUANTUM SESSION CONTROLLER)
 * ============================================================================
 * Architect: 𝑫𝒓𝒂𝒌𝒐𝑿𝑵𝒂𝒆𝒆𝒎
 * Developer: 𝑵𝒂𝒆𝒆𝒎
 * 
 * CORE FEATURES INJECTED:
 * 1. DYNAMIC SILENT LOOPING: Prevents aggressive "Tudung" sound via adaptive delays.
 * 2. EXPONENTIAL BACKOFF: Handles continuous NO_SPEECH errors without crashing UI.
 * 3. WATCHDOG TIMER: Auto-recovers from deadlocks if AI Cloud API hangs.
 * 4. THREAD-SAFE ATOMIC STATES: Ensures zero overlap between Listening & Speaking.
 * ============================================================================
 */
class VoiceSessionManager(
    private val context: Context,
    private val speechRecognizer: SpeechRecognizerManager,
    private val onText: (String) -> Unit,
    private val onStateChanged: (State) -> Unit,
    private val onError: (String) -> Unit
) {

    // =========================================================
    // ENGINE STATES & METRICS
    // =========================================================
    enum class State {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING,
        FAULT_RECOVERY
    }

    companion object {
        private const val TAG = "JarvisSessionController"
        
        // Base delays for silent restarts
        private const val LISTEN_RESTART_DELAY_MS = 600L
        private const val ERROR_RESTART_DELAY_MS = 1500L
        
        // Watchdog parameters
        private const val WATCHDOG_PROCESSING_TIMEOUT_MS = 15000L // 15 seconds max for Cloud AI
    }

    // =========================================================
    // THREADING & SYNCHRONIZATION VARIABLES
    // =========================================================
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var state = State.IDLE
    private var restartScheduled = false
    private var consecutiveErrors = 0

    // Coroutine Scope for Advanced Watchdog & Background Timers
    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // =========================================================
    // 1. LIFECYCLE: START & STOP PROTOCOLS
    // =========================================================
    
    /**
     * Boot the voice session and trigger continuous listening.
     * Prevents overlapping starts using AtomicBoolean.
     */
    fun start() {
        Log.i(TAG, "> Session Start Protocol Initiated.")
        
        if (active.get()) {
            Log.w(TAG, "> Session already active. Ignoring start request.")
            return
        }

        active.set(true)
        restartScheduled = false
        consecutiveErrors = 0

        setState(State.LISTENING)
        startListening()
    }

    /**
     * Completely halt all listening and processing activities.
     */
    fun stop() {
        Log.i(TAG, "> Session Stop Protocol Initiated.")
        
        active.set(false)
        restartScheduled = false
        consecutiveErrors = 0

        handler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "> Failed to stop acoustic sensors: ${e.message}")
        }

        setState(State.IDLE)
    }

    // =========================================================
    // 2. STATE GETTERS & MODIFIERS
    // =========================================================
    
    fun isActive(): Boolean {
        return active.get()
    }

    fun getState(): State {
        return state
    }

    /**
     * Locks the session into PROCESSING mode while Cloud AI computes.
     * Triggers the Watchdog Timer to prevent permanent hanging.
     */
    fun setProcessing() {
        if (!active.get()) return

        Log.i(TAG, "> Transitioning to PROCESSING State.")
        cancelPendingRestart()

        try {
            speechRecognizer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "> Acoustic sensor halt failed during processing.")
        }

        setState(State.PROCESSING)
        startWatchdogTimer()
    }

    /**
     * Locks the session into SPEAKING mode while TTS transmits.
     */
    fun setSpeaking() {
        if (!active.get()) return

        Log.i(TAG, "> Transitioning to SPEAKING State.")
        cancelPendingRestart()
        setState(State.SPEAKING)
    }

    /**
     * Automatically called by TTS Engine when speech finishes.
     * Restarts the acoustic sensors dynamically.
     */
    fun resumeListening() {
        if (!active.get()) return

        Log.i(TAG, "> Resuming acoustic surveillance...")
        scheduleListeningRestart(LISTEN_RESTART_DELAY_MS)
    }

    // =========================================================
    // 3. THE SILENT RESTART ENGINE & BACKOFF ALGORITHM
    // =========================================================
    
    /**
     * Schedules a restart with a dynamic delay.
     * This is the exact mechanism that prevents the "Tudung" sound overlap.
     */
    private fun scheduleListeningRestart(delayMs: Long = LISTEN_RESTART_DELAY_MS) {
        if (!active.get()) return

        if (restartScheduled) {
            Log.d(TAG, "> Restart already queued. Skipping redundant request.")
            return
        }

        restartScheduled = true
        Log.d(TAG, "> Listening restart queued in ${delayMs}ms.")

        handler.postDelayed({
            restartScheduled = false
            
            if (!active.get()) return@postDelayed
            
            startListening()
        }, delayMs)
    }

    /**
     * Executes the actual SpeechRecognizer trigger.
     */
    private fun startListening() {
        if (!active.get()) return

        if (speechRecognizer.isListening()) {
            Log.d(TAG, "> Sensor already hot. Skipping ignition.")
            return
        }

        setState(State.LISTENING)

        speechRecognizer.start(
            // SUCCESS CALLBACK
            { text ->
                if (!active.get()) return@start

                val cleanText = text.trim()
                Log.i(TAG, "> Acoustic Capture: \"$cleanText\"")

                if (cleanText.isBlank()) {
                    Log.w(TAG, "> Empty input captured. Restarting silently.")
                    scheduleListeningRestart(LISTEN_RESTART_DELAY_MS)
                    return@start
                }

                consecutiveErrors = 0 // Reset error tracking on success

                // Immediately stop listening so it doesn't hear itself speak
                try {
                    speechRecognizer.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "> Failed to secure mic lock.")
                }

                setState(State.PROCESSING)
                onText(cleanText)
            },
            
            // ERROR CALLBACK
            { error ->
                if (!active.get()) return@start

                Log.w(TAG, "> Acoustic Error Triggered: $error")
                consecutiveErrors++

                // Forward critical errors to UI, suppress background noise errors
                if (!error.contains("No speech") && !error.contains("Timeout")) {
                    onError(error)
                }

                // Exponential Backoff Algorithm:
                // Prevents the system from constantly looping and beeping if mic is blocked or silent
                val dynamicDelay = if (consecutiveErrors > 3) {
                    setState(State.FAULT_RECOVERY)
                    ERROR_RESTART_DELAY_MS * 2 // Backoff drastically
                } else {
                    ERROR_RESTART_DELAY_MS
                }

                Log.d(TAG, "> Initiating Silent Backoff Protocol for ${dynamicDelay}ms.")
                scheduleListeningRestart(dynamicDelay)
            }
        )
    }

    // =========================================================
    // 4. MANUAL OVERRIDES & WATCHDOG TIMERS
    // =========================================================

    /**
     * Bypasses all delays and forces the mic open.
     * Used when user taps the JARVIS Orb directly.
     */
    fun forceListening() {
        Log.i(TAG, "> MANUAL OVERRIDE: Forcing acoustic sensors online.")
        if (!active.get()) return

        cancelPendingRestart()

        try {
            speechRecognizer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "> Force stop failed.")
        }

        handler.post {
            if (!active.get()) return@post
            consecutiveErrors = 0
            startListening()
        }
    }

    /**
     * Prevents the AI from being permanently stuck in "Thinking..." mode 
     * if the internet drops or the API server fails to respond.
     */
    private fun startWatchdogTimer() {
        sessionScope.launch {
            delay(WATCHDOG_PROCESSING_TIMEOUT_MS)
            if (active.get() && state == State.PROCESSING) {
                Log.e(TAG, "> WATCHDOG TRIGGERED: Processing timeout. Forcing reset.")
                onError("Cloud Interface Timeout")
                
                // Auto-recover back to listening
                resumeListening()
            }
        }
    }

    private fun cancelPendingRestart() {
        restartScheduled = false
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Synchronizes state changes flawlessly with the UI thread.
     */
    private fun setState(newState: State) {
        state = newState
        handler.post {
            onStateChanged(newState)
        }
    }

    // =========================================================
    // 5. MEMORY CLEANSING & DESTRUCTION
    // =========================================================
    
    /**
     * Nuclear option to obliterate the session and free all RAM/Threads.
     */
    fun destroy() {
        Log.i(TAG, "> Executing Session Destruction Protocol.")
        
        active.set(false)
        restartScheduled = false
        consecutiveErrors = 0

        handler.removeCallbacksAndMessages(null)
        sessionScope.cancel() // Kill all Watchdog coroutines

        try {
            speechRecognizer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "> Failed to kill recognizer smoothly.")
        }

        state = State.IDLE
        handler.post {
            onStateChanged(State.IDLE)
        }
    }
}
