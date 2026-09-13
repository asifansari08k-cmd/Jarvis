package com.example.jarvis

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.jarvis.automation.CommandExecutor
import com.example.jarvis.data.PreferencesManager
import com.example.jarvis.ui.ChatAdapter
import com.example.jarvis.ui.JarvisOrbView
import com.example.jarvis.ui.MainViewModel
import com.example.jarvis.ui.OrbState
import com.example.jarvis.ui.OverlayWindowManager
import com.example.jarvis.ui.VoiceOverlayManager
import com.example.jarvis.utils.PermissionHelper
import com.example.jarvis.voice.SpeechRecognizerManager
import com.example.jarvis.voice.TextToSpeechManager
import com.example.jarvis.voice.VoiceService
import com.example.jarvis.voice.VoiceSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * ============================================================================
 * J.A.R.V.I.S. TITAN CORE
 * ============================================================================
 *
 * AI Architecture:
 *
 * User
 *   ↓
 * Voice / Text
 *   ↓
 * MainActivity
 *   ↓
 * MainViewModel
 *   ↓
 * DeepSeekBridge
 *   ↓
 * DeepSeek App
 *   ↓
 * AccessibilityService
 *   ↓
 * DeepSeek Response
 *   ↓
 * JarvisCommandParser
 *   ↓
 * CommandExecutor
 *   ↓
 * Android Action
 *   ↓
 * TTS
 *
 * Gemini / Grok / ChatGPT API infrastructure removed.
 * ============================================================================
 */
class MainActivity :
    ComponentActivity(),
    SensorEventListener,
    TextToSpeech.OnInitListener {

    enum class SystemState {
        INITIALIZING,
        ONLINE,
        STANDBY,
        DIAGNOSTIC,
        CRITICAL_FAULT,
        OFFLINE,
        LISTENING,
        PROCESSING,
        SPEAKING,
        SECURITY_LOCK
    }

    companion object {

        private const val TAG = "JarvisTitanMaster"

        private const val ACTION_WAKE_WORD_DETECTED =
            "com.example.jarvis.WAKE_WORD_DETECTED"

        private const val ACTION_UPDATE_STATE =
            "com.example.jarvis.UPDATE_STATE"

        private const val SHAKE_ACCEL_THRESHOLD = 18.0f

        private const val HUD_COLOR_CYAN = "#00E5FF"
        private const val HUD_COLOR_RED = "#FF1744"
        private const val HUD_COLOR_GREEN = "#00E676"
        private const val HUD_COLOR_ORANGE = "#FF9100"
        private const val HUD_COLOR_BLACK_BG = "#050811"
        private const val HUD_COLOR_BLACK_TRANSPARENT = "#88000000"
    }

    // ========================================================================
    // CORE
    // ========================================================================

    private lateinit var viewModel: MainViewModel
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var prefs: PreferencesManager
    private lateinit var commandExecutor: CommandExecutor

    private lateinit var localDatabase: JarvisDatabaseHelper

    // ========================================================================
    // VOICE / UI
    // ========================================================================

    private lateinit var overlayManager: OverlayWindowManager

    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    private lateinit var voiceSessionManager: VoiceSessionManager
    private lateinit var voiceOverlayManager: VoiceOverlayManager

    private lateinit var textToSpeechEngine: TextToSpeech
    private lateinit var textToSpeechManager: TextToSpeechManager

    // ========================================================================
    // SYSTEM
    // ========================================================================

    private lateinit var audioManager: AudioManager
    private lateinit var activityManager: ActivityManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var deepDiagnosticsEngine: DeepSystemDiagnostics

    // ========================================================================
    // HARDWARE
    // ========================================================================

    private lateinit var sensorManager: SensorManager

    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null

    private lateinit var cameraManager: CameraManager
    private var mainCameraId: String? = null
    private var isTorchActive = false

    // ========================================================================
    // UI
    // ========================================================================

    private var micToggleButton: ImageButton? = null
    private var messageInputBox: EditText? = null
    private var sendCommandButton: ImageButton? = null
    private var holographicOrbView: JarvisOrbView? = null
    private var mainRecyclerView: RecyclerView? = null

    private lateinit var masterRootLayout: ViewGroup

    private lateinit var dynamicTelemetryHUD: TextView
    private lateinit var programmaticTerminalLog: TextView
    private lateinit var terminalScrollView: ScrollView

    private lateinit var aiSwitcherPanel: LinearLayout
    private lateinit var tvPoweredByAI: TextView

    private var matrixBackground: MatrixDigitalRainView? = null
    private var strobeJob: Job? = null

    // ========================================================================
    // STATE
    // ========================================================================

    private var isBackgroundCommandExecuting = false

    private val mainThreadHandler =
        Handler(Looper.getMainLooper())

    private var currentSystemState =
        SystemState.INITIALIZING

    // ========================================================================
    // TELEMETRY
    // ========================================================================

    private var currentBatteryLevel = -1
    private var currentBatteryTemp = -1f
    private var currentBatteryVoltage = -1
    private var isDeviceCharging = false
    private var batteryHealthStr = "UNKNOWN"

    private var isNetworkAvailable = false
    private var ambientLightLux = 0f

    // ========================================================================
    // SENSOR CACHE
    // ========================================================================

    private var accelLastX = 0f
    private var accelLastY = 0f
    private var accelLastZ = 0f
    private var isShakeInitialized = false

    // ========================================================================
    // BACKGROUND WAKE RECEIVER
    // ========================================================================

    private val backgroundWakeReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (intent?.action != ACTION_WAKE_WORD_DETECTED) {
                    return
                }

                val command =
                    intent.getStringExtra(
                        "INITIAL_COMMAND"
                    ) ?: "Jarvis"

                printToTerminal(
                    "> IPC ALERT: Background Wake Activated."
                )

                isBackgroundCommandExecuting = true

                mainThreadHandler.postDelayed({

                    messageInputBox?.setText(command)

                    messageInputBox?.setSelection(
                        messageInputBox?.length() ?: 0
                    )

                    evaluateAndExecuteMasterCommand(
                        command
                    )

                    messageInputBox?.text?.clear()

                }, 200)
            }
        }

    // ========================================================================
    // BATTERY RECEIVER
    // ========================================================================

    private val powerTelemetryReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent
            ) {

                currentBatteryLevel =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_LEVEL,
                        -1
                    )

                currentBatteryTemp =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE,
                        -1
                    ) / 10f

                currentBatteryVoltage =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_VOLTAGE,
                        -1
                    )

                val status =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_STATUS,
                        -1
                    )

                isDeviceCharging =
                    status ==
                        BatteryManager.BATTERY_STATUS_CHARGING ||
                    status ==
                        BatteryManager.BATTERY_STATUS_FULL

                val healthStatus =
                    intent.getIntExtra(
                        BatteryManager.EXTRA_HEALTH,
                        -1
                    )

                batteryHealthStr =
                    when (healthStatus) {

                        BatteryManager.BATTERY_HEALTH_GOOD ->
                            "GOOD"

                        BatteryManager.BATTERY_HEALTH_OVERHEAT ->
                            "OVERHEAT"

                        BatteryManager.BATTERY_HEALTH_DEAD ->
                            "DEAD"

                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                            "OVER_VOLTAGE"

                        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                            "FAILURE"

                        else ->
                            "UNKNOWN"
                    }

                updateProgrammaticHUD()
            }
        }

    // ========================================================================
    // NETWORK TELEMETRY
    // ========================================================================

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(
                network: Network
            ) {

                isNetworkAvailable = true

                runOnUiThread {

                    printToTerminal(
                        "> NETWORK: Internet connection established."
                    )

                    updateProgrammaticHUD()
                }
            }

            override fun onLost(
                network: Network
            ) {

                isNetworkAvailable = false

                runOnUiThread {

                    printToTerminal(
                        "> NETWORK: Connection lost."
                    )

                    updateProgrammaticHUD()
                }
            }
        }

    // ========================================================================
    // CREATE
    // ========================================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.statusBarColor =
            Color.parseColor(
                HUD_COLOR_BLACK_BG
            )

        setContentView(
            R.layout.activity_main
        )

        val root =
            findViewById<View>(
                android.R.id.content
            )

        if (root is ViewGroup) {
            masterRootLayout = root
        } else {
            throw IllegalStateException(
                "Root view is not a ViewGroup."
            )
        }

        printBootLogHeaders()

        executeTitanInitializationSequence()
    }

    // ========================================================================
    // BOOT
    // ========================================================================

    private fun printBootLogHeaders() {

        Log.i(
            TAG,
            "||==============================================||"
        )

        Log.i(
            TAG,
            "|| J.A.R.V.I.S. TITAN CORE                    ||"
        )

        Log.i(
            TAG,
            "|| AI ENGINE: DEEPSEEK APP                    ||"
        )

        Log.i(
            TAG,
            "|| MODE: ACCESSIBILITY BRIDGE                 ||"
        )

        Log.i(
            TAG,
            "||==============================================||"
        )
    }

    private fun executeTitanInitializationSequence() {

        initializeDatabasesAndStorage()

        initializeHardwareSubsystems()

        initializeNetworkSubsystem()

        initializeCoreManagers()

        injectProgrammaticMatrixBackground()

        injectProgrammaticHUD()

        injectProgrammaticTerminalAndControls()

        bindNativeUserInterfaceDynamically()

        setupChatRecyclerView()

        setupInteractiveClickListenersDynamically()

        initializeNativeTTSEngine()

        setupVoiceNeuralEngine()

        setupAICloudListener()

        registerReceiver(
            powerTelemetryReceiver,
            IntentFilter(
                Intent.ACTION_BATTERY_CHANGED
            )
        )

        registerBackgroundIPCReceiver()

        runStartupDiagnosticSequence()
    }

    // ========================================================================
    // DATABASE
    // ========================================================================

    private fun initializeDatabasesAndStorage() {

        Log.d(
            TAG,
            "Booting local memory..."
        )

        localDatabase =
            JarvisDatabaseHelper(this)

        deepDiagnosticsEngine =
            DeepSystemDiagnostics(this)
    }

    // ========================================================================
    // CORE MANAGERS
    // ========================================================================

    private fun initializeCoreManagers() {

        Log.d(
            TAG,
            "Loading JARVIS Core..."
        )

        viewModel =
            ViewModelProvider(this)[
                MainViewModel::class.java
            ]

        viewModel.initializeExecutor(this)

        prefs =
            PreferencesManager(this)

        commandExecutor =
            CommandExecutor(this)

        speechRecognizerManager =
            SpeechRecognizerManager(this)

        voiceOverlayManager =
            VoiceOverlayManager(this)

        overlayManager =
            OverlayWindowManager(this)

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        activityManager =
            getSystemService(
                Context.ACTIVITY_SERVICE
            ) as ActivityManager
    }

    // ========================================================================
    // TTS
    // ========================================================================

    private fun initializeNativeTTSEngine() {

        Log.d(
            TAG,
            "Booting TTS..."
        )

        textToSpeechEngine =
            TextToSpeech(
                this,
                this
            )

        textToSpeechManager =
            TextToSpeechManager(this)
    }

    // ========================================================================
    // HARDWARE
    // ========================================================================

    private fun initializeHardwareSubsystems() {

        sensorManager =
            getSystemService(
                Context.SENSOR_SERVICE
            ) as SensorManager

        proximitySensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_PROXIMITY
            )

        lightSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_LIGHT
            )

        accelerometerSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
            )

        magneticSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_MAGNETIC_FIELD
            )

        gyroscopeSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
            )

        cameraManager =
            getSystemService(
                Context.CAMERA_SERVICE
            ) as CameraManager

        try {

            mainCameraId =
                cameraManager.cameraIdList.firstOrNull { id ->

                    val characteristics =
                        cameraManager.getCameraCharacteristics(id)

                    characteristics.get(
                        android.hardware.camera2
                            .CameraCharacteristics
                            .FLASH_INFO_AVAILABLE
                    ) == true
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Camera flash unavailable",
                e
            )
        }
    }

    // ========================================================================
    // NETWORK
    // ========================================================================

    private fun initializeNetworkSubsystem() {

        connectivityManager =
            getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val request =
            NetworkRequest.Builder()
                .addCapability(
                    NetworkCapabilities
                        .NET_CAPABILITY_INTERNET
                )
                .build()

        try {

            connectivityManager.registerNetworkCallback(
                request,
                networkCallback
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Network callback registration failed",
                e
            )
        }
    }

    // ========================================================================
    // DYNAMIC UI
    // ========================================================================

    @SuppressLint("DiscouragedApi")
    private fun bindNativeUserInterfaceDynamically() {

        try {

            val inputId =
                resources.getIdentifier(
                    "messageInput",
                    "id",
                    packageName
                )

            if (inputId != 0) {
                messageInputBox =
                    findViewById(inputId)
            }

            val micId =
                resources.getIdentifier(
                    "micButton",
                    "id",
                    packageName
                )

            if (micId != 0) {
                micToggleButton =
                    findViewById(micId)
            }

            val sendId =
                resources.getIdentifier(
                    "sendButton",
                    "id",
                    packageName
                )

            if (sendId != 0) {
                sendCommandButton =
                    findViewById(sendId)
            }

            var orbId =
                resources.getIdentifier(
                    "mainJarvisOrb",
                    "id",
                    packageName
                )

            if (orbId == 0) {

                orbId =
                    resources.getIdentifier(
                        "jarvisOrbView",
                        "id",
                        packageName
                    )
            }

            if (orbId != 0) {

                holographicOrbView =
                    findViewById(orbId)

                holographicOrbView?.setOrbState(
                    OrbState.IDLE
                )
            }

            val recyclerId =
                resources.getIdentifier(
                    "messageRecyclerView",
                    "id",
                    packageName
                )

            if (recyclerId != 0) {

                mainRecyclerView =
                    findViewById(recyclerId)
            }

            printToTerminal(
                "> Native UI bindings initialized."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "UI binding failed",
                e
            )
        }
    }

    // ========================================================================
    // MATRIX BACKGROUND
    // ========================================================================

    private fun injectProgrammaticMatrixBackground() {

        matrixBackground =
            MatrixDigitalRainView(this)

        val layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

        masterRootLayout.addView(
            matrixBackground,
            0,
            layoutParams
        )
    }

    // ========================================================================
    // HUD
    // ========================================================================

    private fun injectProgrammaticHUD() {

        dynamicTelemetryHUD =
            TextView(this).apply {

                text =
                    "J.A.R.V.I.S. | DEEPSEEK CORE LOADING..."

                setTextColor(
                    Color.parseColor(
                        HUD_COLOR_CYAN
                    )
                )

                textSize = 9f

                gravity = Gravity.CENTER

                setPadding(
                    10,
                    20,
                    10,
                    20
                )

                setBackgroundColor(
                    Color.parseColor(
                        HUD_COLOR_BLACK_TRANSPARENT
                    )
                )

                typeface =
                    Typeface.MONOSPACE

                letterSpacing = 0.05f
            }

        val params =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity = Gravity.TOP

                topMargin = 50
            }

        masterRootLayout.addView(
            dynamicTelemetryHUD,
            params
        )
    }

    // ========================================================================
    // DEEPSEEK ENGINE PANEL
    // ========================================================================

    private fun injectProgrammaticTerminalAndControls() {

        terminalScrollView =
            ScrollView(this).apply {

                setBackgroundColor(
                    Color.parseColor(
                        HUD_COLOR_BLACK_TRANSPARENT
                    )
                )

                setPadding(
                    16,
                    16,
                    16,
                    16
                )
            }

        programmaticTerminalLog =
            TextView(this).apply {

                text =
                    "> J.A.R.V.I.S. Core Booting...\n" +
                    "> DeepSeek Bridge Loading..."

                setTextColor(
                    Color.parseColor(
                        HUD_COLOR_CYAN
                    )
                )

                textSize = 11f

                typeface =
                    Typeface.MONOSPACE
            }

        terminalScrollView.addView(
            programmaticTerminalLog
        )

        val terminalParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                300
            ).apply {

                gravity = Gravity.TOP

                topMargin = 150

                leftMargin = 30

                rightMargin = 30
            }

        masterRootLayout.addView(
            terminalScrollView,
            terminalParams
        )

        // ------------------------------------------------------------
        // DeepSeek-only engine indicator
        // ------------------------------------------------------------

        aiSwitcherPanel =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    20,
                    20,
                    20,
                    20
                )
            }

        tvPoweredByAI =
            TextView(this).apply {

                text =
                    "ENGINE: DEEPSEEK"

                setTextColor(
                    Color.WHITE
                )

                textSize = 10f

                typeface =
                    Typeface.DEFAULT_BOLD

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    0,
                    0,
                    10
                )
            }

        aiSwitcherPanel.addView(
            tvPoweredByAI
        )

        val panelParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.BOTTOM or
                    Gravity.CENTER_HORIZONTAL

                bottomMargin = 350
            }

        masterRootLayout.addView(
            aiSwitcherPanel,
            panelParams
        )
    }

    // ========================================================================
    // CHAT
    // ========================================================================

    private fun setupChatRecyclerView() {

        chatAdapter =
            ChatAdapter()

        mainRecyclerView?.layoutManager =
            LinearLayoutManager(this).apply {

                stackFromEnd = true
            }

        mainRecyclerView?.adapter =
            chatAdapter
    }

    // ========================================================================
    // HUD UPDATE
    // ========================================================================

    @SuppressLint("SetTextI18n")
    private fun updateProgrammaticHUD() {

        val netStr =
            if (isNetworkAvailable)
                "ONLINE"
            else
                "OFFLINE"

        val chgStr =
            if (isDeviceCharging)
                "AC"
            else
                "BAT"

        val memCount =
            localDatabase.getHistoryCount()

        val ramUsage =
            deepDiagnosticsEngine
                .getRamUsagePercentage()

        val cpuTemp =
            deepDiagnosticsEngine
                .estimateCpuTemp()

        val hudText =
            "CORE: DEEPSEEK | " +
            "NET: $netStr | " +
            "PWR: $currentBatteryLevel% [$chgStr] | " +
            "TMP: ${currentBatteryTemp}C " +
            "(CPU:$cpuTemp) | " +
            "HLT: $batteryHealthStr | " +
            "VOLT: ${currentBatteryVoltage}mV | " +
            "RAM: $ramUsage% | " +
            "LUX: $ambientLightLux | " +
            "SQL: $memCount"

        if (::dynamicTelemetryHUD.isInitialized) {

            dynamicTelemetryHUD.text =
                hudText
        }
    }

    // ========================================================================
    // TERMINAL
    // ========================================================================

    private fun printToTerminal(
        message: String
    ) {

        mainThreadHandler.post {

            if (!::programmaticTerminalLog.isInitialized) {
                return@post
            }

            val current =
                programmaticTerminalLog.text.toString()

            val lines =
                current.split("\n")

            val newText =
                if (lines.size > 80) {

                    lines.drop(1)
                        .joinToString("\n") +
                        "\n$message"

                } else {

                    "$current\n$message"
                }

            programmaticTerminalLog.text =
                newText

            terminalScrollView.post {

                terminalScrollView.fullScroll(
                    ScrollView.FOCUS_DOWN
                )
            }
        }
    }

    // ========================================================================
    // VOICE ENGINE
    // ========================================================================

    private fun setupVoiceNeuralEngine() {

        voiceSessionManager =
            VoiceSessionManager(

                context = this,

                speechRecognizer =
                    speechRecognizerManager,

                onText = { recognizedText ->

                    mainThreadHandler.post {

                        val cleanText =
                            recognizedText.trim()

                        if (cleanText.isNotBlank()) {

                            messageInputBox?.setText(
                                cleanText
                            )

                            messageInputBox?.setSelection(
                                messageInputBox?.length() ?: 0
                            )

                            evaluateAndExecuteMasterCommand(
                                cleanText
                            )
                        }
                    }
                },

                onStateChanged = { sessionState ->

                    mainThreadHandler.post {

                        when (sessionState) {

                            VoiceSessionManager.State.IDLE ->
                                synchronizeHolographicState(
                                    OrbState.IDLE,
                                    "Standby"
                                )

                            VoiceSessionManager.State.LISTENING ->
                                synchronizeHolographicState(
                                    OrbState.LISTENING,
                                    "Listening..."
                                )

                            VoiceSessionManager.State.PROCESSING ->
                                synchronizeHolographicState(
                                    OrbState.THINKING,
                                    "DeepSeek processing..."
                                )

                            VoiceSessionManager.State.SPEAKING ->
                                synchronizeHolographicState(
                                    OrbState.SPEAKING,
                                    "Transmitting..."
                                )

                            VoiceSessionManager.State.FAULT_RECOVERY ->
                                synchronizeHolographicState(
                                    OrbState.ERROR,
                                    "Recovering..."
                                )

                            else ->
                                synchronizeHolographicState(
                                    OrbState.IDLE,
                                    "Standby"
                                )
                        }
                    }
                },

                onError = { errorCode ->

                    mainThreadHandler.post {

                        synchronizeHolographicState(
                            OrbState.ERROR,
                            "Acoustic Error: $errorCode"
                        )

                        printToTerminal(
                            "> Acoustic Engine Fault: $errorCode"
                        )
                    }
                }
            )
    }

    // ========================================================================
    // STATE SYNCHRONIZER
    // ========================================================================

    private fun synchronizeHolographicState(
        state: OrbState,
        subText: String
    ) {

        currentSystemState =
            when (state) {

                OrbState.IDLE ->
                    SystemState.STANDBY

                OrbState.LISTENING ->
                    SystemState.LISTENING

                OrbState.THINKING ->
                    SystemState.PROCESSING

                OrbState.SPEAKING ->
                    SystemState.SPEAKING

                OrbState.ERROR ->
                    SystemState.CRITICAL_FAULT
            }

        holographicOrbView?.setOrbState(
            state
        )

        if (::voiceOverlayManager.isInitialized) {

            voiceOverlayManager.updateState(
                state
            )
        }

        micToggleButton?.alpha =
            if (state == OrbState.LISTENING)
                1.0f
            else
                0.7f

        val syncIntent =
            Intent(
                ACTION_UPDATE_STATE
            ).apply {

                putExtra(
                    "extra_state",
                    state.name
                )
            }

        sendBroadcast(syncIntent)

        val targetColor =
            when (state) {

                OrbState.ERROR ->
                    Color.parseColor(
                        "#44FF0000"
                    )

                OrbState.THINKING ->
                    Color.parseColor(
                        "#3300E5FF"
                    )

                OrbState.LISTENING ->
                    Color.parseColor(
                        "#3300FF00"
                    )

                OrbState.SPEAKING ->
                    Color.parseColor(
                        "#33FF9100"
                    )

                else ->
                    Color.parseColor(
                        HUD_COLOR_BLACK_BG
                    )
            }

        val animator =
            ObjectAnimator.ofArgb(
                masterRootLayout,
                "backgroundColor",
                targetColor
            )

        animator.duration = 500

        animator.interpolator =
            AccelerateDecelerateInterpolator()

        animator.start()

        matrixBackground?.updateHologramColor(
            state
        )
    }

    // ========================================================================
    // RESPONSE LISTENER
    // ========================================================================

    private fun setupAICloudListener() {

        viewModel.setResponseListener { response ->

            mainThreadHandler.post {

                val safeResponse =
                    response.trim()

                if (safeResponse.isBlank()) {
                    return@post
                }

                localDatabase.logInteraction(
                    "DeepSeek",
                    safeResponse,
                    "DEEPSEEK_RESPONSE"
                )

                updateProgrammaticHUD()

                printToTerminal(
                    "> DeepSeek Response: " +
                    safeResponse.take(60) +
                    if (safeResponse.length > 60)
                        "..."
                    else
                        ""
                )

                if (voiceSessionManager.isActive()) {

                    executeVoiceOutputInForeground(
                        safeResponse
                    )

                } else if (
                    isBackgroundCommandExecuting
                ) {

                    executeVoiceOutputInBackground(
                        safeResponse
                    )

                } else {

                    synchronizeHolographicState(
                        OrbState.SPEAKING,
                        "Transmitting"
                    )

                    speakTextNative(
                        safeResponse
                    ) {

                        mainThreadHandler.post {

                            synchronizeHolographicState(
                                OrbState.IDLE,
                                "Standby"
                            )
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // BACKGROUND IPC
    // ========================================================================

    private fun registerBackgroundIPCReceiver() {

        val filter =
            IntentFilter().apply {

                addAction(
                    ACTION_WAKE_WORD_DETECTED
                )
            }

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                registerReceiver(
                    backgroundWakeReceiver,
                    filter,
                    RECEIVER_NOT_EXPORTED
                )

            } else {

                registerReceiver(
                    backgroundWakeReceiver,
                    filter
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "IPC registration failed",
                e
            )
        }
    }

    // ========================================================================
    // MASTER COMMAND ROUTER
    // ========================================================================

    private fun evaluateAndExecuteMasterCommand(
        rawInput: String
    ) {

        val normalized =
            rawInput
                .lowercase(
                    Locale.getDefault()
                )
                .replace(
                    "hey jarvis",
                    ""
                )
                .replace(
                    "ok jarvis",
                    ""
                )
                .replace(
                    "jarvis",
                    ""
                )
                .trim()

        if (normalized.isBlank()) {

            if (voiceSessionManager.isActive()) {

                speakCommandFeedback(
                    "Yes sir, awaiting protocols."
                )

            } else {

                synchronizeHolographicState(
                    OrbState.IDLE,
                    "Standby"
                )
            }

            return
        }

        localDatabase.logInteraction(
            normalized,
            "Processing...",
            "USER_QUERY"
        )

        updateProgrammaticHUD()

        synchronizeHolographicState(
            OrbState.THINKING,
            "Parsing semantic intent..."
        )

        printToTerminal(
            "> Input Vector: \"$normalized\""
        )

        // ====================================================================
        // LOCAL MATH
        // ====================================================================

        val mathRegex =
            Regex(
                ".*(calculate|math|plus|minus|multiply|divided|times|power|root|sin|cos|tan|log).*"
            )

        if (normalized.matches(mathRegex)) {

            try {

                val equationStr =
                    normalized
                        .replace(
                            "plus",
                            "+"
                        )
                        .replace(
                            "minus",
                            "-"
                        )
                        .replace(
                            "times",
                            "*"
                        )
                        .replace(
                            "multiplied by",
                            "*"
                        )
                        .replace(
                            "divided by",
                            "/"
                        )
                        .replace(
                            "over",
                            "/"
                        )
                        .replace(
                            "power",
                            "^"
                        )
                        .replace(
                            "root",
                            "sqrt"
                        )
                        .replace(
                            "sine",
                            "sin"
                        )
                        .replace(
                            "cosine",
                            "cos"
                        )
                        .replace(
                            Regex(
                                "[^0-9\\+\\-\\*\\/\\(\\)\\.\\^a-z]"
                            ),
                            ""
                        )

                val result =
                    AdvancedScientificParser()
                        .evaluate(
                            equationStr
                        )

                val formatRes =
                    if (result % 1.0 == 0.0) {

                        result
                            .toLong()
                            .toString()

                    } else {

                        String.format(
                            Locale.US,
                            "%.4f",
                            result
                        )
                    }

                localDatabase.logInteraction(
                    "Math Engine",
                    formatRes,
                    "MATH_SOLVED"
                )

                speakCommandFeedback(
                    "Sir, the scientific calculation evaluates to $formatRes."
                )

                return

            } catch (e: Exception) {

                printToTerminal(
                    "> Math Engine: syntax unresolved."
                )
            }
        }

        // ====================================================================
        // HARDWARE
        // ====================================================================

        if (
            normalized.contains("torch on") ||
            normalized.contains("light on")
        ) {

            operateHardwareFlashlight(1)

            speakCommandFeedback(
                "Optical illumination engaged."
            )

            return
        }

        if (
            normalized.contains("torch off") ||
            normalized.contains("light off")
        ) {

            operateHardwareFlashlight(0)

            speakCommandFeedback(
                "Optical illumination disengaged."
            )

            return
        }

        if (
            normalized.contains("strobe mode") ||
            normalized.contains("disco light")
        ) {

            operateHardwareFlashlight(2)

            speakCommandFeedback(
                "Strobe protocol active."
            )

            return
        }

        if (
            normalized.contains("sos mode")
        ) {

            operateHardwareFlashlight(3)

            speakCommandFeedback(
                "Visual SOS protocol active."
            )

            return
        }

        if (
            normalized.contains("vibrate") ||
            normalized.contains("haptic")
        ) {

            triggerHapticFeedback(
                1200
            )

            speakCommandFeedback(
                "Haptic resonance activated."
            )

            return
        }

        if (
            normalized.contains("ambient light") ||
            normalized.contains("how dark")
        ) {

            speakCommandFeedback(
                "Ambient light is currently $ambientLightLux lux."
            )

            return
        }

        // ====================================================================
        // SYSTEM TELEMETRY
        // ====================================================================

        if (
            normalized.contains("battery") ||
            normalized.contains("power level")
        ) {

            val status =
                if (isDeviceCharging)
                    "charging"
                else
                    "discharging"

            speakCommandFeedback(
                "Sir, the power cell is at " +
                "$currentBatteryLevel percent and is $status. " +
                "Core thermal output is " +
                "$currentBatteryTemp degrees Celsius. " +
                "Battery health is $batteryHealthStr."
            )

            return
        }

        if (
            normalized.contains("system status") ||
            normalized.contains("diagnostics")
        ) {

            val ramPercent =
                deepDiagnosticsEngine
                    .getRamUsagePercentage()

            speakCommandFeedback(
                "All systems nominal. " +
                "Battery at $currentBatteryLevel percent. " +
                "RAM utilization is $ramPercent percent. " +
                "Network is " +
                if (isNetworkAvailable)
                    "online."
                else
                    "offline."
            )

            return
        }

        if (
            normalized.contains("clear memory") ||
            normalized.contains("purge database")
        ) {

            val count =
                localDatabase.getHistoryCount()

            localDatabase.clearMemory()

            updateProgrammaticHUD()

            speakCommandFeedback(
                "Memory override complete. " +
                "$count interaction logs erased."
            )

            return
        }

        if (
            normalized.contains("export logs") ||
            normalized.contains("download memory")
        ) {

            localDatabase.exportDatabaseToCSV(
                this
            )

            speakCommandFeedback(
                "Memory logs exported successfully."
            )

            return
        }

        if (
            normalized.contains("time") ||
            normalized.contains("samay")
        ) {

            val currentTime =
                SimpleDateFormat(
                    "h:mm a",
                    Locale.getDefault()
                ).format(Date())

            speakCommandFeedback(
                "The current time is $currentTime, sir."
            )

            return
        }

        if (
            normalized.contains("date") ||
            normalized.contains("tarikh")
        ) {

            val currentDate =
                SimpleDateFormat(
                    "EEEE, MMMM d, yyyy",
                    Locale.getDefault()
                ).format(Date())

            speakCommandFeedback(
                "Today is $currentDate."
            )

            return
        }

        // ====================================================================
        // SIMPLE NATIVE APP ROUTING
        // ====================================================================

        if (
            normalized.contains("instagram open") ||
            normalized.contains("open instagram")
        ) {

            openUrl(
                "instagram://user?username=drakoxnaeem"
            )

            speakCommandFeedback(
                "Opening Instagram."
            )

            return
        }

        if (
            normalized.contains("youtube open") ||
            normalized.contains("open youtube")
        ) {

            openUrl(
                "vnd.youtube:"
            )

            speakCommandFeedback(
                "Opening YouTube."
            )

            return
        }

        if (
            normalized.contains("whatsapp open") ||
            normalized.contains("open whatsapp")
        ) {

            openUrl(
                "whatsapp://"
            )

            speakCommandFeedback(
                "Opening WhatsApp."
            )

            return
        }

        if (
            normalized.contains("open settings")
        ) {

            startActivity(
                Intent(
                    Settings.ACTION_SETTINGS
                )
            )

            speakCommandFeedback(
                "Opening device settings."
            )

            return
        }

        // ====================================================================
        // DEEPSEEK
        // ====================================================================

        printToTerminal(
            "> Routing request to DeepSeek..."
        )

        if (!isNetworkAvailable) {

            printToTerminal(
                "> WARNING: Internet connection unavailable."
            )

            speakCommandFeedback(
                "Sir, internet connection is unavailable."
            )

            return
        }

        if (
            voiceSessionManager.isActive()
        ) {

            voiceSessionManager.setProcessing()
        }

        /*
         * IMPORTANT:
         *
         * MainViewModel.send() now accepts only the text.
         *
         * No API key.
         * No Gemini.
         * No direct HTTP API.
         */
        try {

            viewModel.send(
                normalized
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "DeepSeek request failed",
                e
            )

            printToTerminal(
                "> DEEPSEEK BRIDGE ERROR: ${e.message}"
            )

            speakCommandFeedback(
                "Sir, DeepSeek se communication establish nahi ho saka."
            )
        }
    }

    // ========================================================================
    // FOREGROUND VOICE OUTPUT
    // ========================================================================

    private fun executeVoiceOutputInForeground(
        speechText: String
    ) {

        if (!voiceSessionManager.isActive()) {
            return
        }

        voiceSessionManager.setSpeaking()

        synchronizeHolographicState(
            OrbState.SPEAKING,
            "Transmitting"
        )

        speechRecognizerManager.stop()

        printToTerminal(
            "> Audio: $speechText"
        )

        speakTextNative(
            speechText
        ) {

            mainThreadHandler.post {

                if (
                    voiceSessionManager.isActive()
                ) {

                    voiceSessionManager.resumeListening()

                    synchronizeHolographicState(
                        OrbState.LISTENING,
                        "Listening..."
                    )

                } else {

                    synchronizeHolographicState(
                        OrbState.IDLE,
                        "Standby"
                    )
                }
            }
        }
    }

    // ========================================================================
    // BACKGROUND VOICE OUTPUT
    // ========================================================================

    private fun executeVoiceOutputInBackground(
        speechText: String
    ) {

        synchronizeHolographicState(
            OrbState.SPEAKING,
            "Transmitting"
        )

        printToTerminal(
            "> Background Audio: $speechText"
        )

        speakTextNative(
            speechText
        ) {

            mainThreadHandler.post {

                isBackgroundCommandExecuting =
                    false

                synchronizeHolographicState(
                    OrbState.IDLE,
                    "Standby"
                )

                try {

                    val resumeIntent =
                        Intent(
                            this,
                            VoiceService::class.java
                        ).apply {

                            action =
                                "com.example.jarvis.VOICE_RESPONSE_FINISHED"
                        }

                    startService(
                        resumeIntent
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Failed to signal VoiceService",
                        e
                    )
                }
            }
        }
    }

    // ========================================================================
    // COMMAND FEEDBACK
    // ========================================================================

    private fun speakCommandFeedback(
        feedbackText: String
    ) {

        if (
            voiceSessionManager.isActive()
        ) {

            executeVoiceOutputInForeground(
                feedbackText
            )

        } else if (
            isBackgroundCommandExecuting
        ) {

            executeVoiceOutputInBackground(
                feedbackText
            )

        } else {

            synchronizeHolographicState(
                OrbState.SPEAKING,
                "Transmitting"
            )

            printToTerminal(
                "> System Audio: $feedbackText"
            )

            speakTextNative(
                feedbackText
            ) {

                mainThreadHandler.post {

                    synchronizeHolographicState(
                        OrbState.IDLE,
                        "Standby"
                    )
                }
            }
        }
    }

    // ========================================================================
    // NATIVE TTS
    // ========================================================================

    private fun speakTextNative(
        text: String,
        onFinished: (() -> Unit)? = null
    ) {

        textToSpeechEngine
            .setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {

                    override fun onStart(
                        utteranceId: String?
                    ) {
                    }

                    override fun onDone(
                        utteranceId: String?
                    ) {

                        mainThreadHandler.post {
                            onFinished?.invoke()
                        }
                    }

                    override fun onError(
                        utteranceId: String?
                    ) {

                        mainThreadHandler.post {
                            onFinished?.invoke()
                        }
                    }
                }
            )

        val params =
            Bundle()

        params.putFloat(
            TextToSpeech.Engine.KEY_PARAM_VOLUME,
            1.0f
        )

        textToSpeechEngine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "JARVIS_NATIVE_TTS"
        )
    }

    override fun onInit(
        status: Int
    ) {

        if (status ==
            TextToSpeech.SUCCESS
        ) {

            val result =
                textToSpeechEngine.setLanguage(
                    Locale("en", "IN")
                )

            if (
                result ==
                TextToSpeech.LANG_MISSING_DATA ||
                result ==
                TextToSpeech.LANG_NOT_SUPPORTED
            ) {

                printToTerminal(
                    "> TTS language data unavailable."
                )

            } else {

                printToTerminal(
                    "> TTS subsystem operational."
                )
            }

        } else {

            printToTerminal(
                "> CRITICAL: TTS initialization failed."
            )
        }
    }

    // ========================================================================
    // CLICK LISTENERS
    // ========================================================================

    @SuppressLint("DiscouragedApi")
    private fun setupInteractiveClickListenersDynamically() {

        sendCommandButton?.setOnClickListener {

            val typedMessage =
                messageInputBox
                    ?.text
                    ?.toString()
                    ?.trim()
                    ?: ""

            if (
                typedMessage.isNotEmpty()
            ) {

                synchronizeHolographicState(
                    OrbState.THINKING,
                    "Processing..."
                )

                evaluateAndExecuteMasterCommand(
                    typedMessage
                )

                messageInputBox
                    ?.text
                    ?.clear()
            }
        }

        micToggleButton?.setOnClickListener {

            if (
                !PermissionHelper
                    .hasAudioPermission(this)
            ) {

                PermissionHelper
                    .requestAudioPermission(this)

                return@setOnClickListener
            }

            if (
                voiceSessionManager.isActive()
            ) {

                stopVoiceMode()

            } else {

                startVoiceMode()
            }
        }

        safelyBindClick(
            "btnWeb"
        ) {

            openUrl(
                "https://www.google.com"
            )
        }

        safelyBindClick(
            "btnInsta"
        ) {

            openUrl(
                "instagram://user?username=drakoxnaeem"
            )
        }

        safelyBindClick(
            "btnFb"
        ) {

            openUrl(
                "https://www.facebook.com"
            )
        }

        safelyBindClick(
            "settingsButton"
        ) {

            startActivity(
                Intent(
                    Settings.ACTION_SETTINGS
                )
            )
        }

        safelyBindClick(
            "btnSettings"
        ) {

            startActivity(
                Intent(
                    Settings.ACTION_SETTINGS
                )
            )
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun safelyBindClick(
        idName: String,
        action: () -> Unit
    ) {

        val resId =
            resources.getIdentifier(
                idName,
                "id",
                packageName
            )

        if (resId != 0) {

            findViewById<View>(
                resId
            )?.setOnClickListener {

                action()
            }
        }
    }

    // ========================================================================
    // VOICE CONTROL
    // ========================================================================

    private fun startVoiceMode() {

        textToSpeechEngine.stop()

        isBackgroundCommandExecuting =
            false

        voiceSessionManager.start()

        synchronizeHolographicState(
            OrbState.LISTENING,
            "Listening..."
        )

        triggerHapticFeedback(
            100
        )
    }

    private fun stopVoiceMode() {

        voiceSessionManager.stop()

        speechRecognizerManager.stop()

        textToSpeechEngine.stop()

        voiceOverlayManager.hide()

        synchronizeHolographicState(
            OrbState.IDLE,
            "Standby"
        )
    }

    // ========================================================================
    // URL
    // ========================================================================

    private fun openUrl(
        url: String
    ) {

        if (url.isBlank()) {
            return
        }

        printToTerminal(
            "> Routing: $url"
        )

        try {

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "URL execution failure",
                e
            )
        }
    }

    // ========================================================================
    // FLASHLIGHT
    // ========================================================================

    private fun operateHardwareFlashlight(
        mode: Int
    ) {

        strobeJob?.cancel()

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M &&
                mainCameraId != null
            ) {

                when (mode) {

                    0 -> {

                        cameraManager.setTorchMode(
                            mainCameraId!!,
                            false
                        )

                        isTorchActive = false
                    }

                    1 -> {

                        cameraManager.setTorchMode(
                            mainCameraId!!,
                            true
                        )

                        isTorchActive = true
                    }

                    2 -> {

                        strobeJob =
                            lifecycleScope.launch(
                                Dispatchers.IO
                            ) {

                                var toggleState =
                                    true

                                while (isActive) {

                                    cameraManager
                                        .setTorchMode(
                                            mainCameraId!!,
                                            toggleState
                                        )

                                    toggleState =
                                        !toggleState

                                    delay(100)
                                }
                            }
                    }

                    3 -> {

                        strobeJob =
                            lifecycleScope.launch(
                                Dispatchers.IO
                            ) {

                                while (isActive) {

                                    for (i in 1..3) {

                                        cameraManager
                                            .setTorchMode(
                                                mainCameraId!!,
                                                true
                                            )

                                        delay(200)

                                        cameraManager
                                            .setTorchMode(
                                                mainCameraId!!,
                                                false
                                            )

                                        delay(200)
                                    }

                                    for (i in 1..3) {

                                        cameraManager
                                            .setTorchMode(
                                                mainCameraId!!,
                                                true
                                            )

                                        delay(600)

                                        cameraManager
                                            .setTorchMode(
                                                mainCameraId!!,
                                                false
                                            )

                                        delay(200)
                                    }

                                    for (i in 1..3) {

                                        cameraManager
                                            .setTorchMode(
                                                mainCameraId!!,
                                                true
                                            )

                                        delay(200)

                                        cameraManager
                                            .setTorchMode(
                                                mainCameraId!!,
                                                false
                                            )

                                        delay(200)
                                    }

                                    delay(1500)
                                }
                            }
                    }
                }
            }

        } catch (e: Exception) {

            printToTerminal(
                "> Camera operation failed."
            )
        }
    }

    // ========================================================================
    // HAPTIC
    // ========================================================================

    private fun triggerHapticFeedback(
        durationMs: Long = 200
    ) {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val vibratorManager =
                    getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as VibratorManager

                vibratorManager
                    .defaultVibrator
                    .vibrate(
                        VibrationEffect.createOneShot(
                            durationMs,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )

            } else {

                @Suppress("DEPRECATION")

                (
                    getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as Vibrator
                ).vibrate(
                    durationMs
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Haptic feedback unavailable",
                e
            )
        }
    }

    // ========================================================================
    // SENSOR
    // ========================================================================

    override fun onSensorChanged(
        event: SensorEvent?
    ) {

        when (
            event?.sensor?.type
        ) {

            Sensor.TYPE_PROXIMITY -> {

                if (
                    event.values[0] <
                    (
                        proximitySensor
                            ?.maximumRange
                            ?: 5f
                    )
                ) {

                    if (
                        voiceSessionManager.isActive() ||
                        textToSpeechEngine.isSpeaking
                    ) {

                        textToSpeechEngine.stop()

                        speechRecognizerManager.stop()

                        printToTerminal(
                            "> Proximity override: audio muted."
                        )
                    }
                }
            }

            Sensor.TYPE_LIGHT -> {

                ambientLightLux =
                    event.values[0]
            }

            Sensor.TYPE_ACCELEROMETER -> {

                val x =
                    event.values[0]

                val y =
                    event.values[1]

                val z =
                    event.values[2]

                if (!isShakeInitialized) {

                    accelLastX = x
                    accelLastY = y
                    accelLastZ = z

                    isShakeInitialized = true
                }

                val dX =
                    abs(
                        accelLastX - x
                    )

                val dY =
                    abs(
                        accelLastY - y
                    )

                val dZ =
                    abs(
                        accelLastZ - z
                    )

                if (
                    dX > SHAKE_ACCEL_THRESHOLD ||
                    dY > SHAKE_ACCEL_THRESHOLD ||
                    dZ > SHAKE_ACCEL_THRESHOLD
                ) {

                    if (
                        !voiceSessionManager.isActive() &&
                        PermissionHelper
                            .hasAudioPermission(this)
                    ) {

                        printToTerminal(
                            "> Shake detected. Starting voice mode."
                        )

                        startVoiceMode()
                    }
                }

                accelLastX = x
                accelLastY = y
                accelLastZ = z
            }
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }

    // ========================================================================
    // STARTUP DIAGNOSTICS
    // ========================================================================

    private fun runStartupDiagnosticSequence() {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            printToTerminal(
                "> Running JARVIS diagnostics..."
            )

            delay(300)

            printToTerminal(
                "> Loading DeepSeek communication bridge..."
            )

            delay(300)

            printToTerminal(
                "> Synchronizing accessibility controller..."
            )

            delay(300)

            printToTerminal(
                "> Linking acoustic subsystem..."
            )

            delay(300)

            withContext(
                Dispatchers.Main
            ) {

                currentSystemState =
                    SystemState.ONLINE

                updateProgrammaticHUD()

                triggerHapticFeedback(
                    150
                )

                printToTerminal(
                    "> DIAGNOSTIC COMPLETE."
                )

                speakCommandFeedback(
                    "J.A.R.V.I.S. initialized. DeepSeek core online. Awaiting voice protocols."
                )
            }
        }
    }

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    override fun onResume() {

        super.onResume()

        proximitySensor?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        lightSensor?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        accelerometerSensor?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        magneticSensor?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onPause() {

        super.onPause()

        sensorManager.unregisterListener(
            this
        )
    }

    override fun onDestroy() {

        printToTerminal(
            "> J.A.R.V.I.S. shutdown sequence."
        )

        currentSystemState =
            SystemState.OFFLINE

        try {
            unregisterReceiver(
                powerTelemetryReceiver
            )
        } catch (_: Exception) {
        }

        try {
            unregisterReceiver(
                backgroundWakeReceiver
            )
        } catch (_: Exception) {
        }

        try {
            connectivityManager
                .unregisterNetworkCallback(
                    networkCallback
                )
        } catch (_: Exception) {
        }

        operateHardwareFlashlight(
            0
        )

        try {
            voiceSessionManager.destroy()
        } catch (_: Exception) {
        }

        try {
            speechRecognizerManager.destroy()
        } catch (_: Exception) {
        }

        if (
            this::textToSpeechEngine
                .isInitialized
        ) {

            textToSpeechEngine.stop()

            textToSpeechEngine.shutdown()
        }

        if (
            this::viewModel
                .isInitialized
        ) {

            viewModel.setResponseListener(
                null
            )
        }

        mainThreadHandler
            .removeCallbacksAndMessages(
                null
            )

        super.onDestroy()
    }

    // ========================================================================
    // SQLITE MEMORY
    // ========================================================================

    inner class JarvisDatabaseHelper(
        context: Context
    ) : SQLiteOpenHelper(
        context,
        "JarvisTitanMemory.db",
        null,
        4
    ) {

        override fun onCreate(
            db: SQLiteDatabase
        ) {

            val query =
                """
                CREATE TABLE MemoryLog (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT,
                    query TEXT,
                    response TEXT,
                    intent_type TEXT
                )
                """.trimIndent()

            db.execSQL(
                query
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {

            db.execSQL(
                "DROP TABLE IF EXISTS MemoryLog"
            )

            onCreate(
                db
            )
        }

        fun logInteraction(
            query: String,
            response: String,
            intentType: String
        ) {

            try {

                val db =
                    writableDatabase

                val values =
                    ContentValues().apply {

                        put(
                            "timestamp",
                            SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss",
                                Locale.getDefault()
                            ).format(
                                Date()
                            )
                        )

                        put(
                            "query",
                            query
                        )

                        put(
                            "response",
                            response
                        )

                        put(
                            "intent_type",
                            intentType
                        )
                    }

                db.insert(
                    "MemoryLog",
                    null,
                    values
                )

                db.close()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "SQL error",
                    e
                )
            }
        }

        fun getHistoryCount(): Int {

            var count = 0

            try {

                val cursor =
                    readableDatabase.rawQuery(
                        "SELECT COUNT(*) FROM MemoryLog",
                        null
                    )

                if (
                    cursor.moveToFirst()
                ) {

                    count =
                        cursor.getInt(0)
                }

                cursor.close()

            } catch (_: Exception) {
            }

            return count
        }

        fun clearMemory() {

            try {

                val db =
                    writableDatabase

                db.execSQL(
                    "DELETE FROM MemoryLog"
                )

                db.close()

            } catch (_: Exception) {
            }
        }

        fun exportDatabaseToCSV(
            context: Context
        ): String {

            val exportDir =
                File(
                    context.getExternalFilesDir(
                        null
                    ),
                    "JarvisExports"
                )

            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val file =
                File(
                    exportDir,
                    "Jarvis_Memory_Dump_${System.currentTimeMillis()}.csv"
                )

            return try {

                file.createNewFile()

                val fw =
                    FileWriter(file)

                val db =
                    readableDatabase

                val cursor =
                    db.rawQuery(
                        "SELECT * FROM MemoryLog",
                        null
                    )

                fw.append(
                    "ID,Timestamp,Query,Response,IntentType\n"
                )

                if (
                    cursor.moveToFirst()
                ) {

                    do {

                        fw.append(
                            "${cursor.getInt(0)}," +
                            "\"${cursor.getString(1)}\"," +
                            "\"${cursor.getString(2)}\"," +
                            "\"${cursor.getString(3)}\"," +
                            "\"${cursor.getString(4)}\"\n"
                        )

                    } while (
                        cursor.moveToNext()
                    )
                }

                cursor.close()

                fw.close()

                db.close()

                file.absolutePath

            } catch (e: Exception) {

                "Export Failed"
            }
        }
    }

    // ========================================================================
    // SCIENTIFIC MATH
    // ========================================================================

    inner class AdvancedScientificParser {

        fun evaluate(
            expression: String
        ): Double {

            return object : Any() {

                var pos = -1

                var ch = 0

                fun nextChar() {

                    ch =
                        if (
                            ++pos <
                            expression.length
                        ) {

                            expression[pos].code

                        } else {

                            -1
                        }
                }

                fun eat(
                    charToEat: Int
                ): Boolean {

                    while (
                        ch ==
                        ' '.code
                    ) {

                        nextChar()
                    }

                    if (
                        ch ==
                        charToEat
                    ) {

                        nextChar()

                        return true
                    }

                    return false
                }

                fun parse(): Double {

                    nextChar()

                    val x =
                        parseExpression()

                    if (
                        pos <
                        expression.length
                    ) {

                        throw RuntimeException(
                            "Syntax Error: $ch"
                        )
                    }

                    return x
                }

                fun parseExpression(): Double {

                    var x =
                        parseTerm()

                    while (true) {

                        when {

                            eat('+'.code) ->
                                x += parseTerm()

                            eat('-'.code) ->
                                x -= parseTerm()

                            else ->
                                return x
                        }
                    }
                }

                fun parseTerm(): Double {

                    var x =
                        parseFactor()

                    while (true) {

                        when {

                            eat('*'.code) ->
                                x *= parseFactor()

                            eat('/'.code) ->
                                x /= parseFactor()

                            else ->
                                return x
                        }
                    }
                }

                fun parseFactor(): Double {

                    if (
                        eat('+'.code)
                    ) {

                        return parseFactor()
                    }

                    if (
                        eat('-'.code)
                    ) {

                        return -parseFactor()
                    }

                    var x: Double

                    val startPos =
                        pos

                    if (
                        eat('('.code)
                    ) {

                        x =
                            parseExpression()

                        eat(')'.code)

                    } else if (
                        (
                            ch >= '0'.code &&
                            ch <= '9'.code
                        ) ||
                        ch == '.'.code
                    ) {

                        while (
                            (
                                ch >= '0'.code &&
                                ch <= '9'.code
                            ) ||
                            ch == '.'.code
                        ) {

                            nextChar()
                        }

                        x =
                            expression
                                .substring(
                                    startPos,
                                    pos
                                )
                                .toDouble()

                    } else if (
                        ch >= 'a'.code &&
                        ch <= 'z'.code
                    ) {

                        while (
                            ch >= 'a'.code &&
                            ch <= 'z'.code
                        ) {

                            nextChar()
                        }

                        val func =
                            expression.substring(
                                startPos,
                                pos
                            )

                        if (
                            func == "pi"
                        ) {

                            return PI
                        }

                        if (
                            func == "e"
                        ) {

                            return E
                        }

                        x =
                            parseFactor()

                        x =
                            when (func) {

                                "sqrt" ->
                                    sqrt(x)

                                "sin" ->
                                    sin(
                                        Math.toRadians(x)
                                    )

                                "cos" ->
                                    cos(
                                        Math.toRadians(x)
                                    )

                                "tan" ->
                                    tan(
                                        Math.toRadians(x)
                                    )

                                "asin" ->
                                    Math.toDegrees(
                                        asin(x)
                                    )

                                "acos" ->
                                    Math.toDegrees(
                                        acos(x)
                                    )

                                "atan" ->
                                    Math.toDegrees(
                                        atan(x)
                                    )

                                "sinh" ->
                                    sinh(x)

                                "cosh" ->
                                    cosh(x)

                                "tanh" ->
                                    tanh(x)

                                "log" ->
                                    log10(x)

                                "ln" ->
                                    ln(x)

                                else ->
                                    throw RuntimeException(
                                        "Unknown Math Function: $func"
                                    )
                            }

                    } else {

                        throw RuntimeException(
                            "Unexpected Math Token"
                        )
                    }

                    if (
                        eat('^'.code)
                    ) {

                        x =
                            x.pow(
                                parseFactor()
                            )
                    }

                    return x
                }

            }.parse()
        }
    }

    // ========================================================================
    // MATRIX DIGITAL RAIN
    // ========================================================================

    inner class MatrixDigitalRainView(
        context: Context
    ) : View(context) {

        private val rnd =
            Random()

        private val p =
            Paint().apply {

                typeface =
                    Typeface.MONOSPACE
            }

        private val drops =
            Array(100) {
                DigitalDrop()
            }

        private var hexGlowColor =
            HUD_COLOR_CYAN

        inner class DigitalDrop {

            var x =
                rnd.nextFloat() * 1500f

            var y =
                rnd.nextFloat() * -3000f

            var speed =
                rnd.nextFloat() * 15f + 8f

            var chars =
                CharArray(
                    rnd.nextInt(25) + 8
                ) {

                    (
                        rnd.nextInt(94) + 33
                    ).toChar()
                }

            var textSize =
                rnd.nextFloat() * 22f + 14f
        }

        fun updateHologramColor(
            state: OrbState
        ) {

            hexGlowColor =
                when (state) {

                    OrbState.ERROR ->
                        HUD_COLOR_RED

                    OrbState.LISTENING ->
                        HUD_COLOR_GREEN

                    OrbState.SPEAKING ->
                        HUD_COLOR_ORANGE

                    else ->
                        HUD_COLOR_CYAN
                }
        }

        override fun onDraw(
            c: Canvas
        ) {

            super.onDraw(c)

            val w =
                width.toFloat()

            val h =
                height.toFloat()

            for (drop in drops) {

                p.textSize =
                    drop.textSize

                for (
                    i in drop.chars.indices
                ) {

                    if (
                        rnd.nextFloat() >
                        0.95f
                    ) {

                        drop.chars[i] =
                            (
                                rnd.nextInt(94) + 33
                            ).toChar()
                    }

                    val alpha =
                        255 -
                        (
                            i *
                            (
                                255 /
                                drop.chars.size
                            )
                        )

                    p.color =
                        Color.parseColor(
                            hexGlowColor
                        )

                    p.alpha =
                        alpha.coerceIn(
                            0,
                            255
                        )

                    c.drawText(
                        drop.chars[i].toString(),
                        drop.x,
                        drop.y -
                            (
                                i *
                                drop.textSize
                            ),
                        p
                    )
                }

                drop.y +=
                    drop.speed

                if (
                    drop.y -
                    (
                        drop.chars.size *
                        drop.textSize
                    ) > h
                ) {

                    drop.y =
                        rnd.nextFloat() *
                        -1000f

                    drop.x =
                        rnd.nextFloat() *
                        w

                    drop.speed =
                        rnd.nextFloat() *
                        15f + 8f
                }
            }

            invalidate()
        }
    }

    // ========================================================================
    // SYSTEM DIAGNOSTICS
    // ========================================================================

    inner class DeepSystemDiagnostics(
        private val context: Context
    ) {

        fun getRamUsagePercentage(): Int {

            return try {

                val memoryInfo =
                    ActivityManager.MemoryInfo()

                val manager =
                    context.getSystemService(
                        Context.ACTIVITY_SERVICE
                    ) as ActivityManager

                manager.getMemoryInfo(
                    memoryInfo
                )

                (
                    (
                        memoryInfo.totalMem -
                        memoryInfo.availMem
                    ).toFloat() /
                    memoryInfo.totalMem *
                    100
                ).toInt()

            } catch (_: Exception) {

                0
            }
        }

        fun estimateCpuTemp(): String {

            val estimated =
                currentBatteryTemp + 5.0f

            return if (
                estimated > 0
            ) {

                estimated.toString()

            } else {

                "N/A"
            }
        }
    }
}