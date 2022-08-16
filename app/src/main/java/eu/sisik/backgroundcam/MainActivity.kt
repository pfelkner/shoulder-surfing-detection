package eu.sisik.backgroundcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.android.synthetic.main.activity_main.*

const val PERMISSION_REQUEST_ACTIVITY_RECOGNITION = 1000
const val PERMISSION_REQUEST_CUSTOM = 42069
class MainActivity : AppCompatActivity() {

    lateinit var currentActivity: SupportedActivity
    private var isTrackingStarted = false
    private val transitionBroadcastReceiver: TransitionsReceiver = TransitionsReceiver().apply {
        action = { setDetectedActivity(it) }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        initView()

        requestPermission()

        if (isPermissionGranted()) {
            startService(Intent(this, DetectedActivityService::class.java))
            requestActivityTransitionUpdates()
            isTrackingStarted = true
            Toast.makeText(this@MainActivity, "You've started activity tracking",
                Toast.LENGTH_SHORT).show()
        } else {
            requestPermission()
        }
    }

    override fun onResume() {
        super.onResume()

        registerReceiver(receiver, IntentFilter(CamService.ACTION_STOPPED))
        registerReceiver(transitionBroadcastReceiver, IntentFilter(TRANSITIONS_RECEIVER_ACTION))

        val running = isServiceRunning(this, CamService::class.java)
        flipButtonVisibility(running)
    }

    override fun onPause() {
        super.onPause()

        unregisterReceiver(receiver)
        unregisterReceiver(transitionBroadcastReceiver)
    }

    override fun onDestroy() {
//        removeActivityTransitionUpdates()
//        stopService(Intent(this, DetectedActivityService::class.java))
        super.onDestroy()
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
        Log.e("RadioSelection", "getSelectedRadio: "+ radioGroup.getCheckedRadioButtonId())
        return radioGroup.getCheckedRadioButtonId()
    }

    companion object {

        private var currentActivity: SupportedActivity? = null
        const val CODE_PERM_SYSTEM_ALERT_WINDOW = 6111
        const val CODE_PERM_CAMERA = 6112

        fun getCurrentActivity(): SupportedActivity? {
            return MainActivity.currentActivity
        }
    }



    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(SUPPORTED_ACTIVITY_KEY)) {
            val supportedActivity = intent.getSerializableExtra(
                SUPPORTED_ACTIVITY_KEY) as SupportedActivity
            setDetectedActivity(supportedActivity)
        }
    }

    private fun showRationalDialog(activity: Activity) {
        AlertDialog.Builder(activity).apply {
            setTitle(R.string.permission_rational_dialog_title)
            setMessage(R.string.permission_rational_dialog_message)
            setPositiveButton(R.string.permission_rational_dialog_positive_button_text) { _, _ ->
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                    PERMISSION_REQUEST_ACTIVITY_RECOGNITION)
            }
            setNegativeButton(R.string.permission_rational_dialog_negative_button_text){ dialog, _ ->
                dialog.dismiss()
            }
        }.run {
            create()
            show()
        }
    }

    fun Activity.requestPermission() {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            // We don't have camera permission yet. Request it from the user.
            ActivityCompat.requestPermissions(this, arrayOf(permission), CODE_PERM_CAMERA)
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACTIVITY_RECOGNITION).not()) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.CAMERA
            ),
                PERMISSION_REQUEST_CUSTOM)
        } else {
            showRationalDialog(this)
        }
    }

    private fun isPermissionGranted(): Boolean {
        val isAndroidQOrLater: Boolean =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

        return if (isAndroidQOrLater.not()) {
            true
        } else {
            PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        }
    }

    @SuppressLint("MissingPermission") // TODO
    fun requestActivityTransitionUpdates() {
        val request = ActivityTransitionRequest(getActivitiesToTrack())
        val task = ActivityRecognitionClient(this).requestActivityTransitionUpdates(request,
            TransitionsReceiver.getPendingIntent(this))

        task.run {
            addOnSuccessListener {
                Log.d("TransitionUpdate", getString(R.string.transition_update_request_success))
            }
            addOnFailureListener {
                Log.d("TransitionUpdate", getString(R.string.transition_update_request_failed))
            }
        }
    }

    @SuppressLint("MissingPermission") // TODO
    private fun removeActivityTransitionUpdates() {
        val task = ActivityRecognitionClient(this).removeActivityTransitionUpdates(
            TransitionsReceiver.getPendingIntent(this))

        task.run {
            addOnSuccessListener {
                Log.d("TransitionUpdate", getString(R.string.transition_update_remove_success))
            }
            addOnFailureListener {
                Log.d("TransitionUpdate", getString(R.string.transition_update_remove_failed))
            }
        }
    }

    fun getActivitiesToTrack(): List<ActivityTransition> =
        mutableListOf<ActivityTransition>()
            .apply {
                add(ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build())
                add(ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build())
                add(ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.WALKING)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build())
                add(ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.WALKING)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build())
                add(ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.RUNNING)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build())
                add(ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.RUNNING)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build())
            }

    private fun setDetectedActivity(supportedActivity: SupportedActivity) {
//        currentActivity = supportedActivity
        MainActivity.Companion.currentActivity = supportedActivity
        Log.e("**********************", "Activity type is: "+supportedActivity.name)
    }
}
