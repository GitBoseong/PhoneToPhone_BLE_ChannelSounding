package com.example.ble6_channelsounding

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

class SensorCollector(context: Context, private val log: (String) -> Unit) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("PhoneCS-Sensors").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val latest = mutableMapOf<Int, SensorValue>()
    private val unavailable = mutableSetOf<Int>()
    private var listener: SensorEventListener? = null
    private val types = linkedMapOf(Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope", Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "Game rotation vector", Sensor.TYPE_PRESSURE to "Pressure")

    fun start() {
        stop()
        val wallAnchor = System.currentTimeMillis()
        val monoAnchor = SystemClock.elapsedRealtimeNanos()
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(lock) {
                    if (listener !== this) return
                    val values = if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                        val quaternion = FloatArray(4)
                        SensorManager.getQuaternionFromVector(quaternion, event.values)
                        listOf(quaternion[1], quaternion[2], quaternion[3], quaternion[0])
                    } else event.values.toList()
                    latest[event.sensor.type] = SensorValue(values,
                        wallAnchor + (event.timestamp - monoAnchor) / 1_000_000L)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        synchronized(lock) { listener = callback }
        types.forEach { (type, label) ->
            val sensor = manager?.getDefaultSensor(type)
            val registered = sensor != null && runCatching {
                manager.registerListener(callback, sensor, 20_000, handler)
            }.getOrDefault(false)
            if (!registered && unavailable.add(type)) log("$label sensor unavailable")
        }
        log("IMU ON; requested interval=20000 us, recorder=50 Hz")
    }

    fun snapshot(): ImuSnapshot = synchronized(lock) {
        ImuSnapshot(latest[Sensor.TYPE_ACCELEROMETER], latest[Sensor.TYPE_GYROSCOPE],
            latest[Sensor.TYPE_MAGNETIC_FIELD], latest[Sensor.TYPE_GAME_ROTATION_VECTOR], latest[Sensor.TYPE_PRESSURE])
    }
    fun stop() {
        val old = synchronized(lock) { listener.also { listener = null; latest.clear() } }
        old?.let { manager?.unregisterListener(it) }
    }
    fun destroy() { stop(); thread.quitSafely() }
}
