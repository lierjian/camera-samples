package com.example.android.camera2.basic.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.AutoFitSurfaceView
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera2.basic.R
import com.example.android.camera2.basic.databinding.FragmentMultiCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MultiCameraPreviewFragment: Fragment() {
    /** Android ViewBinding */
    private var _fragmentMultiCameraBinding: FragmentMultiCameraBinding? = null

    private val fragmentMultiCameraBinding get() = _fragmentMultiCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: MultiCameraPreviewFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics1: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId1)
    }

    private val characteristics2: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId2)
    }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera1: CameraDevice

    private lateinit var camera2: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentMultiCameraBinding = FragmentMultiCameraBinding.inflate(inflater, container, false)
        return fragmentMultiCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentMultiCameraBinding.preview1.holder.addCallback(SurfaceCallback(args.cameraId1))
        fragmentMultiCameraBinding.preview2.holder.addCallback(SurfaceCallback(args.cameraId2))

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics1).apply {
            observe(viewLifecycleOwner, { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    inner class SurfaceCallback(private val cameraId: String): SurfaceHolder.Callback{

        val preview = when (cameraId.equals(args.cameraId1)) {
            true->fragmentMultiCameraBinding.preview1
            false->fragmentMultiCameraBinding.preview2
        }
        val characteristics = when (cameraId.equals(args.cameraId1)) {
            true->characteristics1
            false->characteristics2
        }
        override fun surfaceCreated(holder: SurfaceHolder?) {
            Log.i(TAG, "surfaceCreated, $holder")
            // Selects appropriate preview size and configures view finder
            val previewSize = getPreviewOutputSize(
                preview.display,
                characteristics,
                SurfaceHolder::class.java
            )
            Log.d(TAG, "preview$cameraId view size: ${preview.width} x ${preview.height}")
            Log.d(TAG, "Selected preview size: $previewSize")
            preview.setAspectRatio(
                previewSize.width,
                previewSize.height
            )
            preview.setScaleType(AutoFitSurfaceView.ScaleType.CENTER_INSIDE)

            // To ensure that size is set, initialize camera in the view's thread
            view?.post { initializeCamera(cameraId) }

        }

        override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            Log.i(TAG, "surfaceChanged")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder?) {
            Log.i(TAG, "surfaceDestroyed, $holder")
        }

    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera(cameraId: String) = lifecycleScope.launch(Dispatchers.Main) {
        // Open the selected camera
        val camera = openCamera(cameraManager, cameraId, cameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val surface = when (cameraId.equals(args.cameraId1)) {
            true->fragmentMultiCameraBinding.preview1.holder.surface
            false->fragmentMultiCameraBinding.preview2.holder.surface
        }
        val targets = listOf(surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
        if (cameraId.equals(args.cameraId1)) {
            camera1 = camera
        } else {
            camera2 = camera
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    override fun onStop() {
        super.onStop()
        try {
            camera1.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
        try {
            camera2.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentMultiCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = "MultiCamPreview"
    }
}