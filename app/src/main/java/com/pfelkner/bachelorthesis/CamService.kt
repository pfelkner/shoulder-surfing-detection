package com.pfelkner.bachelorthesis

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.renderscript.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.pfelkner.bachelorthesis.util.Constants
import io.karn.notify.Notify
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import kotlin.math.absoluteValue


class CamService: Service() {

    private lateinit var rs: RenderScript
    private var snoozeStart: Long? = null
    private var snoozing: Boolean = false
    private lateinit var dc: DataCollection
    private lateinit var alertMechanism: AlertMechanism

    // UI
    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var textureView: TextureView? = null
    private var imageView: ImageView? = null
    private lateinit var drawable: Drawable

    // Camera2-related stuff
    private var cameraManager: CameraManager? = null
    private var previewSize: Size? = null
    private var cameraDevice: CameraDevice? = null
    private var captureRequest: CaptureRequest? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var img: InputImage
    private var imgPath: String? = null

    private var isProcessing: Boolean = false
    private var isWarning: Boolean = false
    private var windowManager: WindowManager? = null

    var mBinder: IBinder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        return mBinder
    }

    inner class LocalBinder : Binder() {
        fun getCamService() : CamService {
            return this@CamService
        }
    }

    override fun onCreate() {
        super.onCreate()
        drawable = resources.getDrawable(R.drawable.ic_baseline_warning_24)
        dc = DataCollection(this)
        dc.setInstallationId()
        rs = RenderScript.create(this)
        startForeground()
    }

//    override fun onBind(p0: Intent?): IBinder? {
//        return null
//    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent != null)
        // Default value is the id of pre selected radio button
            Log.e("RADIO ID", "Button id is: "+intent.getIntExtra("selectedAlert", 2131230938))
        if (intent != null) {
            alertMechanism = AlertMechanism.fromInt(intent.getIntExtra("selectedAlert", 2131230938))
        }
        when(intent?.action) {
            ACTION_START -> initCam(320, 200)
//            ACTION_START -> initCam(1080, 1080) TODO figure out what size is best for perfomrance while keeping accuracy
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Toast.makeText(this, "Amount of Logs: " + dc.entries.size, Toast.LENGTH_LONG)
            .show()
        stopCamera()
        if (rootView != null)
            wm?.removeView(rootView)
        sendBroadcast(Intent(ACTION_STOPPED))
    }

    private fun startForeground() {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }
        val notification = createNotification(pendingIntent)
        startForeground(ONGOING_NOTIFICATION_ID, notification)
    }

    private fun createNotification(intent: PendingIntent): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE)
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getText(R.string.app_name))
            .setContentText(getText(R.string.app_name))
            .setSmallIcon(R.drawable.ic_baseline_notifications_24)
            .setContentIntent(intent)
            .setTicker(getText(R.string.app_name))
            .build()
    }

    private fun stopCamera() {
        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun initCam(width: Int, height: Int) {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId: String? = getFronFacingCamId()
        previewSize = chooseSupportedSize(camId!!, width, height)
        // No Permission check required, done from the main activity
        cameraManager!!.openCamera(camId, stateCallback, null)
    }

    // TODO intruduce error handling here
    //Dont get confused with the crossover of cam ID and facing direction, its working!
    private fun getFronFacingCamId(): String? {
        this.cameraManager!!.cameraIdList.forEach { id ->
            val facing = this.cameraManager!!.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        return null
    }

    private fun chooseSupportedSize(camId: String, textureViewWidth: Int, textureViewHeight: Int): Size {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // Get all supported sizes for TextureView
        val characteristics = manager.getCameraCharacteristics(camId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(SurfaceTexture::class.java)
        // We want to find something near the size of our TextureView
        val texViewArea = textureViewWidth * textureViewHeight
        val texViewAspect = textureViewWidth.toFloat()/textureViewHeight.toFloat()
        val nearestToFurthestSz = supportedSizes?.sortedWith(compareBy(
            // First find something with similar aspect
            {
                val aspect = if (it.width < it.height) it.width.toFloat() / it.height.toFloat()
                else it.height.toFloat()/it.width.toFloat()
                (aspect - texViewAspect).absoluteValue
            },
            // Also try to get similar resolution
            {
                (texViewArea - it.width * it.height).absoluteValue
            }
        ))

        if (nearestToFurthestSz != null) {
            if (nearestToFurthestSz.isNotEmpty())
                return nearestToFurthestSz[0]
        }
        return Size(320, 200)
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(currentCameraDevice: CameraDevice) {
            cameraDevice = currentCameraDevice
            createCaptureSession()
        }
        override fun onDisconnected(currentCameraDevice: CameraDevice) {
            currentCameraDevice.close()
            cameraDevice = null
        }
        override fun onError(currentCameraDevice: CameraDevice, error: Int) {
            currentCameraDevice.close()
            cameraDevice = null
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {}
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {}
    }

    private fun createCaptureSession() {
        try {
            // Prepare surfaces we want to use in capture session
            val targetSurfaces = ArrayList<Surface>()
            // Prepare CaptureRequest that can be used with CameraCaptureSession
            val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
                // Configure target surface for background processing (ImageReader)
                imageReader = ImageReader.newInstance(
                    previewSize!!.width, previewSize!!.height,
//                    ImageFormat.JPEG, 15
                    ImageFormat.YUV_420_888, 15
                )
                Log.e("ImageReader", "Width: "+ previewSize!!.width+ "Height: " + previewSize!!.height)
                imageReader!!.setOnImageAvailableListener(imageListener, null)
                targetSurfaces.add(imageReader!!.surface)
                addTarget(imageReader!!.surface)
                // Set some additional parameters for the request
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
            // Prepare CameraCaptureSession
            cameraDevice!!.createCaptureSession(targetSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (null == cameraDevice) {
                            return
                        }
                        captureSession = cameraCaptureSession
                        try {
                            // Now we can start capturing
                            captureRequest = requestBuilder.build()
                            captureSession!!.capture(captureRequest!!, captureCallback, null)

                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "createCaptureSession", e)
                        }
                    }
                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Log.e(TAG, "createCaptureSession()")
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession", e)
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        if(isProcessing){
            captureSession!!.abortCaptures()
        }
        if (windowManager == null) windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        var image: Image? = null
        if (!isProcessing) {
            image = reader!!.acquireLatestImage()!!
//            TODO IMPORTANT! figure this out, why is it not calculating orientation correctly?
            img = InputImage.fromMediaImage(image, 270)
            isProcessing = true
            captureSession!!.capture(captureRequest!!, captureCallback, null)
        }
        detectFaces(getFaceDetector(), image)
        if (snoozing)
            checkSnooze()
    }

    private fun detectFaces(faceDetector: FaceDetector, image: Image?) = runBlocking {
        launch {
            Log.e("IMG", "IMG: "+ img.rotationDegrees)
            faceDetector.process(img)
                .addOnSuccessListener { faces ->
                    isProcessing = false
                    if (!isWarning && faces.size > 0) // TODO change to 1 for live version
                        startWarning(image)
                    if (isWarning && faces.size == 0 || isSnoozing()) // TODO change to 1 for live version
                        stopWarning()

                }
                .addOnFailureListener { e ->
                    Log.e("Face detection onFail: ", e.cause.toString())
                    e.printStackTrace()
                    isProcessing = false
                }
                .addOnCompleteListener{
                    if (captureSession != null && captureRequest != null)
                        captureSession!!.capture(captureRequest!!, captureCallback, null)
                    image?.close()
                }
        }
    }

    private fun getFaceDetector(): FaceDetector {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f) //.enableTracking()
            .build()
        return FaceDetection.getClient(options)
    }

    private fun startWarning(image: Image?) {
        dc.getUserActivity()
        dc.logEvent(DataCollection.Trigger.ATTACK_DETECTED, alertMechanism, snoozing)
        val li = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        if (!snoozing) {
            when (alertMechanism) {
                AlertMechanism.WARNING_SIGN -> setupWarningView(li)
                AlertMechanism.FLASHING_BORDERS -> setupBorderView(li)
                AlertMechanism.ATTACKER_IMAGE -> setupAttackerView(li, image)
            }
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm!!.addView(rootView, determineParams())
        }
        isWarning = true
    }

    private fun stopWarning() {
        dc.logEvent(DataCollection.Trigger.DETECTION_END, alertMechanism, snoozing)
        Handler().postDelayed({
            rootView?.setVisibility(View.GONE)
            isWarning = false
        }, 1000.toLong())
    }

    private fun checkSnooze() {
        val time = snoozeStart?.minus(System.currentTimeMillis())
        if (time != null && time.absoluteValue >= 10000) {
            Notify
                .with(this)
                .content {
                    title = "You are snoozing"
                    text =
                        "Please consider to disable snoozing"
                }
                .show(id = Constants.SNOOZE_NOTIFICATION_ID)
            snoozeStart = System.currentTimeMillis()
        }
    }

    fun snooze() {
        snoozing = true
        if (snoozeStart == null)
            snoozeStart = System.currentTimeMillis()
        dc.logEvent(DataCollection.Trigger.SNOOZED, alertMechanism, snoozing)
        if (rootView != null)
            rootView?.visibility = View.GONE
    }

    fun stopSnooze() {
        snoozing = false
        snoozeStart = null
    }

    private fun isSnoozing(): Boolean {
        return snoozing == true
    }

    private fun setupBorderView(li: LayoutInflater) {
        rootView = li.inflate(R.layout.alert_borders, null)
    }

    private fun setupWarningView(li: LayoutInflater) {
        rootView = li.inflate(R.layout.alert_icon, null)
        imageView = rootView?.findViewById(R.id.alertIconImageView)
        imageView?.setImageDrawable(drawable)
    }

    private fun setupAttackerView(li: LayoutInflater, image: Image?) {
        rootView = li.inflate(R.layout.alert_image, null)
        imageView = rootView?.findViewById(R.id.attackerImageView)
        imageView?.rotation = 270F
        if (image != null)
            imageView?.setImageBitmap(imageToBitmap(image, image.width, image.height))
    }

    private fun determineParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        return WindowManager.LayoutParams(
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
    }

//    needs more fixing from here on
//    private val ORIENTATIONS = SparseIntArray()
//    init {
//        ORIENTATIONS.append(Surface.ROTATION_0, 0)
//        ORIENTATIONS.append(Surface.ROTATION_90, 90)
//        ORIENTATIONS.append(Surface.ROTATION_180, 180)
//        ORIENTATIONS.append(Surface.ROTATION_270, 270)
//    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        val planes = image.planes
        var buffer: ByteBuffer = planes[0].buffer
        val y = ByteArray(buffer.remaining())
        buffer.get(y)
        buffer = planes[1].buffer
        val u = ByteArray(buffer.remaining())
        buffer.get(u)
        buffer = planes[2].buffer
        val v = ByteArray(buffer.remaining())
        buffer.get(v)

        val yRowStride = planes[0].rowStride
        val uvRowStride =
            planes[1].rowStride
        val uvPixelStride =
            planes[1].pixelStride
        val mYuv420 = ScriptC_yuv420888(rs)

        val typeUcharY: Type.Builder = Type.Builder(rs, Element.U8(rs))

        typeUcharY.setX(yRowStride).setY(y.size / yRowStride)
        val yAlloc = Allocation.createTyped(rs, typeUcharY.create())
        yAlloc.copyFrom(y)
        mYuv420.set_ypsIn(yAlloc)
        val typeUcharUV: Type.Builder = Type.Builder(rs, Element.U8(rs))

        typeUcharUV.setX(u.size)
        val uAlloc = Allocation.createTyped(rs, typeUcharUV.create())
        uAlloc.copyFrom(u)
        mYuv420.set_uIn(uAlloc)
        val vAlloc = Allocation.createTyped(rs, typeUcharUV.create())
        vAlloc.copyFrom(v)
        mYuv420.set_vIn(vAlloc)

        mYuv420.set_picWidth(width.toLong())
        mYuv420.set_uvRowStride(uvRowStride.toLong())
        mYuv420.set_uvPixelStride(uvPixelStride.toLong())
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outAlloc = Allocation.createFromBitmap(
            rs,
            outBitmap,
            Allocation.MipmapControl.MIPMAP_NONE,
            Allocation.USAGE_SCRIPT
        )
        val lo = Script.LaunchOptions()
        lo.setX(0, width)
        lo.setY(0, y.size / yRowStride)
        mYuv420.forEach_doConvert(outAlloc, lo)
        outAlloc.copyTo(outBitmap)
        return outBitmap
    }



    companion object {

        const val TAG = "CamService"

        const val ACTION_START = "eu.sisik.backgroundcam.action.START"
        const val ACTION_STOPPED = "eu.sisik.backgroundcam.action.STOPPED"

        const val ONGOING_NOTIFICATION_ID = 6660
        const val CHANNEL_ID = "cam_service_channel_id"
        const val CHANNEL_NAME = "cam_service_channel_name"

    }
}