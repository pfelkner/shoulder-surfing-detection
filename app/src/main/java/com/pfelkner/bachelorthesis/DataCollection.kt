package com.pfelkner.bachelorthesis

import android.app.Service
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.installations.FirebaseInstallations
import com.pfelkner.bachelorthesis.CamService.Companion.TAG
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class DataCollection constructor(context: Context){
    val context = context
    private val db : FirebaseFirestore = FirebaseFirestore.getInstance()
    private val dbRef : DocumentReference = db.collection("Entries").document("Test")
    private val docRef : DocumentReference = db.document("Entries/Test")
    var entries : MutableList<Entry> = mutableListOf()
    var newEntries : MutableList<(HashMap<String, Any>)> = mutableListOf()

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

    fun getDateTime(): String {
        val sdf = SimpleDateFormat("dd/M/yyyy hh:mm:ss")
        return sdf.format(Date())
    }


    fun getCurrentActivity(): String {
        if (currentActivity == null)
            return ""
        else
            return currentActivity as String
    }

    fun getCurrentTransition(): String {
        if (currentTransition == null)
            return ""
        else
            return currentTransition as String
    }
    fun getInstallationId(): String {
        if (installationId == null)
            return ""
        else
            return installationId as String
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
//        dbWrite(newEntry)
        entries.add(newEntry)
    }

    enum class Trigger {
        ATTACK_DETECTED,
        DETECTION_END,
        SNOOZED
    }

//    fun dbWritePatch() {
//        val new : Entries = Entries(arrayListOf(entries))
//    }



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

        db.collection(getInstallationId() +"-"+ entry.alertMode).document()
            .set(entry)
            .addOnSuccessListener { Log.d(TAG, "DocumentSnapshot successfully written!") }
            .addOnFailureListener { e -> Log.w(TAG, "Error writing document", e) }
//        Log.e("!!!", newEntry.toString())
    }

}