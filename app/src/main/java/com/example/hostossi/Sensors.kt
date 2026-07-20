package com.example.hostossi

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor

class LightSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_LIGHT,
    deviceType = DeviceType.LIGHT_SENSOR,
    sensorType = Sensor.TYPE_LIGHT
)

class AccelerometerSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_ACCELEROMETER,
    deviceType = DeviceType.ACCELEROMETER,
    sensorType = Sensor.TYPE_ACCELEROMETER
)

class GyroscopeSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_GYROSCOPE,
    deviceType = DeviceType.GYROSCOPE,
    sensorType = Sensor.TYPE_GYROSCOPE
)

class MagneticFieldSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_COMPASS,
    deviceType = DeviceType.MAGNETIC_FIELD,
    sensorType = Sensor.TYPE_MAGNETIC_FIELD
)

class ProximitySensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_PROXIMITY,
    deviceType = DeviceType.PROXIMITY,
    sensorType = Sensor.TYPE_PROXIMITY
)

class PressureSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_BAROMETER,
    deviceType = DeviceType.PRESSURE,
    sensorType = Sensor.TYPE_PRESSURE
)

class AmbientTemperatureSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_AMBIENT_TEMPERATURE,
    deviceType = DeviceType.AMBIENT_TEMPERATURE,
    sensorType = Sensor.TYPE_AMBIENT_TEMPERATURE
)

class RelativeHumiditySensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_RELATIVE_HUMIDITY,
    deviceType = DeviceType.RELATIVE_HUMIDITY,
    sensorType = Sensor.TYPE_RELATIVE_HUMIDITY
)

class GravitySensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = "", // System sensors might not have features
    deviceType = DeviceType.GRAVITY,
    sensorType = Sensor.TYPE_GRAVITY
)

class LinearAccelerationSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = "",
    deviceType = DeviceType.LINEAR_ACCELERATION,
    sensorType = Sensor.TYPE_LINEAR_ACCELERATION
)

class RotationVectorSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = "",
    deviceType = DeviceType.ROTATION_VECTOR,
    sensorType = Sensor.TYPE_ROTATION_VECTOR
)

class StepCounterSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_STEP_COUNTER,
    deviceType = DeviceType.STEP_COUNTER,
    sensorType = Sensor.TYPE_STEP_COUNTER
)

class HeartRateSensor(context: Context): AndroidSensor(
    context = context,
    sensorFeature = PackageManager.FEATURE_SENSOR_HEART_RATE,
    deviceType = DeviceType.HEART_RATE,
    sensorType = Sensor.TYPE_HEART_RATE
)
