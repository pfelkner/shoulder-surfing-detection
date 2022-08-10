package eu.sisik.backgroundcam

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
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

    override fun onCreate() {
        super.onCreate()
        drawable = resources.getDrawable(R.drawable.ic_baseline_warning_24)
        startForeground()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent != null)
        //Default value is the id of pre selected radio button
            alertMechanism = AlertMechanism.fromInt(intent?.getIntExtra("selectedAlert", 2131231044))
        when(intent?.action) {
            ACTION_START -> initCam(320, 200)
//            ACTION_START -> initCam(1080, 1080) TODO figure out what size is best for perfomrance while keeping accuracy
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
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
//            val buffer: ByteBuffer = image!!.planes[0].buffer
//            val bytes = ByteArray(buffer.capacity())
//            buffer.get(bytes)
//            val bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//            imgPath = saveToInternalStorage(bitmapImage)
//            TODO IMPORTANT! figure this out, why is it not calculating orientation correctly?
            img = InputImage.fromMediaImage(image, 270)
//            img = InputImage.fromMediaImage(image!!, getRotationCompensation(windowManager))
            isProcessing = true
            captureSession!!.capture(captureRequest!!, captureCallback, null)
        }
        Log.d(TAG, "Image Dimensions: " + image?.width + " x " + image?.height)
        detectFaces(getFaceDetector(), image)
    }

    private fun detectFaces(faceDetector: FaceDetector, image: Image?) = runBlocking {
//        val buffer: ByteBuffer = image!!.planes[0].buffer
//        val bytes = ByteArray(buffer.capacity())
//        buffer.get(bytes)
//        val bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
//        imgPath = saveToInternalStorage(bitmapImage)
        launch {
//            if (image != null) {
//                val buffer: ByteBuffer = image!!.planes[0].buffer
//                val bytes = ByteArray(buffer.capacity()-1)
//                buffer.get(bytes)
////                val image_data = convertYUV420888ToNV21(image)
//                val bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//                delay(1000L)
//                if (bitmapImage != null)
//                    imgPath = saveToInternalStorage(bitmapImage)
//            }
//            val faceDetector = FaceDetection.getClient()
            Log.e("IMG", "IMG: "+ img.rotationDegrees)
            faceDetector.process(img)
                .addOnSuccessListener { faces ->
                    // Task completed successfully
                    // ...
                    Log.e("########FACES", "Faces detected: "+faces.size)
                    isProcessing = false
                    Log.e("+++++++++", img.toString())
                    if (!isWarning && faces.size > 0) // TODO change to 1 for live version
                        startWarning()
                    if (isWarning && faces.size == 0) { // TODO change to 1 for live version
                        stopWarning()
                    }
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

    private fun startWarning() {
        val li = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        when (alertMechanism) {
            AlertMechanism.WARNING_SIGN -> setupWarningView(li)
            AlertMechanism.FLASHING_BORDERS -> setupBorderView(li)
            AlertMechanism.ATTACKER_IMAGE -> null;
        }

        isWarning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm!!.addView(rootView, determineParams())
    }

    private fun stopWarning() {
        rootView?.setVisibility(View.GONE)
        isWarning = false
    }

    private fun setupBorderView(li: LayoutInflater) {
        rootView = li.inflate(R.layout.alert_borders, null)
    }

    private fun setupWarningView(li: LayoutInflater) {
        rootView = li.inflate(R.layout.alert_icon, null)
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

//    needs more fixing from here on

//    private fun saveToInternalStorage(bitmapImage: Bitmap): String {
//        val cw = ContextWrapper(applicationContext)
//        // path to /data/data/yourapp/app_data/imageDir
//        val directory: File = cw.getDir("imageDir", MODE_PRIVATE)
//        // Create imageDir
//        val mypath = File(directory, "profile.jpg")
//        var fos: FileOutputStream? = null
//        try {
//            fos = FileOutputStream(mypath)
//            // Use the compress method on the BitMap object to write image to the OutputStream
//            bitmapImage.compress(Bitmap.CompressFormat.PNG, 100, fos)
//        } catch (e: java.lang.Exception) {
//            e.printStackTrace()
//        } finally {
//            try {
//                if (fos != null) {
//                    fos.close()
//                }
//            } catch (e: IOException) {
//                e.printStackTrace()
//            }
//        }
//        Log.e("####++++++++++", "Storing to: "+ directory.absolutePath)
//        return directory.getAbsolutePath()
//    }
//
//    private fun loadImageFromStorage(path: String) {
//        try {
//            val f = File(path, "profile.jpg")
//            val b = BitmapFactory.decodeStream(FileInputStream(f))
////            val imgToStore = rootView?.findViewById(R.id.i) as ImageView
//            imageView?.setImageBitmap(b)
////            imgToStore.setImageBitmap(b)
//        } catch (e: FileNotFoundException) {
//            e.printStackTrace()
//        }
//    }
//
//    private fun convertYUV420888ToNV21(imgYUV420: Image): ByteArray {
//// Converting YUV_420_888 data to YUV_420_SP (NV21).
//        val data: ByteArray
//        val buffer0 = imgYUV420.planes[0].buffer
//        val buffer2 = imgYUV420.planes[2].buffer
//        val buffer0_size = buffer0.remaining()
//        val buffer2_size = buffer2.remaining()
//        data = ByteArray(buffer0_size + buffer2_size)
//        buffer0[data, 0, buffer0_size]
//        buffer2[data, buffer0_size, buffer2_size]
//        return data
//    }


//    private val ORIENTATIONS = SparseIntArray()
//    init {
//        ORIENTATIONS.append(Surface.ROTATION_0, 0)
//        ORIENTATIONS.append(Surface.ROTATION_90, 90)
//        ORIENTATIONS.append(Surface.ROTATION_180, 180)
//        ORIENTATIONS.append(Surface.ROTATION_270, 270)
//    }

    /**
     * Get the angle by which an image must be rotated given the device's current
     * orientation.
     */
//    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//    @Throws(CameraAccessException::class)
//    private fun getRotationCompensation(wm: WindowManager?): Int {
//        val deviceRotation = wm?.defaultDisplay?.rotation
//        var rotationCompensation = ORIENTATIONS[deviceRotation!!]
//
//        val sensorOrientation = cameraManager
//            ?.getCameraCharacteristics(CameraCharacteristics.LENS_FACING_FRONT.toString())
//            ?.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
//
//        return (sensorOrientation + (-rotationCompensation)) % 360
//
//    }

    companion object {

        const val TAG = "CamService"

        const val ACTION_START = "eu.sisik.backgroundcam.action.START"
        const val ACTION_STOPPED = "eu.sisik.backgroundcam.action.STOPPED"

        const val ONGOING_NOTIFICATION_ID = 6660
        const val CHANNEL_ID = "cam_service_channel_id"
        const val CHANNEL_NAME = "cam_service_channel_name"

    }
}