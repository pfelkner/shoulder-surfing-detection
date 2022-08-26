package com.pfelkner.bachelorthesis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.location.ActivityTransitionResult
import com.pfelkner.bachelorthesis.DataCollection.Companion.setActivityType
import com.pfelkner.bachelorthesis.DataCollection.Companion.setTransitionType
import java.text.SimpleDateFormat
import java.util.*
import com.pfelkner.bachelorthesis.util.ActivityTransitionsUtil
import com.pfelkner.bachelorthesis.util.Constants
import io.karn.notify.Notify


class ActivityTransitionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)
            result?.let {
                result.transitionEvents.forEach { event ->
                    //Info for debugging purposes
                    val activityType = ActivityTransitionsUtil.toActivityString(event.activityType)
                    val transitionType = ActivityTransitionsUtil.toTransitionType(event.transitionType)
                    setActivityType(activityType)
                    setTransitionType(transitionType)
                    val info =
                        "Transition: " + activityType +
                                " (" + transitionType +")" + "   " +
                                SimpleDateFormat("HH:mm:ss", Locale.GERMAN).format(Date())

                    Notify
                        .with(context)
                        .content {
                            title = "Activity Detected"
                            text =
                                "I can see you are in $activityType state"
                        }
                        .show(id = Constants.ACTIVITY_TRANSITION_NOTIFICATION_ID)

                    Toast.makeText(context, info, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}