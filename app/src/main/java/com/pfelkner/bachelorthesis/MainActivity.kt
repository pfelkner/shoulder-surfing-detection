package com.pfelkner.bachelorthesis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.*
import com.pfelkner.bachelorthesis.util.ActivityTransitionsUtil
import com.pfelkner.bachelorthesis.util.Constants
import com.pfelkner.bachelorthesis.util.Constants.ALERT_MODE_SELECTION
import com.pfelkner.bachelorthesis.util.Constants.SNOOZE_DURATION
import com.pfelkner.bachelorthesis.util.Constants.SNOOZE_SELECTION
import kotlinx.android.synthetic.main.activity_main.*
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions


const val PERMISSION_REQUEST_ACTIVITY_RECOGNITION = 1000
const val PERMISSION_REQUEST_CUSTOM = 42069
lateinit var storage: SharedPreferences
class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {

    private var bound: Boolean = false
    private var camService : CamService? = null
    lateinit var client: ActivityRecognitionClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        initView()

        client = ActivityRecognition.getClient(this)
        storage = PreferenceManager.getDefaultSharedPreferences(this)
//        requestPermission()

        switchSnooze.isChecked = getSwitchState()
        switchSnooze.setOnCheckedChangeListener { _, isChecked ->
//            saveSwitchState(isChecked)
            if (isChecked && isServiceRunning(this, CamService::class.java)) {
                Log.e("HELP", ("'*'*'*"+ camService == null).toString())
                camService?.snooze()
                Handler().postDelayed({
                    switchSnooze.isChecked = false
                }, SNOOZE_DURATION.toLong())
            } else {
                showToast("Service isn't running")
                switchSnooze.isChecked = false
            }
            saveSwitchState(switchSnooze.isChecked)
        }

        val radiog = findViewById(R.id.radio) as RadioGroup
        radiog.check(getRadioState())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // We don't have camera permission yet. Request it from the user.
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CODE_PERM_CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && !ActivityTransitionsUtil.hasActivityTransitionPermissions(this)
        ) {
            requestActivityTransitionPermission()
        } else {
            requestForUpdates()
        }
        checkDrawOverlayPermission()
    }

    // TODO handle permissions in an uniform way
    fun checkDrawOverlayPermission() {
        /** check if we already  have permission to draw over other apps  */
        if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                !Settings.canDrawOverlays(this)
            } else {
                TODO("VERSION.SDK_INT < M")
            }
        ) {
            /** if not construct intent to request permission  */
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            /** request permission via start activity for result  */
            startActivityForResult(intent, 101010)
        }
    }

    override fun onResume() {
        super.onResume()

        registerReceiver(receiver, IntentFilter(CamService.ACTION_STOPPED))

        if (isServiceRunning(this, CamService::class.java)) {
            val bindIntent = Intent(this, CamService::class.java)
            bindService(bindIntent, mConnection, BIND_AUTO_CREATE)
        }

        val running = isServiceRunning(this, CamService::class.java)
        flipButtonVisibility(running)
    }

    override fun onPause() {
        super.onPause()
        saveRadioState(getSelectedRadio())
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
//        removeActivityTransitionUpdates()
        saveRadioState(getSelectedRadio())
        super.onDestroy()
    }
//
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//
//        when (requestCode) {
//            CODE_PERM_CAMERA -> {
//                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
//                    Toast.makeText(this, getString(R.string.err_no_cam_permission), Toast.LENGTH_LONG).show()
//                    finish()
//                }
//            }
//        }
//    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
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
            sendFakeActivityTransitionEvent()
        }

        butStop.setOnClickListener {
            stopService(Intent(this, CamService::class.java))
            if (bound) {
                unbindService(mConnection)
                bound = false
            }
        }
    }

    private fun notifyService(action: String) {
        val startIntent = Intent(this, CamService::class.java)
        startIntent.action = action
        startIntent.putExtra("selectedAlert", getSelectedRadio())
        startService(startIntent)
        val bindIntent = Intent(this, CamService::class.java)
        bindService(bindIntent, mConnection, BIND_AUTO_CREATE)
    }

    var mConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceDisconnected(name: ComponentName) {
//            showToast("Service is disconnected")
            bound = false
            camService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
//            showToast("Service is connected")
            bound = true
            val mLocalBinder: CamService.LocalBinder = service as CamService.LocalBinder
            camService = mLocalBinder.getCamService()
        }
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

        const val CODE_PERM_SYSTEM_ALERT_WINDOW = 6111
        const val CODE_PERM_CAMERA = 6112
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

//    ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            AppSettingsDialog.Builder(this).build().show()
        } else {
            requestActivityTransitionPermission()
        }
    }

    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
//        switchActivityTransition.isChecked = true
//        saveRadioState(true)
        requestForUpdates()
    }

// moved up to compare to other permission request
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
//    }

    @SuppressLint("MissingPermission") // TODO
    private fun requestForUpdates() {
        client
            .requestActivityTransitionUpdates(
                ActivityTransitionsUtil.getActivityTransitionRequest(),
                getPendingIntent()
            )
            .addOnSuccessListener {
                showToast("successful registration")
            }
            .addOnFailureListener { e: Exception ->
                showToast("Unsuccessful registration")
            }
    }

    @SuppressLint("MissingPermission") // TODO
    private fun deregisterForUpdates() {
        client
            .removeActivityTransitionUpdates(getPendingIntent())
            .addOnSuccessListener {
                getPendingIntent().cancel()
                showToast("successful deregistration")
            }
            .addOnFailureListener { e: Exception ->
                showToast("unsuccessful deregistration")
            }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestActivityTransitionPermission() {
        EasyPermissions.requestPermissions(
            this,
            "You need to allow activity transition permissions in order to use this feature",
            Constants.REQUEST_CODE_ACTIVITY_TRANSITION,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            Constants.REQUEST_CODE_INTENT_ACTIVITY_TRANSITION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG)
            .show()
    }

    private fun saveRadioState(id: Int) {
        storage
            .edit()
            .putInt(ALERT_MODE_SELECTION, id)
            .apply()
    }

    private fun getRadioState() = storage.getInt(ALERT_MODE_SELECTION, getSelectedRadio())

    fun saveSwitchState(state: Boolean) {
        storage
            .edit()
            .putBoolean(Constants.SNOOZE_SELECTION, state)
            .apply()
    }

    private fun getSwitchState() = storage.getBoolean(SNOOZE_SELECTION, false)

    fun onRadioButtonClicked(view: View) {
        if (view is RadioButton) {
            saveRadioState(view.id)
        }
    }

    fun sendFakeActivityTransitionEvent() {
        // name your intended recipient class
        val intent = Intent(this, ActivityTransitionReceiver::class.java)

        val events: ArrayList<ActivityTransitionEvent> = arrayListOf()

        // create fake events
        events.add(
            ActivityTransitionEvent(
                DetectedActivity.ON_BICYCLE,
                ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                SystemClock.elapsedRealtimeNanos()
            )
        )

        // finally, serialize and send
        val result = ActivityTransitionResult(events)
        SafeParcelableSerializer.serializeToIntentExtra(
            result,
            intent,
            "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT"
        )
        this.sendBroadcast(intent)
    }
}
