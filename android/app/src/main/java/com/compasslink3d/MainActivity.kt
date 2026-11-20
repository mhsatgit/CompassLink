package com.compasslink3d

import android.app.Activity
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Gravity
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
    private var sensor: Sensor? = null
    private var transport: UsbSensorTransport? = null
    private val quaternion = FloatArray(4)
    private var samples = 0L
    private var lastUiTimestamp = 0L
    private var connectionMode = "unknown"

    private fun readConnectionMode() {
        connectionMode = intent.getStringExtra("connection_mode")
            ?.takeIf { it == "usb" || it == "wireless" } ?: "unknown"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        stopStream()
        setIntent(intent)
        readConnectionMode()
        status.text = "Ready - USB or wireless ADB tunnel required"
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
            setOnClickListener { if (transport == null) startStream() else stopStream() }
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@MainActivity).apply {
                text = "CompassLink3D"
                textSize = 28f
            })
            addView(status)
            addView(values)
            addView(toggle)
        })
        if (sensor == null) status.text = "No supported rotation-vector sensor found"
    }

    private fun startStream() {
        val selectedSensor = sensor ?: return
        if (transport != null) return
        if (!manager.registerListener(this, selectedSensor, 20_000)) {
            status.text = "Unable to register orientation sensor"
            return
        }
        samples = 0
        lastUiTimestamp = 0
        // Ignore callbacks from sessions that were closed or superseded.
        lateinit var session: UsbSensorTransport
        session = UsbSensorTransport { message ->
            runOnUiThread { if (transport === session) status.text = message }
        }
        transport = session
        session.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggle.text = "Stop streaming"
        status.text = when (connectionMode) {
            "usb" -> "Connecting through USB debugging."
            "wireless" -> "Connecting through wireless debugging."
            else -> "Connecting through ADB tunnel."
        }
    }

    private fun stopStream() {
        val session = transport
        transport = null
        manager.unregisterListener(this)
        session?.close()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggle.text = "Start streaming"
        status.text = "Stopped"
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
    override fun onPause() {
        stopStream()
        super.onPause()
    }
}

