package eu.sisik.backgroundcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

private const val RELIABLE_CONFIDENCE = 75
private const val DETECTED_PENDING_INTENT_REQUEST_CODE = 100

class DetectedActivityReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityRecognitionResult.hasResult(intent)) {
            val result = ActivityRecognitionResult.extractResult(intent)
            Toast.makeText(context, "Receive Acitvity: "+ result?.probableActivities?.get(0),
                Toast.LENGTH_LONG).show()
            result?.let { handleDetectedActivities(it.probableActivities, context) }
        }
    }

    private fun handleDetectedActivities(probableActivities: List<DetectedActivity>, context: Context) {
        probableActivities
            .filter {
                it.type == DetectedActivity.STILL ||
                        it.type == DetectedActivity.WALKING ||
                        it.type == DetectedActivity.RUNNING
            }
            .filter { it.confidence > RELIABLE_CONFIDENCE }
            .run {
                if (isNotEmpty()) {
                    Log.e("----------------------", "ACTIVITY: "+ this[0])
                }
            }
    }

    companion object {

        fun getPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DetectedActivityReceiver::class.java)
            return PendingIntent.getBroadcast(context, DETECTED_PENDING_INTENT_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
