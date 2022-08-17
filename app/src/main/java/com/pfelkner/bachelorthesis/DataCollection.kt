package com.pfelkner.bachelorthesis

import android.app.Service
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.installations.FirebaseInstallations
import com.pfelkner.bachelorthesis.CamService.Companion.TAG
import java.text.SimpleDateFormat
import java.util.*
import com.pfelkner.bachelorthesis.CamService.Entry

class DataCollection constructor(context: Context){
    val context = context
    private val db : FirebaseFirestore = FirebaseFirestore.getInstance()

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

    fun getRingMode(): Int {
        val am = context.getSystemService(Service.AUDIO_SERVICE) as AudioManager

        when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Log.i("MyApp", "Silent mode")
            AudioManager.RINGER_MODE_VIBRATE -> Log.i("MyApp", "Vibrate mode")
            AudioManager.RINGER_MODE_NORMAL -> Log.i("MyApp", "Normal mode")
        }
        return am.ringerMode
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
        const val SOURCE_START = "Detection Started"

        fun setActivityType(newActivity: String) {
            currentActivity = newActivity
        }
        fun setTransitionType(transition: String) {
            currentTransition = transition
        }

    }

    fun writeDataOnFirestore(entry: Entry){
        val newEntry = HashMap<String, Any>()
        newEntry["id"] = entry.id
        newEntry["source"] = entry.source
        newEntry["date"] = entry.date
        newEntry["timestamp"] = entry.created
        newEntry["ringMode"] = entry.ringMode
        newEntry["ringModeDesc"] = entry.ringModeDesc
        newEntry["ringModeDesc"] = entry.ringModeDesc
        newEntry["activity"] = entry.activity
        newEntry["activityTransition"] = entry.activityTransition
        newEntry["snoozing"] = entry.snoozing
        db.collection("student_info").document()
            .set(newEntry)
            .addOnSuccessListener { Log.d(TAG, "DocumentSnapshot successfully written!") }
            .addOnFailureListener { e -> Log.w(TAG, "Error writing document", e) }
    }

}