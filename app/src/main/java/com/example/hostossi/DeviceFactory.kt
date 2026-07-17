package com.example.hostossi

import android.content.Context
import com.example.hostossi.AndroidSensor


object DeviceFactory {

    fun create(
        context: Context,
        entity: DeviceEntity
    ): MeasureableSensor? {

        return when (entity.type) {

            DeviceType.LIGHT_SENSOR ->
                LightSensor(context)

            DeviceType.ACCELEROMETER ->
                AccelerometerSensor(context)

            DeviceType.GYROSCOPE ->
                GyroscopeSensor(context)

            else ->
                null
        }
    }
}