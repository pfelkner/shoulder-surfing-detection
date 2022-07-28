package eu.sisik.backgroundcam

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.absoluteValue


/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
class CamService: Service() {

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

    private var isProcessing: Boolean = false
    private var isWarning: Boolean = false
    private var windowManager: WindowManager? = null

    // You can start service in 2 modes - 1.) with preview 2.) without preview (only bg processing)
    private var shouldShowPreview = true

    private fun getFaceDetector(): FaceDetector {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f) //.enableTracking()
            .build()
        return FaceDetection.getClient(options)
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

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            initCam(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }


    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        if(isProcessing){
//            captureSession!!.stopRepeating()
            captureSession!!.abortCaptures()
        }
        if (windowManager == null) windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        var image: Image? = null
        if (!isProcessing) {
            image = reader!!.acquireLatestImage()!!
//            TODO IMPORTANT! figure this out, why is it not calculating orientation correctly?
            img = InputImage.fromMediaImage(image, 270)
//            img = InputImage.fromMediaImage(image!!, getRotationCompensation(windowManager))
            isProcessing = true
            captureSession!!.capture(captureRequest!!, captureCallback, null)
//            captureSession!!.setRepeatingRequest(captureRequest!!, captureCallback, null)
        }
        Log.d(TAG, "Got image: " + image?.width + " x " + image?.height)
        countFaces(getFaceDetector(), image)
    }


    private fun countFaces(faceDetector: FaceDetector, image: Image?) = runBlocking {
        launch {
//            val faceDetector = FaceDetection.getClient()
            Log.e("IMG", "IMG: "+ img.rotationDegrees)
            faceDetector.process(img)
                .addOnSuccessListener { faces ->
                    // Task completed successfully
                    // ...
                    Log.e("########FACES", "Faces detected: "+faces.size)
                    isProcessing = false
                    Log.e("+++++++++", img.toString())
                    if (!isWarning && faces.size > 0)
                        initOverlay()
                }
                .addOnFailureListener { e ->
                    Log.e("+++++++++Cause", e.cause.toString())
                    e.printStackTrace()
//                    TODO figure out if image has to be closed or onComplete is called after fail
                    isProcessing = false
                }
                .addOnCompleteListener{
                    if (captureSession != null && captureRequest != null)
                    captureSession!!.capture(captureRequest!!, captureCallback, null)
                    image?.close()
                }
            }
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


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when(intent?.action) {
            ACTION_START -> start()

            ACTION_START_WITH_PREVIEW -> startWithPreview()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        drawable = resources.getDrawable(R.drawable.ic_baseline_warning_24)
        startForeground()
    }

    override fun onDestroy() {
        super.onDestroy()

        stopCamera()

        if (rootView != null)
            wm?.removeView(rootView)

        sendBroadcast(Intent(ACTION_STOPPED))
    }

    private fun start() {

        shouldShowPreview = false

        initCam(1080, 1080)
//        initCam(320, 200)
    }

    private fun startWithPreview() {

        shouldShowPreview = true

        // Initialize view drawn over other apps
        initOverlay()

        // Initialize camera here if texture view already initialized
        if (textureView!!.isAvailable)
            initCam(textureView!!.width, textureView!!.height)
        else
            textureView!!.surfaceTextureListener = surfaceTextureListener
    }

    private fun initOverlay() {
        setupView()

        isWarning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm!!.addView(rootView, determineParams())
        Handler().postDelayed({
            imageView?.setImageDrawable(null)
            isWarning = false
       }, 5000)
    }

    private fun setupView() {
        val li = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        rootView = li.inflate(R.layout.overlay, null)
        textureView = rootView?.findViewById(R.id.texPreview)
        imageView = rootView?.findViewById(R.id.simpleImageView)
        imageView?.setImageDrawable(drawable)
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

    @SuppressLint("MissingPermission")
    private fun initCam(width: Int, height: Int) {

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var camId: String? = getFronFacingCamId()

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

    private fun createCaptureSession() {
        try {
            // Prepare surfaces we want to use in capture session
            val targetSurfaces = ArrayList<Surface>()

            // Prepare CaptureRequest that can be used with CameraCaptureSession
            val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
//            val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {

                if (shouldShowPreview) {
                    val texture = textureView!!.surfaceTexture!!
                    texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
                    val previewSurface = Surface(texture)

                    targetSurfaces.add(previewSurface)
                    addTarget(previewSurface)
                }

                // Configure target surface for background processing (ImageReader)
                imageReader = ImageReader.newInstance(
                    previewSize!!.width, previewSize!!.height,
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

    private val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 0)
        ORIENTATIONS.append(Surface.ROTATION_90, 90)
        ORIENTATIONS.append(Surface.ROTATION_180, 180)
        ORIENTATIONS.append(Surface.ROTATION_270, 270)
    }

    /**
     * Get the angle by which an image must be rotated given the device's current
     * orientation.
     */
//    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//    @Throws(CameraAccessException::class)
    private fun getRotationCompensation(wm: WindowManager?): Int {
        val deviceRotation = wm?.defaultDisplay?.rotation
        var rotationCompensation = ORIENTATIONS[deviceRotation!!]

        val sensorOrientation = cameraManager
            ?.getCameraCharacteristics(CameraCharacteristics.LENS_FACING_FRONT.toString())
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

        return (sensorOrientation + (-rotationCompensation)) % 360

    }

    companion object {

        const val TAG = "CamService"

        const val ACTION_START = "eu.sisik.backgroundcam.action.START"
        const val ACTION_START_WITH_PREVIEW = "eu.sisik.backgroundcam.action.START_WITH_PREVIEW"
        const val ACTION_STOPPED = "eu.sisik.backgroundcam.action.STOPPED"

        const val ONGOING_NOTIFICATION_ID = 6660
        const val CHANNEL_ID = "cam_service_channel_id"
        const val CHANNEL_NAME = "cam_service_channel_name"

    }
}