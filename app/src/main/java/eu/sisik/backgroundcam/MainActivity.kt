package eu.sisik.backgroundcam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.util.prefs.Preferences

import androidx.datastore.core.DataStore
import androidx.datastore.*
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.firestore.auth.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

//import androidx.datastore.preferences.core.Preferences
//import androidx.datastore.preferences.core.edit
//import androidx.datastore.preferences.core.preferencesKey
//import androidx.datastore.preferences.createDataStore
//import androidx.lifecycle.lifecycleScope

val MainActivity.dataStore by preferencesDataStore(name = "settings")


class MainActivity : AppCompatActivity() {

    val EXAMPLE_COUNTER = intPreferencesKey("example_counter")
    val exampleCounterFlow: Flow<Int> = this.dataStore.data
        .map { preferences ->
            // No type safety.
            preferences[EXAMPLE_COUNTER] ?: 0
        }


    suspend fun incrementCounter() {
        this.dataStore.edit { settings ->
            val currentCounterValue = settings[EXAMPLE_COUNTER] ?: 0
            settings[EXAMPLE_COUNTER] = currentCounterValue + 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        runBlocking {
            incrementCounter()
            incrementCounter()
            incrementCounter()
        }

//        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        initView()

        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            // We don't have camera permission yet. Request it from the user.
            ActivityCompat.requestPermissions(this, arrayOf(permission), CODE_PERM_CAMERA)
        }
        val exampleData = runBlocking { dataStore.data.first() }
        Log.e("Tag", "#+#+#+#+#+#+#+#+#"+ exampleData)
    }

    override fun onResume() {
        super.onResume()

        registerReceiver(receiver, IntentFilter(CamService.ACTION_STOPPED))

        val running = isServiceRunning(this, CamService::class.java)
        flipButtonVisibility(running)
    }

    override fun onPause() {
        super.onPause()

        unregisterReceiver(receiver)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CODE_PERM_CAMERA -> {
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, getString(R.string.err_no_cam_permission), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private val receiver = object: BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            when (p1?.action) {
                CamService.ACTION_STOPPED -> flipButtonVisibility(false)
            }
        }
    }

    private fun initView() {
        butStart.setOnClickListener {
            if (!isServiceRunning(this, CamService::class.java)) {
                notifyService(CamService.ACTION_START)
                finish()
            }
        }

        butStop.setOnClickListener {
            stopService(Intent(this, CamService::class.java))
        }
    }

    private fun notifyService(action: String) {
        val intent = Intent(this, CamService::class.java)
        intent.action = action
        intent.putExtra("selectedAlert", getSelectedRadio())
        startService(intent)
    }

    private fun flipButtonVisibility(running: Boolean) {
        butStart.visibility =  if (running) View.GONE else View.VISIBLE
        butStop.visibility =  if (running) View.VISIBLE else View.GONE
    }

    fun getSelectedRadio() : Int {
        val radioGroup = findViewById(R.id.radio) as RadioGroup;
        val radio = radioGroup.findViewById(radioGroup.getCheckedRadioButtonId()) as RadioButton
        Log.e("RadioSelection", "getSelectedRadio: "+ radioGroup.getCheckedRadioButtonId())
        Log.e("RadioSelection", "radio text: "+ radio.text)
        radio.text
        return radioGroup.getCheckedRadioButtonId()
    }

    companion object {

        const val CODE_PERM_SYSTEM_ALERT_WINDOW = 6111
        const val CODE_PERM_CAMERA = 6112
    }




}
