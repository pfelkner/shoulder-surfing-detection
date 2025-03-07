package com.pfelkner.bachelorthesis

import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.installations.FirebaseInstallations
import com.pfelkner.bachelorthesis.CamService.Companion.TAG
import com.pfelkner.bachelorthesis.util.Constants
import com.pfelkner.bachelorthesis.util.Constants.COUNTER_INTERVAL
import com.pfelkner.bachelorthesis.util.Constants.STOARGE_COUNTER
import java.util.*
import kotlin.math.absoluteValue


class DataCollection constructor(context: Context){
    val context = context
    private val db : FirebaseFirestore = FirebaseFirestore.getInstance()
    private val dbRef : DocumentReference = db.collection("Entries").document("Test")
    private val docRef : DocumentReference = db.document("Entries/Test")
    var entries : MutableList<Entry> = mutableListOf()
    var newEntries : MutableList<(HashMap<String, Any>)> = mutableListOf()
    var alertCounter = 0

    fun setInstallationId() {
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d("Installations", "Installation ID: " + task.result)
                installationId = task.result
            } else {
                Log.e("Installations", "Unable to get Installation ID")
            }
        }
    }

    fun getRingMode(): String {
        val am = context.getSystemService(Service.AUDIO_SERVICE) as AudioManager
        var ringMode : String = "Unkown"
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> ringMode = "Silent mode"
            AudioManager.RINGER_MODE_VIBRATE -> ringMode = "Vibrate mode"
            AudioManager.RINGER_MODE_NORMAL -> ringMode = "Normal mode"
        }
        return ringMode
    }

    fun getCurrentActivity(): String {
        if (currentActivity == null)
            return ""
        else
            return currentActivity as String
    }

    fun getInstallationId(): String {
        if (installationId == null)
            return ""
        else
            return installationId as String
    }

    fun getUserId(): String {
        if (storage.getString("userId", null) == null)
            return ""
        else
            return storage.getString("userId", null) as String
    }

     fun setUserId(newId : String) {
        if (getUserId() != null) {
            storage
                .edit()
                .putString("userId", newId)
                .apply()
        }
    }

    companion object {
        var currentActivity: String? = null
        private var currentTransition: String? = null
        private  var installationId : String? = null
        const val EVENT_START = "Detection Started"
        const val EVENT_END = "Detection Ended"

        fun setActivityType(newActivity: String) {
            currentActivity = newActivity
        }
        fun setTransitionType(transition: String) {
            currentTransition = transition
        }

    }

    fun getFirstInstall(context: Context): Long {
        val pm = context.packageManager
        return pm.getPackageInfo("com.pfelkner.bachelorthesis", PackageManager.GET_ACTIVITIES).firstInstallTime
    }

     fun getAlertMethod(): AlertMechanism {
        val firstInstall = getFirstInstall(context)
         val now = System.currentTimeMillis()

         return if (firstInstall.minus(now).absoluteValue > (2* Constants.THREE_H_MS))
             AlertMechanism.fromInt(3)
         else if (firstInstall.minus(now).absoluteValue > Constants.THREE_H_MS)
             AlertMechanism.fromInt(2)
         else
             AlertMechanism.fromInt(1)
    }

     fun getAlertMethod2(): AlertMechanism {
        val alertCounter = storage.getInt(STOARGE_COUNTER, 0)

         return if (alertCounter >= (2*COUNTER_INTERVAL))
             AlertMechanism.fromInt(3)
         else if (alertCounter >= COUNTER_INTERVAL)
             AlertMechanism.fromInt(2)
         else
             AlertMechanism.fromInt(1)
    }

    fun updateAlertCounter(alerts: Int) {
        val previousAlerts = storage.getInt(STOARGE_COUNTER, 0)
        storage
            .edit()
            .putInt(STOARGE_COUNTER, (alerts+previousAlerts))
            .apply()
    }

    data class Entry(
        var id : String = "",
        var trigger: String,
        var alertMode : String,
        var created: Timestamp,
        var ringMode: String = "",
        var activity: String = "",
        var snoozing: Boolean
    )

    data class Entries(
        var test: ArrayList<Entry>
    )

    fun logEvent(trigger : Trigger, alertMechanism: AlertMechanism, snoozing: Boolean) {
        val newEntry : Entry = Entry(
            getInstallationId(),
            trigger.toString(),
            alertMechanism.toString(),
            Timestamp.now(),
            getRingMode(),
            getCurrentActivity(),
            snoozing
        )
        dbWrite(newEntry)
        entries.add(newEntry)
    }

    enum class Trigger {
        ATTACK_DETECTED,
        DETECTION_END,
        SNOOZED
    }

    fun dbWrite(entry: Entry){
//
//        val newEntry = HashMap<String, Any>()
//            newEntry["id"] = entry.id
//            newEntry["source"] = entry.source
//            newEntry["date"] = entry.date
//            newEntry["timestamp"] = entry.created
//            newEntry["ringMode"] = entry.ringMode
//            newEntry["ringModeDesc"] = entry.ringModeDesc
//            newEntry["ringModeDesc"] = entry.ringModeDesc
//            newEntry["activity"] = entry.activity
//            newEntry["activityTransition"] = entry.activityTransition
//            newEntry["snoozing"] = entry.snoozing
//            newEntries.add(newEntry)
//            Log.e("!!!", newEntry.toString())

        db.collection(getUserId() +"-"+ entry.alertMode).document()
            .set(entry)
            .addOnSuccessListener { Log.d(TAG, "DocumentSnapshot successfully written!") }
            .addOnFailureListener { e -> Log.w(TAG, "Error writing document", e) }
//        Log.e("!!!", newEntry.toString())
    }

}