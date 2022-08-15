package eu.sisik.backgroundcam

import android.app.Service
import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class DataCollection constructor(context: Context){
    val context = context

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
        val currentDate = sdf.format(Date())
        Log.i("MyApp", "Date: "+currentDate)

        return currentDate
    }

}