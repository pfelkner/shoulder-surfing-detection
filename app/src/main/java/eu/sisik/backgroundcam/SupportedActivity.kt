package eu.sisik.backgroundcam

import com.google.android.gms.location.DetectedActivity

const val SUPPORTED_ACTIVITY_KEY = "activity_key"

enum class SupportedActivity(i: Int) {
    NOT_STARTED(0),
    STILL(1),
    WALKING(2),
    RUNNING(3);

    companion object {

        fun fromActivityType(type: Int): SupportedActivity = when (type) {
            DetectedActivity.STILL -> STILL
            DetectedActivity.WALKING -> WALKING
            DetectedActivity.RUNNING -> RUNNING
            else -> throw IllegalArgumentException("activity $type not supported")
        }
    }
}