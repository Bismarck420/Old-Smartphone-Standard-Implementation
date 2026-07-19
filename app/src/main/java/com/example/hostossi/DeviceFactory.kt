package com.example.hostossi

import android.content.Context

object DeviceFactory {

    fun create(
        context: Context,
        entity: DeviceEntity
    ): MeasureableSensor? {

        return when (entity.type) {
            DeviceType.LIGHT_SENSOR -> LightSensor(context)
            DeviceType.ACCELEROMETER -> AccelerometerSensor(context)
            DeviceType.GYROSCOPE -> GyroscopeSensor(context)
            DeviceType.MAGNETIC_FIELD -> MagneticFieldSensor(context)
            DeviceType.PROXIMITY -> ProximitySensor(context)
            DeviceType.PRESSURE -> PressureSensor(context)
            DeviceType.AMBIENT_TEMPERATURE -> AmbientTemperatureSensor(context)
            DeviceType.RELATIVE_HUMIDITY -> RelativeHumiditySensor(context)
            DeviceType.GRAVITY -> GravitySensor(context)
            DeviceType.LINEAR_ACCELERATION -> LinearAccelerationSensor(context)
            DeviceType.ROTATION_VECTOR -> RotationVectorSensor(context)
            DeviceType.STEP_COUNTER -> StepCounterSensor(context)
            DeviceType.HEART_RATE -> HeartRateSensor(context)
            else -> null
        }
    }
}
