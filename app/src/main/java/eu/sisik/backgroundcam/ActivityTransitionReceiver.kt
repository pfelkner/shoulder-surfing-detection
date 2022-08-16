package eu.sisik.backgroundcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.location.ActivityTransitionResult
import java.text.SimpleDateFormat
import java.util.*
import eu.sisik.backgroundcam.util.ActivityTransitionsUtil
import eu.sisik.backgroundcam.util.Constants
import io.karn.notify.Notify


class ActivityTransitionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)
            result?.let {
                result.transitionEvents.forEach { event ->
                    //Info for debugging purposes
                    val info =
                        "Transition: " + ActivityTransitionsUtil.toActivityString(event.activityType) +
                                " (" + ActivityTransitionsUtil.toTransitionType(event.transitionType) + ")" + "   " +
                                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())


                    Notify
                        .with(context)
                        .content {
                            title = "Activity Detected"
                            text =
                                "I can see you are in ${
                                    ActivityTransitionsUtil.toActivityString(
                                        event.activityType
                                    )
                                } state"
                        }
                        .show(id = Constants.ACTIVITY_TRANSITION_NOTIFICATION_ID)

                    Toast.makeText(context, info, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}