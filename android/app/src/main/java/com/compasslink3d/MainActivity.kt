package com.compasslink3d

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity(), SensorEventListener {
    private lateinit var manager: SensorManager
    private lateinit var status: TextView
    private lateinit var values: TextView
    private lateinit var toggle: Button
    private lateinit var motionScreen: LinearLayout
    private var controllerPad: ControllerPad? = null
    private var controllerStatus: TextView? = null
    private var controllerToken: String? = null
    private var sensor: Sensor? = null
    private var transport: UsbSensorTransport? = null
    private var streaming = false
    private val quaternion = FloatArray(4)
    private var samples = 0L
    private var lastUiTimestamp = 0L
    private var connectionMode = "unknown"

    private fun readConnectionMode() {
        connectionMode = intent.getStringExtra("connection_mode")
            ?.takeIf { it == "usb" || it == "wireless" } ?: "unknown"
        controllerToken = intent.getStringExtra("controller_token")
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        stopAll()
        if (controllerPad != null) showMotionScreen()
        setIntent(intent)
        readConnectionMode()
        status.text = if (sensor == null) "No supported rotation-vector sensor found"
            else "Ready - USB or wireless ADB tunnel required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readConnectionMode()
        manager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        status = TextView(this).apply {
            textSize = 18f
            text = "Ready - USB or wireless ADB tunnel required"
        }
        values = TextView(this).apply {
            textSize = 16f
            text = "Sensor: ${sensor?.name ?: "unavailable"}"
        }
        toggle = Button(this).apply {
            text = "Start streaming"
            isEnabled = sensor != null
            setOnClickListener { if (streaming) stopAll() else startStream() }
        }
        motionScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            applyScreenInsets(this, 24)
            addView(TextView(this@MainActivity).apply {
                text = "CompassLink3D"
                textSize = 28f
            })
            addView(status)
            addView(values)
            addView(toggle)
            addView(Button(this@MainActivity).apply {
                text = "Controller Mode"
                setOnClickListener { showControllerScreen() }
            })
        }
        setContentView(motionScreen)
        if (sensor == null) status.text = "No supported rotation-vector sensor found"
    }

    private fun showControllerScreen() {
        val pad = ControllerPad(this) { event -> transport?.sendKey(event) }
        controllerPad = pad
        controllerStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB5CCE8.toInt())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Controller Mode"
                textSize = 20f
                setTextColor(0xFFEDF4FF.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Motion / Sensor"
                setOnClickListener { showMotionScreen() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Stop Controller"
                setOnClickListener { showMotionScreen() }
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A1020.toInt())
            applyScreenInsets(this, 12)
            addView(header)
            addView(controllerStatus)
            addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        replaceTransport()
    }

    private fun showMotionScreen() {
        controllerPad?.releaseAll(notify = false)
        controllerPad = null
        controllerStatus = null
        setContentView(motionScreen)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        replaceTransport()
    }

    private fun applyScreenInsets(view: View, paddingDp: Int) {
        val padding = (paddingDp * resources.displayMetrics.density).toInt()
        view.setPadding(padding, padding, padding, padding)
        if (Build.VERSION.SDK_INT >= 35) {
            view.setOnApplyWindowInsetsListener { current, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                current.setPadding(padding + safe.left, padding + safe.top, padding + safe.right, padding + safe.bottom)
                insets
            }
        }
    }

    private fun startStream() {
        val selectedSensor = sensor ?: return
        if (streaming) return
        if (!manager.registerListener(this, selectedSensor, 20_000)) {
            status.text = "Unable to register orientation sensor"
            return
        }
        samples = 0
        lastUiTimestamp = 0
        streaming = true
        toggle.text = "Stop streaming"
        replaceTransport()
    }

    private fun replaceTransport() {
        closeTransport()
        val token = controllerToken.takeIf { controllerPad != null }
        controllerStatus?.text = if (token == null) {
            "Run the USB or wireless connection command to authorize this phone."
        } else {
            "Connecting to the PC through ADB…"
        }
        if (token == null && !streaming) {
            updateKeepAwake()
            return
        }
        // Ignore callbacks from sessions that were closed or superseded.
        lateinit var session: UsbSensorTransport
        session = UsbSensorTransport(token, { message ->
            runOnUiThread { if (transport === session && streaming) status.text = message }
        }, { ready, message ->
            runOnUiThread {
                if (transport === session) {
                    controllerPad?.setReady(ready)
                    val link = when (connectionMode) {
                        "usb" -> "USB · "
                        "wireless" -> "Wireless debugging · "
                        else -> ""
                    }
                    val text = link + message
                    if (controllerStatus?.text != text) controllerStatus?.text = text
                    updateKeepAwake()
                }
            }
        })
        transport = session
        session.start()
        if (streaming) setSensorConnectingStatus()
        updateKeepAwake()
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
        transport = null
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

    private fun stopAll() {
        closeTransport()
        if (streaming) {
            manager.unregisterListener(this)
            status.text = "Stopped"
        }
        streaming = false
        toggle.text = "Start streaming"
        updateKeepAwake()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val session = transport ?: return
        SensorManager.getQuaternionFromVector(quaternion, event.values) // [w,x,y,z]
        if (quaternion.any { !it.isFinite() }) return
        val q = doubleArrayOf(
            quaternion[1].toDouble(),
            quaternion[2].toDouble(),
            quaternion[3].toDouble(),
            quaternion[0].toDouble(),
        )
        session.send(JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("timestampNs", event.timestamp.toString())
            put("sensor", event.sensor.stringType)
            put("accuracy", event.accuracy)
            put("q", JSONArray(q.toList()))
            put("connectionMode", connectionMode)
        }.toString())
        samples++
        if (event.timestamp - lastUiTimestamp >= 100_000_000L) {
            values.text = String.format(
                Locale.US,
                "Samples: %d\nQuaternion x y z w:\n%.3f  %.3f  %.3f  %.3f",
                samples, q[0], q[1], q[2], q[3],
            )
            lastUiTimestamp = event.timestamp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onResume() {
        super.onResume()
        if (controllerPad != null && transport == null) replaceTransport()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) controllerPad?.releaseAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controllerPad?.releaseAll()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (controllerPad != null) showMotionScreen() else super.onBackPressed()
    }

    override fun onPause() {
        stopAll()
        super.onPause()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }
}

