package com.example.hostossi

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.hostossi.MeasureableSensor

abstract class AndroidSensor(
    private val context: Context,
    private val sensorFeature: String,
    override val deviceType: DeviceType,
    private val sensorType: Int
) : MeasureableSensor(), SensorEventListener {

    override val doesSensorExist: Boolean
        get() = context.packageManager.hasSystemFeature(sensorFeature)

    private lateinit var sensorManager : SensorManager
    private var sensor : android.hardware.Sensor? = null

    override fun startListening() {
        if(!doesSensorExist) return
        if(!::sensorManager.isInitialized && sensor == null){
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensor = sensorManager.getDefaultSensor(sensorType)
        }
        sensor.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

    }

    override fun stopListening() {
        if(!doesSensorExist || !::sensorManager.isInitialized) return
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if(!doesSensorExist) return
        if(event?.sensor?.type == sensorType){
            onSensorValuesChanged?.invoke(event.values.toList())
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) = Unit
}