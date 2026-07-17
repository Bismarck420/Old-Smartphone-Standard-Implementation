package com.example.hostossi

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor

class LightSensor(
    context: Context
): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_LIGHT,
    deviceType = DeviceType.LIGHT_SENSOR,
    sensorType = Sensor.TYPE_LIGHT
)

class AccelerometerSensor(
    context: Context
): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_ACCELEROMETER,
    deviceType = DeviceType.ACCELEROMETER,
    sensorType = Sensor.TYPE_ACCELEROMETER
)
class GyroscopeSensor(
    context: Context
): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_GYROSCOPE,
    deviceType = DeviceType.GYROSCOPE,
    sensorType = Sensor.TYPE_GYROSCOPE
)