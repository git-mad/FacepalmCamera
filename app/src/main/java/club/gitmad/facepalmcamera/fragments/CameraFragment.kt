package club.gitmad.facepalmcamera.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val TAG = CameraFragment::class.java.simpleName

class CameraFragment : Fragment(R.layout.fragment_camera) {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
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

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        _binding = FragmentCameraBinding.bind(view)

        binding.btnReady.setOnClickListener {
            isReady = true
            binding.btnReady.text = "Ready"
        }
    }

    private fun takePicture() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    Toast.makeText(requireContext(), "Success: $savedUri", Toast.LENGTH_SHORT)
                        .show()
                    Log.d(TAG, "Saved at: $savedUri")
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalyzer.setAnalyzer(cameraExecutor, { analyzeImage(it) })

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer, preview)
            } catch (e: Exception) {
                Log.e(TAG, "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun analyzeImage(imageProxy: ImageProxy) {
        imageProxy.image?.let {
            InputImage.fromMediaImage(it, imageProxy.imageInfo.rotationDegrees)
        }?.apply {
            poseDetector.process(this)
                .addOnSuccessListener {
                    if (isReady && isFacepalm(it)) {
                        binding.btnReady.text = "Not ready"
                        isReady = false
                        takePicture()
                    }
                }.addOnFailureListener {
                    Log.e(TAG, "Pose detector processing failed", it)
                }.addOnCompleteListener {
                    imageProxy.close()
                }
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
            if (allPermissionsGranted()) {
                startCamera()
            } else {
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