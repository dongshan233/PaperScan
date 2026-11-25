package com.dsh.scanner.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 手持稳定度来源：为识别状态机提供陀螺仪角速度（°/s）。
 *
 * 默认实现 [GyroscopeMotionSource] 读取硬件陀螺仪；宿主也可以注入自己的
 * 实现（例如 IMU 融合结果、伪角速度用于测试）。
 */
interface MotionSource {

    /** 开始接收运动数据。 */
    fun start()

    /** 停止接收运动数据（宿主应在 onPause 调用以省电）。 */
    fun stop()

    /** 当前角速度模长的 EMA（°/s）；无数据时为 0。 */
    val angularSpeedDeg: Float
}

/** 硬件陀螺仪实现：三轴角速度模长 EMA 平滑；设备无陀螺仪时恒为 0。 */
class GyroscopeMotionSource(context: Context) : MotionSource, SensorEventListener {

    /** 传感器管理器；设备不提供时为 null。 */
    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** 默认陀螺仪；没有硬件陀螺仪时为 null。 */
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 三轴角速度模长的 EMA（°/s），无传感器时保持 0。 */
    @Volatile
    override var angularSpeedDeg: Float = 0f
        private set

    override fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val w = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) * 57.29578f
        angularSpeedDeg = 0.8f * angularSpeedDeg + 0.2f * w
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
