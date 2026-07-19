package com.example.rawcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RawCamera"
        private const val REQUEST_CAMERA_PERMISSION = 100
    }

    private lateinit var textureView: TextureView
    private lateinit var tvStatus: TextView
    private lateinit var btnCapture: Button

    private lateinit var cameraManager: CameraManager
    private var cameraId: String = ""
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var imageReader: ImageReader? = null
    private var supportsRaw = false
    private var previewSize: Size = Size(1920, 1080)
    private var captureSize: Size = Size(1920, 1080)

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            setupCameraAndOpen()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        tvStatus = findViewById(R.id.tvStatus)
        btnCapture = findViewById(R.id.btnCapture)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        btnCapture.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable) setupCameraAndOpen()
                else textureView.surfaceTextureListener = surfaceTextureListener
            } else {
                Toast.makeText(this, "Permiso de cámara requerido", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable) setupCameraAndOpen()
            else textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error deteniendo hilo", e)
        }
    }

    // ---- Detección real de la cámara trasera principal y sus capacidades ----
    private fun setupCameraAndOpen() {
        try {
            var bestId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    bestId = id
                    characteristics = chars
                    break
                }
            }
            if (bestId == null) {
                tvStatus.text = "No se encontró cámara trasera"
                return
            }
            cameraId = bestId

            val chars = characteristics!!
            val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run { tvStatus.text = "Sin StreamConfigurationMap"; return }

            captureSize = if (supportsRaw) {
                pickLargest(map.getOutputSizes(ImageFormat.RAW_SENSOR))
            } else {
                pickLargest(map.getOutputSizes(ImageFormat.JPEG))
            }
            previewSize = pickPreviewSize(map.getOutputSizes(SurfaceTexture::class.java), captureSize)

            val hwLevelStr = when (hwLevel) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "DESCONOCIDO"
            }
            val modo = if (supportsRaw) "RAW/DNG ${captureSize.width}x${captureSize.height}"
                       else "JPEG máx ${captureSize.width}x${captureSize.height} (sin RAW en este HW)"
            runOnUiThread { tvStatus.text = "HW: $hwLevelStr | Captura: $modo" }
            Log.i(TAG, "HW level=$hwLevelStr supportsRaw=$supportsRaw captureSize=$captureSize")

            imageReader = ImageReader.newInstance(
                captureSize.width, captureSize.height,
                if (supportsRaw) ImageFormat.RAW_SENSOR else ImageFormat.JPEG,
                2
            )
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                saveImage(image)
            }, backgroundHandler)

            openCamera()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error accediendo a la cámara", e)
        }
    }

    private fun pickLargest(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1920, 1080)
        return sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes[0]
    }

    private fun pickPreviewSize(sizes: Array<Size>?, target: Size): Size {
        if (sizes.isNullOrEmpty()) return Size(1920, 1080)
        // Evita previews absurdamente grandes; máximo 1920 de ancho, mismo aspect ratio aproximado
        val targetRatio = target.width.toDouble() / target.height.toDouble()
        return sizes.filter { it.width <= 1920 }
            .minByOrNull { Math.abs(it.width.toDouble() / it.height.toDouble() - targetRatio) }
            ?: sizes[0]
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    Log.e(TAG, "Error al abrir cámara: $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error abriendo cámara", e)
        }
    }

    private fun createPreviewSession() {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        val device = cameraDevice ?: return
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(previewSurface)

        val targets = listOf(previewSurface, imageReader!!.surface)
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                try {
                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "Error iniciando preview", e)
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Configuración de sesión fallida")
            }
        }, backgroundHandler)
    }

    // ---- Captura a máxima calidad, sin reducción de ruido ni edge enhancement ----
    private fun takePhoto() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val chars = characteristics ?: return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)

            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            // Desactivar reducción de ruido si el hardware lo permite
            val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            if (nrModes != null && nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF)) {
                captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            }

            // Desactivar realce de bordes (edge enhancement) si está disponible
            val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            if (edgeModes != null && edgeModes.contains(CameraMetadata.EDGE_MODE_OFF)) {
                captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            }

            if (!supportsRaw) {
                captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            }

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90)

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    lastCaptureResult = result
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error al capturar", e)
        }
    }

    private var lastCaptureResult: TotalCaptureResult? = null

    private fun saveImage(image: Image) {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "RawCamera")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        try {
            if (supportsRaw) {
                val chars = characteristics ?: return
                val result = lastCaptureResult
                if (result == null) {
                    image.close()
                    return
                }
                val dngCreator = DngCreator(chars, result)
                val file = File(dir, "IMG_${timestamp}.dng")
                FileOutputStream(file).use { fos ->
                    dngCreator.writeImage(fos, image)
                }
                dngCreator.close()
                runOnUiThread { Toast.makeText(this, "DNG guardado: ${file.name}", Toast.LENGTH_SHORT).show() }
            } else {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val file = File(dir, "IMG_${timestamp}.jpg")
                FileOutputStream(file).use { it.write(bytes) }
                runOnUiThread { Toast.makeText(this, "JPEG guardado: ${file.name}", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando imagen", e)
        } finally {
            image.close()
        }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }
}
