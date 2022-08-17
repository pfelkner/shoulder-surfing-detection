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
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.math.MathUtils.clamp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.experimental.and
import kotlin.math.absoluteValue


/**
 * Copyright (c) 2019 by Roman Sisik. All rights reserved.
 */
class CamService: Service() {

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

    override fun onCreate() {
        super.onCreate()
        drawable = resources.getDrawable(R.drawable.ic_baseline_warning_24)
        dc = DataCollection(this)
        startForeground()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

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
//            val buffer: ByteBuffer = image!!.planes[0].buffer
//            val bytes = ByteArray(buffer.capacity())
//            buffer.get(bytes)
//            val bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//            imgPath = saveToInternalStorage(bitmapImage)
//            TODO IMPORTANT! figure this out, why is it not calculating orientation correctly?
            img = InputImage.fromMediaImage(image, 270)
            isProcessing = true
            captureSession!!.capture(captureRequest!!, captureCallback, null)
        }
        Log.d(TAG, "Image Dimensions: " + image?.width + " x " + image?.height)
        detectFaces(getFaceDetector(), image)
    }

    private fun detectFaces(faceDetector: FaceDetector, image: Image?) = runBlocking {
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
                    Log.e("########", "Faces detected: "+faces.size)
                    Log.e("########", "Mode: "+dc.getRingMode())
                    Log.e("########", "Date: "+dc.getDateTime())
                    if (dc.getCurrentActivity() != null)
                        Log.e("########", "Date: "+dc.getCurrentActivity())
                    isProcessing = false
                    Log.e("+++++++++", img.toString())
                    if (!isWarning && faces.size > 0) // TODO change to 1 for live version
                        startWarning(image)
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

    private fun startWarning(image: Image?) {
        val li = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        logAttackStart()
        when (alertMechanism) {
            AlertMechanism.WARNING_SIGN -> setupWarningView(li)
            AlertMechanism.FLASHING_BORDERS -> setupBorderView(li)
            AlertMechanism.ATTACKER_IMAGE -> setupAttackerView(li, image)
        }

        isWarning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm!!.addView(rootView, determineParams())
    }



    private fun stopWarning() {
        rootView?.setVisibility(View.GONE)
        isWarning = false
        logAttackEnd()
    }

    private fun logAttackEnd() {
        Log.e("++++++++++", "Atack End: "+ dc.getDateTime())
    }

    private fun logAttackStart() {
        Log.e("++++++++++", "Atack Start: "+ dc.getDateTime())
    }

    private fun setupBorderView(li: LayoutInflater) {
        rootView = li.inflate(R.layout.alert_borders, null)
    }

    private fun setupWarningView(li: LayoutInflater) {
        rootView = li.inflate(R.layout.alert_icon, null)
//        textureView = rootView?.findViewById(R.id.texPreview)
        imageView = rootView?.findViewById(R.id.alertIconImageView)
        imageView?.setImageDrawable(drawable)
    }


    private fun setupAttackerView(li: LayoutInflater, image: Image?) {
        rootView = li.inflate(R.layout.alert_image, null)
        if (image != null)
            imageView?.setImageBitmap(yuv420ToBitmap(image))
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

    fun yuv420ToBitmap(image: Image): Bitmap? {
        require(image.format == ImageFormat.YUV_420_888) { "Invalid image format" }
        val imageWidth = image.width
        val imageHeight = image.height
        // ARGB array needed by Bitmap static factory method I use below.
        val argbArray = IntArray(imageWidth * imageHeight)
        val yBuffer = image.planes[0].buffer
        yBuffer.position(0)

        // A YUV Image could be implemented with planar or semi planar layout.
        // A planar YUV image would have following structure:
        // YYYYYYYYYYYYYYYY
        // ................
        // UUUUUUUU
        // ........
        // VVVVVVVV
        // ........
        //
        // While a semi-planar YUV image would have layout like this:
        // YYYYYYYYYYYYYYYY
        // ................
        // UVUVUVUVUVUVUVUV   <-- Interleaved UV channel
        // ................
        // This is defined by row stride and pixel strides in the planes of the
        // image.

        // Plane 1 is always U & plane 2 is always V
        // https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
        val uBuffer = image.planes[1].buffer
        uBuffer.position(0)
        val vBuffer = image.planes[2].buffer
        vBuffer.position(0)

        // The U/V planes are guaranteed to have the same row stride and pixel
        // stride.
        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride
        var r: Int
        var g: Int
        var b: Int
        var yValue: Int
        var uValue: Int
        var vValue: Int
        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                val yIndex = y * yRowStride + x * yPixelStride
                // Y plane should have positive values belonging to [0...255]
                yValue = (yBuffer[yIndex] and 0xff.toByte()).toInt()
                val uvx = x / 2
                val uvy = y / 2
                // U/V Values are subsampled i.e. each pixel in U/V chanel in a
                // YUV_420 image act as chroma value for 4 neighbouring pixels
                val uvIndex = uvy * uvRowStride + uvx * uvPixelStride

                // U/V values ideally fall under [-0.5, 0.5] range. To fit them into
                // [0, 255] range they are scaled up and centered to 128.
                // Operation below brings U/V values to [-128, 127].
                uValue = (uBuffer[uvIndex] and 0xff.toByte()) - 128
                vValue = (vBuffer[uvIndex] and 0xff.toByte()) - 128

                // Compute RGB values per formula above.
                r = (yValue + 1.370705f * vValue).toInt()
                g = (yValue - 0.698001f * vValue - 0.337633f * uValue).toInt()
                b = (yValue + 1.732446f * uValue).toInt()
                r = clamp(r, 0, 255)
                g = clamp(g, 0, 255)
                b = clamp(b, 0, 255)

                // Use 255 for alpha value, no transparency. ARGB values are
                // positioned in each byte of a single 4 byte integer
                // [AAAAAAAARRRRRRRRGGGGGGGGBBBBBBBB]
                val argbIndex = y * imageWidth + x
                argbArray[argbIndex] =
                    255 shl 24 or (r and 255 shl 16) or (g and 255 shl 8) or (b and 255)
            }
        }
        return Bitmap.createBitmap(argbArray, imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
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