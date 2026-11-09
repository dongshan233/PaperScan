package com.dsh.scanner.model

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Gyroscope magnitude in deg/s, EMA-smoothed. 0 when no sensor is present. */
class MotionGate(context: Context) : SensorEventListener {

    /** 传感器管理器；设备不提供时为 null。 */
    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** 默认陀螺仪；没有硬件陀螺仪时为 null。 */
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 三轴角速度模长的 EMA（°/s），无传感器时保持 0。 */
    @Volatile
    var angularSpeedDeg: Float = 0f
        private set

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val w = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) * 57.29578f
        angularSpeedDeg = 0.8f * angularSpeedDeg + 0.2f * w
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
