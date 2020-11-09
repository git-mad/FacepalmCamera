package club.gitmad.facepalmcamera.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import club.gitmad.facepalmcamera.R
import club.gitmad.facepalmcamera.databinding.FragmentCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.frame.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val TAG = CameraFragment::class.java.simpleName

class CameraFragment : Fragment(R.layout.fragment_camera) {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val poseDetector by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .build()
        )
    }

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    private var isReady = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        _binding = FragmentCameraBinding.bind(view)

        binding.apply {
            camera.setLifecycleOwner(viewLifecycleOwner)
            camera.addFrameProcessor { processFrame(it) }
            camera.addCameraListener(object : CameraListener() {
                override fun onPictureTaken(result: PictureResult) {
                    super.onPictureTaken(result)
                    savePicture(result)
                }
            })

            btnReady.setOnClickListener {
                isReady = true
                btnReady.text = "Ready"
            }
        }
    }

    private fun processFrame(frame: Frame) {
        if (!isReady) return

        val inputImage = when {
            frame.dataClass === ByteArray::class.java -> {
                InputImage.fromByteArray(
                    frame.getData(),
                    frame.size.width,
                    frame.size.height,
                    frame.rotationToView,
                    InputImage.IMAGE_FORMAT_NV21
                )
            }
            frame.dataClass === Image::class.java -> {
                InputImage.fromMediaImage(frame.getData(), frame.rotationToView)
            }
            else -> {
                null
            }
        }

        if (inputImage != null) {
            analyzeImage(inputImage)
        }
    }


    @SuppressLint("UnsafeExperimentalUsageError")
    private fun analyzeImage(inputImage: InputImage) {
        poseDetector.process(inputImage)
            .addOnSuccessListener {
                if (isFacepalm(it)) {
                    binding.btnReady.text = "Not ready"
                    isReady = false
                }
            }.addOnFailureListener {
                binding.btnReady.text = "Failed processing"
                Log.e(TAG, "Pose detector processing failed", it)
            }
    }

    private fun savePicture(result: PictureResult) {
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )
        result.toFile(photoFile) {
            Log.d(TAG, "Saved to: ${Uri.fromFile(it)}")
            binding.btnReady.text = "Saved. Not ready."
        }
    }

    private fun showNotification() {
        createNotificationChannel()

        // TODO: https://developer.android.com/training/notify-user/build-notification#builder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(
                "club.gitmad.facepalmcamera",
                "Facepalm Camera",
                importance
            ).apply {
                description = "Picture taken"
            }
            val notificationManager: NotificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun isFacepalm(pose: Pose): Boolean {
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)

        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)

        val shoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        if (leftEar != null && rightEar != null && rightWrist != null && shoulder != null) {
            if ((leftEar.position.x <= rightWrist.position.x && rightWrist.position.x <= rightEar.position.x) || (rightEar.position.x <= rightWrist.position.x && rightWrist.position.x <= leftEar.position.x)) {
                if (rightWrist.position.y >= shoulder.position.y) {
                    return true
                }
            }
        }

        return false
    }

    private fun getOutputDirectory(): File {
        val mediaDir = requireActivity().externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else requireActivity().filesDir
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(requireContext(), "Permissions not granted :(.", Toast.LENGTH_SHORT)
                    .show()
                requireActivity().finish()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

}