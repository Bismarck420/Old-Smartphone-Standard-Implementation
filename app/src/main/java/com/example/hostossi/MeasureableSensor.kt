package com.example.hostossi


abstract class MeasureableSensor{
    protected var onSensorValuesChanged:
            ((List<Float>) -> Unit)? = null
    abstract val deviceType: DeviceType
    abstract val doesSensorExist: Boolean
    abstract fun startListening()
    abstract fun stopListening()

    fun setOnSensorValuesChangedListener(listener: (List<Float>)-> Unit) {
        onSensorValuesChanged = listener
    }


}