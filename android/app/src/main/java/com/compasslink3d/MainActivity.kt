package com.compasslink3d

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var sensors: MotionSensors
    private val motionHandler = Handler(Looper.getMainLooper())
    private val motionTick = Runnable { tickMotion() }
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var toggle: Button
    private lateinit var motionScreen: LinearLayout
    private var controllerPad: ControllerPad? = null
    private var controllerStatus: TextView? = null
    private var controllerMessage = "Connecting to the PC through ADB…"
    private var controllerRetry: Button? = null
    private var controllerOptions: View? = null
    private var playStatus: TextView? = null
    private var playSurface: View? = null
    private var playProfile: ControllerProfile? = null
    private var playOrientation = ProfileOrientation.LANDSCAPE
    private var playing = false
    private var destination = Destination.CONNECT
    private var editorOrigin: String? = null
    private var editorSessionProfile: ControllerProfile? = null
    private var library: ControllerProfiles? = null
    private var librarySelection: String? = null
    private var libraryScroll = 0
    private var connectMode: TextView? = null
    private var connectionLabel: TextView? = null
    private lateinit var sensorGauge: OrientationGauge
    private lateinit var sensorSummary: TextView
    private var outputMode = OutputMode.KEYBOARD
    private var lastExclusiveOutput = OutputMode.KEYBOARD
    private var editor: ControllerEditor? = null
    private var pendingExport: String? = null
    private lateinit var controllerWindow: ControllerWindow
    private val supportedKeys by lazy {
        val list = JSONArray(assets.open("controller-keys.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        List(list.length()) { list.getString(it) }
    }
    private val profileCodec by lazy { ProfileCodec(supportedKeys.toSet()) }
    private val profiles by lazy { ControllerProfileStore(this, profileCodec) }
    private val grantStore by lazy { ControllerGrantStore(noBackupFilesDir) }
    private var controllerGrant: ControllerGrant? = null
    private var processors = emptyList<MotionProcessor>()
    private val motion get() = processors.firstOrNull()
    private val gyro get() = processors.firstOrNull { it.settings.source == MotionSource.GYROSCOPE }
    private var motionPanel: MotionPanel? = null
    private var motionActive = false
    private var motionStartsAt = 0L
    private var legacyTilt = false
    private var legacyAt = 0L
    private var streamedAt = 0L
    private var motionRotation: Int? = null
    private var telemeteredAt = 0L
    private var controllerReady = false
    private var selectOnAcknowledgement = false
    private var transport: UsbSensorTransport? = null
    private var transportGrant: ControllerGrant? = null
    private var streaming = false
    private var foreground = false
    private var focused = false
    private var samples = 0L
    private var lastUiTimestamp = 0L
    private var lastPanelTimestamp = 0L
    private var connectionMode = "unknown"

    private fun launchGrant(intent: Intent) =
        ControllerGrant.parse(intent.getStringExtra("connection_mode"), intent.getStringExtra("controller_token"))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val grant = launchGrant(intent) ?: return
        controllerGrant = grant
        connectionMode = grant.mode
        connectMode?.text = if (connectionMode == "usb") "USB debugging" else "Wireless debugging"
        syncTransport()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controllerWindow = ControllerWindow(window)
        outputMode = OutputMode.entries.firstOrNull { it.wire == savedInstanceState?.getString("outputMode") }
            ?: OutputMode.KEYBOARD
        lastExclusiveOutput = exclusiveModes.firstOrNull { it.wire == savedInstanceState?.getString("exclusiveOutput") }
            ?: outputMode.takeUnless { it == OutputMode.HYBRID } ?: OutputMode.KEYBOARD
        pendingExport = savedInstanceState?.getString("profileExport")
        controllerGrant = launchGrant(intent) ?: grantStore.saved
        connectionMode = controllerGrant?.mode ?: intent.getStringExtra("connection_mode")
            ?.takeIf { it == "usb" || it == "wireless" } ?: "unknown"
        sensors = MotionSensors(getSystemService(SENSOR_SERVICE) as SensorManager) { sample, time ->
            val now = SystemClock.elapsedRealtimeNanos()
            val rotation = controllerPad?.display?.rotation
            if (rotation != null && motionRotation == rotation && motionActive && time >= motionStartsAt && freshMotion(time, now))
                gyro?.gyro(sample, time, rotation)
        }
        val available = orientationSensor(sensors.available(MotionSensor.GAME), sensors.available(MotionSensor.ROTATION), false)
        status = TextView(this).apply {
            textSize = 18f
            setText(R.string.sensor_ready)
        }
        values = TextView(this).apply {
            textSize = 16f
            text = getString(R.string.sensor_name, sensors.label(available) ?: getString(R.string.sensor_unavailable))
        }
        toggle = builderButton("", highlight = true, icon = R.drawable.ic_controller_signal) {
            if (streaming) stopStream() else startStream()
        }.apply {
            setText(R.string.start_streaming)
            isEnabled = available != null
        }
        sensorGauge = OrientationGauge(this)
        sensorSummary = hint(sensors.label(available) ?: "No orientation sensor")
        motionScreen = builderRoot().apply {
            addView(heading("Sensors"))
            addView(sensorGauge, LinearLayout.LayoutParams(-1, px(128)))
            addView(controlPanel().apply {
                addView(sensorSummary)
                addView(status)
                addView(toggle)
                addView(builderButton("Quaternion details") {
                    values.visibility = if (values.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                })
                values.visibility = View.GONE
                addView(values)
            })
        }
        librarySelection = savedInstanceState?.getString("librarySelection")
        libraryScroll = savedInstanceState?.getInt("libraryScroll") ?: 0
        editorOrigin = savedInstanceState?.getString("editorOrigin")
        playOrientation = ProfileOrientation.entries.firstOrNull { it.name == savedInstanceState?.getString("playOrientation") }
            ?: ProfileOrientation.LANDSCAPE
        streaming = savedInstanceState?.getBoolean("streaming") == true && available != null
        if (streaming) toggle.setText(R.string.stop_streaming)
        val draft = savedInstanceState?.getString("draft")?.let {
            runCatching { profileCodec.decode(org.json.JSONObject(it)) }.getOrNull()
        }
        val sessionProfile = savedInstanceState?.getString("sessionProfile")?.let {
            runCatching { profileCodec.decode(org.json.JSONObject(it)) }.getOrNull()
        }
        editorSessionProfile = sessionProfile?.takeIf { it.id == editorOrigin }
        val retainedProfile = savedInstanceState?.getString("playProfile")?.let { id ->
            sessionProfile?.takeIf { it.builtIn && it.id == id } ?: profiles.find(id)
        }
        when {
            draft != null -> {
                showEditor(draft, profiles.find(draft.id) == null)
                editor?.restore(savedInstanceState.getString("selectedControl"), savedInstanceState.getBoolean("canvasOnly"),
                    savedInstanceState.getBoolean("editorClean"))
            }
            retainedProfile != null -> {
                val orientation = ProfileOrientation.entries.find { it.name == savedInstanceState.getString("playOrientation") }
                showControllerScreen(retainedProfile, orientation ?: retainedProfile.orientation, false)
            }
            savedInstanceState?.getString("destination") == Destination.CONTROLLERS.name -> showProfiles()
            savedInstanceState?.getString("destination") == Destination.SENSORS.name -> showMotionScreen()
            else -> showConnect()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!navigateBack()) finishAfterTransition()
            }
        }
        if (available == null) status.setText(R.string.sensor_missing)
    }

    private fun playController(profile: ControllerProfile, orientation: ProfileOrientation = profile.orientation, switching: Boolean = false) {
        libraryScroll = library?.scrollY ?: libraryScroll
        library = null
        editorOrigin = null
        editorSessionProfile = null
        librarySelection = profile.id
        outputMode = preferredOutput(profile)
        if (outputMode != OutputMode.HYBRID) lastExclusiveOutput = outputMode
        showControllerScreen(profile, orientation, switching = switching)
    }

    private fun preferredOutput(profile: ControllerProfile): OutputMode {
        val bindings = profile.controls.filter { it.style.visible }.flatMap { it.bindings.values }
        val preferred = profile.motion?.takeIf { it.enabled }?.mapping?.mode
        return when {
            profile.hybrid?.hybrid == true || profile.requiresHybridTouchLook -> OutputMode.HYBRID
            preferred != null -> preferred
            bindings.any { it.supports(lastExclusiveOutput) } -> lastExclusiveOutput
            else -> exclusiveModes.firstOrNull { mode -> bindings.any { it.supports(mode) } } ?: OutputMode.KEYBOARD
        }
    }

    private fun showControllerScreen(profile: ControllerProfile, orientation: ProfileOrientation, startPlaying: Boolean = true,
        switching: Boolean = false) {
        if (!switching && !reportErrors { profiles.select(profile.id) }) return
        clearControllerScreen()
        playProfile = profile
        selectOnAcknowledgement = switching
        playOrientation = orientation
        legacyTilt = profile.controls.any { control ->
            control.type == ControlType.TILT && control.style.visible && control.bindings.values.any { it.supports(outputMode) }
        }
        processors = profile.motionsFor(outputMode).mapIndexed { index, settings ->
            MotionProcessor(settings, profile.motionId(index), { event -> transport?.sendInput(event) }, { id -> transport?.discardPointer(id) })
        }
        val lock = playLock(profile)
        val layoutOrientation = lock ?: resources.configuration.layoutOrientation
        val pad = ControllerPad(this, profile.layoutFor(layoutOrientation), outputMode, { id ->
            motionHandler.post {
                playProfile?.takeIf { playing && it.id == profile.id && controllerReady }?.let { quickSwitch(it, id) }
            }
        }) { event ->
            val source = profile.hybrid?.gyroControl
            if (source != null && event is ControllerInput.Digital && event.control == source.control && event.slot == source.slot)
                gyro?.activate(event.down, SystemClock.elapsedRealtimeNanos())
            if (event == ControllerInput.Neutral) stopMotion()
            transport?.sendInput(event)
            if (event == ControllerInput.Neutral) syncSensorListener()
        }.apply { onPointerReleased = { id -> transport?.discardPointer(id) } }
        controllerPad = pad
        val connectionStatus = hint("").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        controllerStatus = connectionStatus
        val content = builderRoot().apply {
            addView(controllerHeader(heading(profile.name), "Back to profiles", ::showProfiles))
            addView(actionRow(
                builderButton("Resume Play", true, R.drawable.ic_controller_play) { setPlaying(true) },
                builderButton("Edit", icon = R.drawable.ic_controller_edit) { editFromPlay(playProfile ?: profile) },
                builderButton("End session", icon = R.drawable.ic_controller_stop) { showProfiles() }
            ))
            addView(controlPanel().apply {
                addView(connectionStatus)
                val retry = builderButton("Retry", icon = R.drawable.ic_controller_refresh) { replaceTransport() }
                controllerRetry = retry
                addView(retry)
                retry.visibility = View.GONE
                if (legacyTilt && !sensors.available(MotionSensor.GAME) && !sensors.available(MotionSensor.ROTATION))
                    addView(hint("Tilt requires a rotation sensor."))
                if (profile.motion?.enabled == true && motion == null)
                    addView(hint("Motion uses ${profile.motion.mapping.mode.title}."))
            })
            val outputButton = builderButton(outputMode.title, icon = R.drawable.ic_controller_gamepad) {
                choose("Controller output", exclusiveModes.map { it.title }) { choice ->
                    val chosen = exclusiveModes[choice]
                    if (chosen != outputMode) {
                        outputMode = chosen
                        lastExclusiveOutput = chosen
                        showControllerScreen(playProfile ?: profile, playOrientation, startPlaying = false)
                    }
                }
            }
            outputButton.isEnabled = outputMode != OutputMode.HYBRID
            if (profile.legacyOrientationChoice) {
                lateinit var orientationButton: Button
                orientationButton = builderButton(orientation.title, icon = R.drawable.ic_controller_phone) {
                    choose("Play orientation", ProfileOrientation.entries.map { it.title }) { choice ->
                        val chosen = ProfileOrientation.entries[choice]
                        if (chosen != playOrientation) {
                            playOrientation = chosen
                            orientationButton.text = chosen.title
                            pad.setReady(false)
                            stopMotion()
                            sessionProfileChanged()
                            pad.render((playProfile ?: profile).layoutFor(chosen).controls)
                            orientController(chosen)
                        }
                    }
                }
                addView(actionRow(orientationButton, outputButton))
            } else addView(actionRow(outputButton))
            val gyroSettings = profile.motionsFor(outputMode).find { it.source == MotionSource.GYROSCOPE }
            if (gyroSettings != null) {
                addView(builderButton("Gyro sensitivity") {
                    val current = playProfile ?: return@builderButton
                    val form = ControllerForm(this@MainActivity)
                    val level = form.number("Gyro sensitivity (0.1–8)", gyroSettings.sensitivity)
                    form.show(if (current.builtIn) "Adjust for this session" else "Adjust saved profile") {
                        val changed = gyroSettings.copy(sensitivity = level.readNumber(.1, 8.0))
                        val updated = if (current.motion?.source == MotionSource.GYROSCOPE) current.copy(motion = changed)
                            else current.copy(hybrid = current.hybrid?.copy(secondaryMotion = changed))
                        if (!updated.builtIn) profiles.save(updated)
                        showControllerScreen(updated, playOrientation, startPlaying = false)
                    }
                })
            }
            if (mouseControls(profile).isNotEmpty()) {
                addView(actionRow(builderButton(getString(R.string.mouse_sensitivity), icon = R.drawable.ic_controller_settings) {
                    adjustMouseSensitivity()
                }))
            }

        }
        val options = ScrollView(this).apply {
            setBackgroundColor(CANVAS_BACKGROUND)
            controllerWindow.insetContent(this, 14)
            addView(content)
        }
        controllerOptions = options
        val view = FrameLayout(this).apply {
            setBackgroundColor(CANVAS_BACKGROUND)
            addView(LinearLayout(context).apply {
                this.orientation = LinearLayout.VERTICAL
                // Only the controls need clearance from the camera cutout.
                controllerWindow.insetContent(this, 0, protectSystemBars = false)
                addView(FrameLayout(context).apply {
                    controllerWindow.insetContent(this, 0, protectBottom = false)
                    addView(builderButton("Pause", icon = R.drawable.ic_controller_settings) { setPlaying(false) },
                        FrameLayout.LayoutParams(-2, -2, Gravity.END))
                }, LinearLayout.LayoutParams(-1, -2))
                motion?.let { processor ->
                    val feedback = profile.hybrid?.haptics ?: TouchHaptics.OFF
                    val panel = MotionPanel(context, processor.settings, gyro?.settings, profile.hybrid?.gyroControl == null, {
                        if (motionActive) {
                            val now = SystemClock.elapsedRealtimeNanos()
                            processors.forEach { it.recenter(now) }
                            playSurface?.feedback(feedback)
                        }
                    }, { pressed ->
                        if (motionActive) {
                            val previous = gyro?.gyroEnabled
                            gyro?.activate(pressed, SystemClock.elapsedRealtimeNanos())
                            if (pressed && previous != gyro?.gyroEnabled) playSurface?.feedback(feedback)
                        }
                    })
                    motionPanel = panel
                    addView(panel.view)
                }
                playStatus = hint("").apply {
                    gravity = Gravity.CENTER
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    setPadding(px(10), px(8), px(10), px(8))
                    addView(this)
                }
                addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
                playSurface = this
            }, FrameLayout.LayoutParams(-1, -1))
            addView(options, FrameLayout.LayoutParams(-1, -1))
        }
        pad.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInputReadiness() }
        setPlaying(startPlaying)
        mountController(view, lock)
        syncTransport()
    }

    private fun playLock(profile: ControllerProfile) =
        if (profile.legacyOrientationChoice) playOrientation else profile.lockedOrientation

    private fun mouseControls(profile: ControllerProfile) = profile.controls.filter {
        it.style.visible && it.bindings[Slot.POINTER]?.supports(outputMode) == true
    }

    private fun adjustMouseSensitivity() {
        val profile = playProfile ?: return
        val controls = mouseControls(profile)
        fun adjust(control: LayoutControl) {
            val form = ControllerForm(this)
            val sensitivity = form.mouseSensitivity(control.settings.sensitivity)
            form.show(control.displayName + if (profile.builtIn) " · This session" else " · Saved profile") {
                val updated = profile.copy(controls = profile.controls.map {
                    if (it.id == control.id) it.copy(settings = it.settings.copy(sensitivity = sensitivity())) else it
                })
                if (updated != profile) {
                    if (!updated.builtIn) profiles.save(updated)
                    playProfile = updated
                    val orientation = playLock(updated) ?: resources.configuration.layoutOrientation
                    controllerPad?.render(updated.layoutFor(orientation).controls)
                }
            }
        }
        if (controls.size == 1) adjust(controls.single())
        else if (controls.isNotEmpty()) choose(getString(R.string.mouse_sensitivity), controls.map { it.displayName }) { adjust(controls[it]) }
    }

    private fun sessionProfileChanged() {
        if (playProfile == null) return
        controllerPad?.releaseAll()
        controllerPad?.setReady(false)
        stopMotion()
        syncTransport()
    }

    private fun quickSwitch(profile: ControllerProfile, control: String) {
        val target = profile.hybrid?.quickSwitch?.get(control)?.let(profiles::find)
        if (target == null) {
            setPlaying(false)
            showError("The switch target is unavailable. Edit the profile to choose another.")
            return
        }
        playController(target, switching = true)
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        controllerPad?.releaseAll()
        playSurface?.visibility = if (playing) View.VISIBLE else View.GONE
        controllerOptions?.visibility = if (playing) View.GONE else View.VISIBLE
        controllerWindow.immersive(playing)
        if (playing) playSurface?.requestApplyInsets() else controllerOptions?.requestApplyInsets()
        updateInputReadiness()
    }

    private fun editFromPlay(profile: ControllerProfile) {
        val open = { draft: ControllerProfile, unsaved: Boolean ->
            editorOrigin = profile.id
            editorSessionProfile = profile
            showEditor(draft, unsaved)
        }
        if (profile.builtIn) editBuiltInController(this, profile.layoutFor(playLock(profile)
            ?: resources.configuration.layoutOrientation), open) else open(profile, false)
    }

    private fun browse(keepPausedSession: Boolean = false) {
        library?.let { librarySelection = playProfile?.id ?: it.selectedId; libraryScroll = it.scrollY }
        library = null
        connectionLabel = null
        connectMode = null
        clearControllerScreen()
        if (!keepPausedSession) {
            editorOrigin = null
            editorSessionProfile = null
            syncTransport()
        }
        controllerWindow.immersive(false)
        orientController(null)
    }

    private fun mountDestination(next: Destination, body: View) {
        destination = next
        val shell = navigationShell(next, body) { target ->
            when (target) {
                Destination.CONNECT -> showConnect()
                Destination.CONTROLLERS -> showProfiles()
                Destination.SENSORS -> showMotionScreen()
            }
        }
        controllerWindow.insetContent(shell, 12)
        setContentView(shell)
        shell.requestApplyInsets()
    }

    private fun showConnect() {
        browse()
        editorOrigin = null
        val diagram = LinkDiagram(this)
        val label = hint("Ready to check the PC link")
        connectionLabel = label
        val linkName = when (connectionMode) {
            "usb" -> "USB debugging"
            "wireless" -> "Wireless debugging"
            else -> "ADB tunnel"
        }
        val content = builderRoot().apply {
            addView(heading("CompassLink").apply { textSize = 32f })
            addView(hint("PC connection"))
            addView(controlPanel().apply {
                addView(diagram, LinearLayout.LayoutParams(-1, px(108)))
                addView(heading(linkName).also { connectMode = it })
                addView(label)
                lateinit var check: Button
                check = builderButton("Check link", true, R.drawable.ic_controller_signal) {
                    check.isEnabled = false
                    diagram.verified = false
                    label.text = "Checking the PC receiver…"
                    kotlin.concurrent.thread(isDaemon = true) {
                        val connected = runCatching {
                            java.net.Socket().use { socket ->
                                socket.connect(java.net.InetSocketAddress("127.0.0.1", 8765), 1500)
                                socket.soTimeout = 1500
                                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                                writer.write("""{"type":"link.check","version":1}""")
                                writer.newLine()
                                writer.flush()
                                val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readControllerReply()
                                val reply = line?.let { org.json.JSONObject(it) }
                                reply?.optString("type") == "link.ready" && reply.optInt("version") == 1
                            }
                        }.getOrDefault(false)
                        runOnUiThread {
                            if (connectionLabel === label) {
                                check.isEnabled = true
                                diagram.verified = connected
                                label.text = if (connected) "PC receiver reachable" else "Waiting for PC · Check setup"
                            }
                        }
                    }
                }
                addView(actionRow(check, builderButton("Setup", icon = R.drawable.ic_controller_settings) {
                    choose("Connection setup", listOf("USB debugging", "Wireless debugging")) { index ->
                        val command = if (index == 0) "npm run connect:prebuilt:usb" else "npm run connect:prebuilt:wireless -- -Pair"
                        information(if (index == 0) "Connect by USB" else "Connect by Wi-Fi",
                            "Enable debugging on your phone. On the PC, run npm start and then run $command in a second terminal.")
                    }
                }))
            })
            addView(heading("Last controller").apply { setPadding(0, px(18), 0, px(6)) })
            val recent = profiles.active
            addView(controlPanel().apply {
                addView(LayoutPreview(this@MainActivity).apply { profile = recent },
                    LinearLayout.LayoutParams(-1, px(76)))
                addView(heading(recent.name))
                addView(actionRow(builderButton("Play controller", true, R.drawable.ic_controller_play) { playController(recent) }))
            })
        }
        mountDestination(Destination.CONNECT, ScrollView(this).apply { addView(content) })
    }

    private fun showProfiles() {
        browse()
        editorOrigin = null
        val screen = ControllerProfiles(this, profiles, ::playController,
            ::showEditor, ::importProfile, ::exportProfile, librarySelection, libraryScroll)
        library = screen
        mountDestination(Destination.CONTROLLERS, screen.view)
    }

    private fun showEditor(profile: ControllerProfile, unsaved: Boolean) {
        browse(keepPausedSession = editorOrigin != null)
        val screen = ControllerEditor(this, profile, supportedKeys, profiles::save,
            { playController(profiles.find(it.id) ?: it) }, {
                val original = editorOrigin?.let(profiles::find)
                val previous = editorSessionProfile
                val origin = editorSessionProfile?.takeIf { it.builtIn } ?: original
                editorOrigin = null
                editorSessionProfile = null
                if (origin == null) {
                    if (unsaved && profiles.find(profile.id) != null) librarySelection = profile.id
                    showProfiles()
                } else {
                    if (previous != origin && (origin.hybrid?.hybrid == true || origin.requiresHybridTouchLook ||
                        outputMode == OutputMode.HYBRID || previous?.motion != origin.motion ||
                        origin.controls.none { control -> control.style.visible && control.bindings.values.any { it.supports(outputMode) } })) {
                        outputMode = preferredOutput(origin)
                    }
                    showControllerScreen(origin, playOrientation, false)
                }
            }, ::orientController, controllerWindow, unsaved,
            { profiles.profiles }, profiles.find(profile.id))
        editor = screen
        mountController(screen.view, profile.lockedOrientation)
        syncTransport()
    }

    private fun mountController(view: View, orientation: ProfileOrientation? = null) {
        setContentView(view)
        view.requestApplyInsets()
        controllerWindow.immersive(playing || editor?.canvasOnly == true)
        orientController(orientation)
    }

    private fun orientController(orientation: ProfileOrientation?) {
        requestedOrientation = when (orientation) {
            ProfileOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ProfileOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    private fun clearControllerScreen() {
        controllerPad?.releaseAll()
        controllerPad?.setReady(false)
        stopMotion()
        controllerReady = false
        controllerPad = null
        controllerStatus = null
        controllerRetry = null
        controllerOptions = null
        playStatus = null
        playSurface = null
        processors = emptyList()
        motionPanel = null
        legacyTilt = false
        editor = null
        playProfile = null
        selectOnAcknowledgement = false
        setPlaying(false)
    }

    private fun showMotionScreen() {
        browse()
        (motionScreen.parent as? android.view.ViewGroup)?.removeView(motionScreen)
        mountDestination(Destination.SENSORS, ScrollView(this).apply { addView(motionScreen) })
    }

    private fun importProfile() {
        openDocument(IMPORT_PROFILE, Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        })
    }

    private fun exportProfile(profile: ControllerProfile) {
        pendingExport = profile.id
        openDocument(EXPORT_PROFILE, Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, profile.name.replace(Regex("[^a-zA-Z0-9 _.-]"), "_") + ".json")
        })
    }

    @Suppress("DEPRECATION")
    private fun openDocument(request: Int, intent: Intent) {
        try {
            startActivityForResult(intent, request)
        } catch (_: ActivityNotFoundException) {
            pendingExport = null
            showError("No Android document picker is available.")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val export = pendingExport
        if (requestCode == EXPORT_PROFILE) pendingExport = null
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        reportErrors {
            when (requestCode) {
                IMPORT_PROFILE -> {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBounded(MAX_PROFILE_BYTES) }
                        ?: throw java.io.IOException("Could not open the selected profile")
                    val imported = profileCodec.decode(bytes)
                    profiles.importProfile(imported)
                    showProfiles()
                }
                EXPORT_PROFILE -> {
                    val profile = export?.let(profiles::find) ?: throw IllegalStateException("The saved profile could not be found")
                    val target = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("The file could not be opened")
                    target.use { it.write(profileCodec.encode(profile).toString(2).toByteArray(Charsets.UTF_8)) }
                    android.widget.Toast.makeText(this, "Controller profile exported", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("outputMode", outputMode.wire)
        outState.putString("exclusiveOutput", lastExclusiveOutput.wire)
        outState.putString("profileExport", pendingExport)
        outState.putBoolean("streaming", streaming)
        outState.putString("playProfile", playProfile?.id)
        (editorSessionProfile ?: playProfile?.takeIf { it.builtIn })?.let {
            outState.putString("sessionProfile", profileCodec.encode(it).toString())
        }
        outState.putString("playOrientation", playOrientation.name)
        outState.putString("destination", destination.name)
        outState.putString("editorOrigin", editorOrigin)
        outState.putString("librarySelection", library?.selectedId ?: librarySelection)
        outState.putInt("libraryScroll", library?.scrollY ?: libraryScroll)
        editor?.let {
            outState.putString("draft", profileCodec.encode(it.draft).toString())
            outState.putString("selectedControl", it.selection)
            outState.putBoolean("canvasOnly", it.canvasOnly)
            outState.putBoolean("editorClean", it.isClean)
        }
    }

    private fun startStream() {
        if (streaming) return
        streaming = true
        if (!syncSensorListener()) {
            streaming = false
            status.setText(R.string.sensor_registration_failed)
            return
        }
        samples = 0
        lastUiTimestamp = 0
        streamedAt = 0
        toggle.setText(R.string.stop_streaming)
        syncTransport()
    }

    private fun stopStream() {
        streaming = false
        transport?.clearSamples()
        status.setText(R.string.streaming_stopped)
        sensorSummary.setText(R.string.sensor_ready)
        sensorGauge.pose(null)
        values.text = status.text
        toggle.setText(R.string.start_streaming)
        syncTransport()
    }

    private fun replaceTransport() = syncTransport(force = true)

    private fun syncTransport(force: Boolean = false) {
        val profile = playProfile ?: editorSessionProfile ?: editorOrigin?.let(profiles::find)
        val grant = controllerGrant
        val descriptor = profile?.takeIf { grant != null }?.hybridDescriptor(outputMode, playOrientation)
        if (force || transportGrant != grant || (!streaming && descriptor == null)) closeTransport()
        if (profile != null && descriptor == null) {
            displayConnectionStatus(false, "Run the USB or wireless connection command to authorize this phone.")
        }
        if (!streaming && descriptor == null) {
            syncSensorListener()
            updateKeepAwake()
            return
        }
        transport?.let { existing ->
            val changed = existing.switchProfile(descriptor)
            controllerReady = descriptor != null && existing.profileReady
            if (descriptor != null) {
                val message = when {
                    controllerReady -> "Connected · Profile synchronized"
                    changed -> "Synchronizing the controller profile…"
                    else -> controllerMessage
                }
                displayConnectionStatus(controllerReady, message)
            }
            updateInputReadiness()
            updateKeepAwake()
            return
        }
        if (descriptor != null) displayConnectionStatus(false, "Connecting to the PC through ADB…")
        // Ignore callbacks from sessions that were closed or superseded.
        lateinit var session: UsbSensorTransport
        session = UsbSensorTransport(grant?.token, descriptor, { message ->
            runOnUiThread { if (transport === session && streaming) status.text = message }
        }, { ready, message ->
            runOnUiThread {
                if (transport === session && ready == session.profileReady) {
                    val wasReady = controllerReady
                    controllerReady = ready
                    if (wasReady && !ready) stopMotion()
                    updateInputReadiness()
                    displayConnectionStatus(ready, message)
                    if (ready && selectOnAcknowledgement) {
                        selectOnAcknowledgement = false
                        playProfile?.let {
                            reportErrors { profiles.select(it.id) }
                            Toast.makeText(this, it.name, Toast.LENGTH_SHORT).show()
                        }
                    }
                    updateKeepAwake()
                }
            }
        }, {
            runOnUiThread {
                if (transport === session && grant != null) grantStore.remember(grant)
            }
        }, {
            runOnUiThread {
                if (transport === session && grant != null) {
                    grantStore.forget(grant)
                    controllerGrant = grantStore.saved
                    connectionMode = controllerGrant?.mode ?: "unknown"
                    syncTransport()
                }
            }
        })
        transportGrant = grant
        transport = session
        session.start()
        syncSensorListener()
        if (streaming) setSensorConnectingStatus()
        updateKeepAwake()
    }

    private fun displayConnectionStatus(ready: Boolean, message: String) {
        controllerMessage = message
        val link = when (connectionMode) {
            "usb" -> getString(R.string.usb_link)
            "wireless" -> getString(R.string.wireless_link)
            else -> null
        }
        controllerStatus?.text = if (link == null) message else getString(R.string.controller_link_status, link, message)
        playStatus?.apply {
            text = message
            visibility = if (ready) View.GONE else View.VISIBLE
        }
        controllerRetry?.apply {
            text = if (ready) "Reconnect" else "Retry"
            visibility = if (message.startsWith("Synchronizing")) View.GONE else View.VISIBLE
        }
    }

    private fun setSensorConnectingStatus() {
        status.text = when (connectionMode) {
            "usb" -> "Connecting through USB debugging."
            "wireless" -> "Connecting through wireless debugging."
            else -> "Connecting through ADB tunnel."
        }
    }

    private fun closeTransport() {
        val session = transport
        stopMotion()
        controllerReady = false
        transport = null
        transportGrant = null
        controllerPad?.setReady(false)
        session?.close()
    }

    private fun updateKeepAwake() {
        if (streaming || (controllerPad != null && transport != null)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun syncSensorListener(): Boolean {
        val current = foreground && focused
        val controls = current && playing && controllerReady && inputLayoutReady()
        val advanced = controls && motion != null
        if (!advanced && motionActive) stopMotion()
        if (advanced && !motionActive) motionStartsAt = SystemClock.elapsedRealtimeNanos() + 300_000_000L
        motionActive = advanced
        sensors.configure(foreground && streaming, advanced, controls && legacyTilt)
        motionHandler.removeCallbacks(motionTick)
        if (sensors.active || advanced) motionHandler.post(motionTick)
        return sensors.absolute != null
    }

    private fun inputLayoutReady(): Boolean {
        val lock = playProfile?.let(::playLock)
        return controllerPad?.width?.let { it > 0 } == true &&
            (lock == null || lock == resources.configuration.layoutOrientation)
    }

    private fun updateInputReadiness() {
        controllerPad?.setReady(controllerReady && playing && foreground && focused && inputLayoutReady())
        if (::sensors.isInitialized) syncSensorListener()
    }

    private fun stopMotion() {
        processors.forEach { it.stop() }
        transport?.freshMotion = emptyList()
        motionActive = false
        legacyAt = 0
        motionRotation = null
    }

    @Suppress("DEPRECATION")
    private fun tickMotion() {
        if (!foreground) return
        val now = SystemClock.elapsedRealtimeNanos()
        val session = transport
        val activeProcessors = processors.takeIf { motionActive }.orEmpty()
        val processor = activeProcessors.firstOrNull()
        val rotation = controllerPad?.display?.rotation ?: windowManager.defaultDisplay.rotation
        val previousRotation = motionRotation
        if (previousRotation != null && previousRotation != rotation && controllerReady && playing) {
            controllerPad?.releaseAll()
            stopMotion()
            syncSensorListener()
            return
        }
        motionRotation = rotation
        val selected = sensors.gameplay
        val time = sensors.timestamp(selected)
        val process = activeProcessors.takeIf { now >= motionStartsAt }.orEmpty()
        if (selected != null && freshMotion(time, now) && (process.isNotEmpty() || legacyTilt && time > legacyAt)) {
            sensors.pose(selected)?.let { pose ->
                process.forEach { it.orientation(pose, time, rotation) }
                if (playing && legacyTilt && controllerReady && time > legacyAt) {
                    legacyAt = time
                    controllerPad?.updateTilt(rightTiltDegrees(pose.w.toFloat(), pose.x.toFloat(), pose.y.toFloat(), pose.z.toFloat(), rotation))
                }
            }
        }
        if (process.isNotEmpty()) {
            process.forEach { it.expire(now) }
            session?.freshMotion = process.filter { it.fresh(now) }.map { it.id }
        }

        val absoluteAt = sensors.timestamp(sensors.absolute)
        val newStreamSample = streaming && freshMotion(absoluteAt, now) && absoluteAt > streamedAt
        if (session != null && (newStreamSample || processor != null && now - telemeteredAt >= 40_000_000L)) {
            val sample = sensors.telemetry(now, activeProcessors, connectionMode, includePose = streaming)
            session.send(sample)
            telemeteredAt = now
            if (newStreamSample) {
                streamedAt = absoluteAt
                samples++
            }
        }
        if (streaming && now - lastUiTimestamp >= 250_000_000L) {
            val source = sensors.absolute
            val fresh = freshMotion(absoluteAt, now)
            val q = source?.takeIf { fresh }?.let(sensors::pose)
            sensorGauge.pose(q)
            val rate = sensors.rate(source, now)?.let { "%.0f Hz".format(it) } ?: "Measuring rate"
            sensorSummary.text = listOfNotNull(sensors.label(source), rate,
                if (source == MotionSensor.GAME) "Relative heading" else if (source != null) "Magnetic heading" else null,
                if (fresh) "${(now - absoluteAt) / 1_000_000} ms old" else "Waiting for fresh orientation").joinToString(" · ")
            values.text = if (q == null) "Waiting for fresh orientation"
                else String.format(Locale.US, "Samples: %d\nQuaternion x y z w:\n%.3f  %.3f  %.3f  %.3f", samples, q.x, q.y, q.z, q.w)
            lastUiTimestamp = now
        }
        if (motionPanel != null && playing && now - lastPanelTimestamp >= 200_000_000L) {
            motionPanel?.update(processor?.relative != null, processor?.fresh(now) == true,
                selected != null || sensors.available(MotionSensor.GAME) || sensors.available(MotionSensor.ROTATION),
                sensors.available(MotionSensor.GYRO), gyro?.gyroEnabled == true,
                processor?.output ?: LayoutPoint(0.0, 0.0), activeProcessors.joinToString("\n") { it.summary(now) })
            lastPanelTimestamp = now
        }
        if (sensors.active || motionActive) motionHandler.postDelayed(motionTick, 16L)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        syncTransport()
        updateInputReadiness()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        if (!hasFocus) {
            controllerPad?.releaseAll()
        } else controllerWindow.immersive(playing || editor?.canvasOnly == true)
        if (::sensors.isInitialized) updateInputReadiness()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        editor?.cancelDrag()
        library?.refreshOrientation()
        controllerPad?.releaseAll()
        controllerPad?.setReady(false)
        playProfile?.takeIf { playLock(it) == null }?.let {
            controllerPad?.render(it.layoutFor(newConfig.layoutOrientation).controls)
        }
        stopMotion()
        updateInputReadiness()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCanceled) {
            if (navigateBack()) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun navigateBack(): Boolean {
        when {
            editor != null -> editor?.requestBack()
            playing -> setPlaying(false)
            controllerPad != null -> showProfiles()
            destination != Destination.CONNECT -> showConnect()
            else -> return false
        }
        return true
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!navigateBack()) super.onBackPressed()
    }

    override fun onPause() {
        foreground = false
        if (playing) setPlaying(false)
        closeTransport()
        syncSensorListener()
        updateKeepAwake()
        super.onPause()
    }

    override fun onDestroy() {
        closeTransport()
        motionHandler.removeCallbacks(motionTick)
        super.onDestroy()
    }

    private companion object {
        const val IMPORT_PROFILE = 21
        const val EXPORT_PROFILE = 22
        val exclusiveModes = listOf(OutputMode.KEYBOARD, OutputMode.GAMEPAD)
    }
}

