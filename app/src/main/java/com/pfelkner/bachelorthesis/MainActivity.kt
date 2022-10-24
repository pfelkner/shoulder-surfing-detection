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
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.pfelkner.bachelorthesis.util.ActivityTransitionsUtil
import com.pfelkner.bachelorthesis.util.Constants
import com.pfelkner.bachelorthesis.util.Constants.ALERT_MODE_SELECTION
import com.pfelkner.bachelorthesis.util.Constants.SNOOZE_SELECTION
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.confirm_questionaire.*
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions


const val PERMISSION_REQUEST_ACTIVITY_RECOGNITION = 1000
const val PERMISSION_REQUEST_CUSTOM = 42069
lateinit var storage: SharedPreferences
class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {

    // TODO
    // 2. activity manager ausprobieren

    private var bound: Boolean = false
    private var camService : CamService? = null
    lateinit var client: ActivityRecognitionClient
    private lateinit var dc: DataCollection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        storage = PreferenceManager.getDefaultSharedPreferences(this)
        client = ActivityRecognition.getClient(this)
        switchSnooze.isChecked = getSwitchState()
        dc = DataCollection(this)
        initView()

//        requestPermission()

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

        checkEligableForUse(dc.getAlertMethod().id)
    }

    private fun checkEligableForUse(id: Int) {
        when (id) {
            1 -> return
            2 -> check(1, Constants.CODE_Q1)
            3 -> check(2, Constants.CODE_Q2)
        }
    }

    private fun check(id: Int, qCode : Int) {
        if (getQuestionaireState(id))
            return

        setContentView(R.layout.confirm_questionaire)
        val code = findViewById(R.id.questionaireCode) as EditText
        confirm_questionaire.setOnClickListener{
            if (code.text.toString() == qCode.toString()) {
                confirmQuestionaireState(id)
//                finish()
                setContentView(R.layout.activity_main)
            }
        }
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
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        deregisterForUpdates()
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
            }

            butStop.setOnClickListener {
                stopService(Intent(this, CamService::class.java))
                switchSnooze.isChecked = false
                saveSwitchState(switchSnooze.isChecked)
                if (bound) {
                    unbindService(mConnection)
                    bound = false
                }
            }

            switchSnooze.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && isServiceRunning(this, CamService::class.java)) {
                    camService?.snooze()
                } else {
                    showToast("Service isn't running")
                    switchSnooze.isChecked = false
                    camService?.stopSnooze()
                }
                saveSwitchState(switchSnooze.isChecked)
            }
    }

    private fun eligableForUse(): Boolean {
        return true
    }

    private fun notifyService(action: String) {
        val startIntent = Intent(this, CamService::class.java)
        startIntent.action = action
        startService(startIntent)
        val bindIntent = Intent(this, CamService::class.java)
        bindService(bindIntent, mConnection, BIND_AUTO_CREATE)
    }

    var mConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            camService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bound = true
            val mLocalBinder: CamService.LocalBinder = service as CamService.LocalBinder
            camService = mLocalBinder.getCamService()
        }
    }


    private fun flipButtonVisibility(running: Boolean) {
        butStart.visibility =  if (running) View.GONE else View.VISIBLE
        butStop.visibility =  if (running) View.VISIBLE else View.GONE
        switchSnooze.visibility = if (running) View.VISIBLE else View.GONE
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
        requestForUpdates()
    }


    @SuppressLint("MissingPermission") // TODO
    private fun requestForUpdates() {
        client
            .requestActivityTransitionUpdates(
                ActivityTransitionsUtil.getActivityTransitionRequest(),
                getPendingIntent()
            )
            .addOnSuccessListener {
                Log.i("Acitvity Detection", "successful registration")
            }
            .addOnFailureListener { e: Exception ->
                Log.i("Acitvity Detection", "Unsuccessful registrationn")
            }
    }

    @SuppressLint("MissingPermission") // TODO
    private fun deregisterForUpdates() {
        client
            .removeActivityTransitionUpdates(getPendingIntent())
            .addOnSuccessListener {
                getPendingIntent().cancel()
                Log.i("Acitvity Detection", "successful deregistration")
            }
            .addOnFailureListener { e: Exception ->
                Log.i("Acitvity Detection", "unsuccessful deregistration")
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

    fun saveSwitchState(state: Boolean) {
        storage
            .edit()
            .putBoolean(Constants.SNOOZE_SELECTION, state)
            .apply()
    }

    fun confirmQuestionaireState(id: Int) {
        storage
            .edit()
            .putBoolean("QUESTIONAIRE_"+id, true)
            .apply()
    }

    fun getQuestionaireState(id : Int): Boolean {
        return storage.getBoolean("QUESTIONAIRE_"+id, false)
    }

    private fun getSwitchState() = storage.getBoolean(SNOOZE_SELECTION, false)

    fun onRadioButtonClicked(view: View) {
        if (view is RadioButton) {
            saveRadioState(view.id)
        }
    }
}
