package eu.sisik.backgroundcam

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.ActivityRecognitionClient

class DetectedActivityService: Service() {

    inner class LocalBinder : Binder() {
        val serverInstance: DetectedActivityService
            get() = this@DetectedActivityService
    }

    override fun onBind(p0: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        requestActivityUpdates()
    }

    @SuppressLint("MissingPermission") // TODO
    private fun requestActivityUpdates() {
        val task = ActivityRecognitionClient(this).requestActivityUpdates(1000L,
            DetectedActivityReceiver.getPendingIntent(this))

        task.run {
            addOnSuccessListener {
                Log.d("ActivityUpdate", getString(R.string.activity_update_request_success))
            }
            addOnFailureListener {
                Log.d("ActivityUpdate", getString(R.string.activity_update_request_failed))
            }
        }
    }
    @SuppressLint("MissingPermission") // TODO
    private fun removeActivityUpdates() {
        val task = ActivityRecognitionClient(this).removeActivityUpdates(
            DetectedActivityReceiver.getPendingIntent(this))

        task.run {
            addOnSuccessListener {
                Log.d("ActivityUpdate", getString(R.string.activity_update_remove_success))
            }
            addOnFailureListener {
                Log.d("ActivityUpdate", getString(R.string.activity_update_remove_failed))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeActivityUpdates()
    }
}
