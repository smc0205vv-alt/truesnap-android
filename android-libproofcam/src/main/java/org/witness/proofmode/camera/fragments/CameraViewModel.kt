@file:Suppress("DEPRECATION")

package org.witness.proofmode.camera.fragments

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExposureState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.FlashMode
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.witness.proofmode.ProofMode
import org.witness.proofmode.camera.CameraActivity
import org.witness.proofmode.camera.adapter.Media
import org.witness.proofmode.camera.fragments.CameraConstants.NEW_MEDIA_EVENT
import org.witness.proofmode.camera.network.CertificationService
import org.witness.proofmode.camera.utils.SharedPrefsManager
import org.witness.proofmode.camera.utils.getMediaFlow
import org.witness.proofmode.camera.utils.getSupportedQualities
import org.witness.proofmode.camera.utils.isUltraHdrSupported
import org.witness.proofmode.c2pa.proofsign.CaptureAuthority
import org.witness.proofmode.service.MediaWatcher.Companion.getInstance
import java.io.File
import java.io.FileOutputStream
import timber.log.Timber
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Captures older than this threshold are rejected before hashing to prevent stale certifications. */
private const val SESSION_MAX_AGE_MS = 10L * 60 * 1000L

/** State machine for the post-edit certification pipeline. */
sealed class CertificationState {
    object Idle : CertificationState()
    object Processing : CertificationState()
    /**
     * Hashes and auth ID computed on-device. Server upload is a separate later step.
     *
     * Server-side classification hint (implement on backend):
     *   SHA-256 same                              → 동일 파일
     *   SHA-256 diff + pHash Hamming ≤ 10        → 단순 보정됨
     *   SHA-256 diff + pHash Hamming > 10        → 구조 변경 의심
     */
    data class Done(
        val authId: String,
        val sha256Hash: String,
        val pHash: String?,
        val captureTimestampMs: Long
    ) : CertificationState()
    /** The capture file is older than [SESSION_MAX_AGE_MS]; redirect user to camera. */
    object SessionExpired : CertificationState()
}

/** State machine for the watermark composition step. */
sealed class WatermarkState {
    object Idle : WatermarkState()
    object Generating : WatermarkState()
    data class Ready(
        val bitmap: android.graphics.Bitmap,
        val shareUri: android.net.Uri
    ) : WatermarkState()
    data class Failed(val error: String) : WatermarkState()
}

/** State machine for the metadata POST to the server after nickname is confirmed. */
sealed class UploadState {
    object Idle : UploadState()
    object Uploading : UploadState()
    data class Success(val authId: String) : UploadState()
    data class Failure(val authId: String, val error: String) : UploadState()
}

class CameraViewModel(private val activity: CameraActivity, private val app: Application) : AndroidViewModel(app) {
    private val sharedPrefsManager = SharedPrefsManager.newInstance(app.applicationContext)
    private val capturesDir: File get() = File(app.filesDir, "captures").also { it.mkdirs() }
    private var _mediaFiles:MutableStateFlow<List<Media>> = MutableStateFlow(emptyList())
    val mediaFiles: StateFlow<List<Media>> = _mediaFiles
    private val mExec = Executors.newSingleThreadExecutor()
    private var surfaceOrientedMeteringPointFactory:SurfaceOrientedMeteringPointFactory? = null
    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest
    // Used for navigating to the preview page. Supposed to have content credentials attached if enabled
    private var _lastCapturedMedia: MutableStateFlow<Media?> = MutableStateFlow(null)
    val lastCapturedMedia: StateFlow<Media?> = _lastCapturedMedia

    private val _certificationState = MutableStateFlow<CertificationState>(CertificationState.Idle)
    val certificationState: StateFlow<CertificationState> = _certificationState

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState

    private val _watermarkState = MutableStateFlow<WatermarkState>(WatermarkState.Idle)
    val watermarkState: StateFlow<WatermarkState> = _watermarkState
    // Used for rounded thumbnail to immediately show when an image or video is captured
    var _thumbPreviewUri = MutableStateFlow<Media?>(null)
    val thumbPreviewUri: StateFlow<Media?> = _thumbPreviewUri


    private val _cameraQualities = MutableStateFlow<List<Quality>>(emptyList())
    val cameraQualities: StateFlow<List<Quality>> = _cameraQualities

    private var _exposureState:MutableStateFlow<ExposureState?> = MutableStateFlow(null)
    val exposureState: StateFlow<ExposureState?> = _exposureState
    private var _cameraDelay:MutableStateFlow<CameraDelay> = MutableStateFlow(CameraDelay.Zero)
    val cameraDelay: StateFlow<CameraDelay> = _cameraDelay
    private val previewUseCase = Preview.Builder()
        .build().apply {
        setSurfaceProvider { newSurfaceRequest->
            _surfaceRequest.update { newSurfaceRequest }
            surfaceOrientedMeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                newSurfaceRequest.resolution.width.toFloat(),
                newSurfaceRequest.resolution.height.toFloat()
            )

        }

    }
    init {
        loadMediaFiles()

    }


    private fun loadMediaFiles() {
        viewModelScope.launch {
            getMediaFlow(app.applicationContext, capturesDir)
                .collect{ media->
                    _thumbPreviewUri.value = media.firstOrNull()
                    _mediaFiles.value = media
                    _lastCapturedMedia.value = media.firstOrNull()
                }
        }
    }

    fun updateCameraDelay(delay: CameraDelay) {
        _cameraDelay.update { delay }
    }

    fun deleteMedia(media: Media?) {
        viewModelScope.launch(Dispatchers.IO) {
            media?.let {
                val segment = it.uri.lastPathSegment
                val deleted = segment?.let { name -> File(capturesDir, name).delete() } == true
                if (deleted) {
                    val currentList = _mediaFiles.value.toMutableList()
                    currentList.remove(it)
                    _mediaFiles.value = currentList
                    if (_lastCapturedMedia.value == it) {
                        _lastCapturedMedia.value = currentList.firstOrNull()
                    }
                } else {
                    Timber.e("Failed to delete capture file: ${it.uri}")
                }
            }
        }
    }
    private val _previewAlpha = MutableStateFlow(1f)
    val previewAlpha: StateFlow<Float> = _previewAlpha

    private val _shutterFlashTrigger = MutableStateFlow(0)
    val shutterFlashTrigger: StateFlow<Int> = _shutterFlashTrigger

    var lensFacing: MutableLiveData<Int> = MutableLiveData(
        sharedPrefsManager.getInt(SharedPrefsManager.KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
    )
        private set
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable
    private var startTime: Long = 0

    // LiveData to expose the elapsed time
    private val _elapsedTime = MutableLiveData<String>()
    val elapsedTime: LiveData<String> get() = _elapsedTime

    private var _recordTime = MutableStateFlow<String>("")
    val recordTime: StateFlow<String> = _recordTime

    private var cameraProvider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null

    // Recording state
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> get() = _recordingState
    private val _flashMode = MutableStateFlow(ImageCapture.FLASH_MODE_OFF)
    val flashMode: StateFlow<Int> = _flashMode

    private var _torchOn = MutableStateFlow(false)
    val torchOn: StateFlow<Boolean> = _torchOn
    private var _supportedFrameRates = MutableStateFlow(emptySet<Range<Int>>())
    val supportedFrameRates: StateFlow<Set<Range<Int>>> = _supportedFrameRates
    var zoomState: LiveData<ZoomState?> = MutableLiveData(null)
        private set

    // Reactive zoom values for the Compose zoom control. zoomState (LiveData) is
    // reassigned on every (re)bind, which makes it awkward to observe; these flows
    // are refreshed after each bind and on every zoom change so the preset bubbles
    // and slider always reflect the live ratio.
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio
    private val _minZoomRatio = MutableStateFlow(1f)
    val minZoomRatio: StateFlow<Float> = _minZoomRatio
    private val _maxZoomRatio = MutableStateFlow(1f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio

    // Still-capture framing & quality. Seeded from prefs so the user's last choice
    // survives app restarts; persisted again in changeAspectRatio / changePhotoQuality.
    // The enum is stored by name(), with a fallback in case a stored value is ever
    // renamed or removed.
    private val _photoAspectRatio = MutableStateFlow(
        runCatching {
            PhotoAspectRatio.valueOf(
                sharedPrefsManager.getString(
                    SharedPrefsManager.KEY_PHOTO_ASPECT_RATIO, PhotoAspectRatio.RATIO_4_3.name
                )
            )
        }.getOrDefault(PhotoAspectRatio.RATIO_4_3)
    )
    val photoAspectRatio: StateFlow<PhotoAspectRatio> = _photoAspectRatio
    private val _photoQuality = MutableStateFlow(
        runCatching {
            PhotoQuality.valueOf(
                sharedPrefsManager.getString(
                    SharedPrefsManager.KEY_PHOTO_QUALITY, PhotoQuality.HIGH.name
                )
            )
        }.getOrDefault(PhotoQuality.HIGH)
    )
    val photoQuality: StateFlow<PhotoQuality> = _photoQuality

    // Selected quality
    private val _selectedQuality = MutableStateFlow<Quality?>(null) // Default to FHD (1080p)
    val selectedQuality: StateFlow<Quality?> get() = _selectedQuality

    private var _ultraHdr = MutableStateFlow(UltraHDRAvailabilityState.OFF)
    val ultraHdr: StateFlow<UltraHDRAvailabilityState> = _ultraHdr

    private val imageCaptureBuilder = ImageCapture.Builder()
        .setJpegQuality(100)
    private var imageCapture: ImageCapture? = null

    fun toggleTorchForVideo() {
        val previousTorchState = torchOn.value
        _torchOn.value = !previousTorchState
        cameraControl?.enableTorch(_torchOn.value)
    }


    fun toggleFlashMode( @FlashMode newMode: Int, lifecycleOwner: LifecycleOwner) {
        _flashMode.value = newMode
        imageCaptureBuilder.setFlashMode(flashMode.value)
        bindImageUseCases(lifecycleOwner)
    }

    /**
     * Builds an [ImageCapture] reflecting the current aspect ratio, JPEG quality
     * and Ultra HDR selection. The shared [imageCaptureBuilder] retains the flash
     * mode set elsewhere.
     */
    private fun buildImageCapture(): ImageCapture {
        val aspect = _photoAspectRatio.value
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (aspect.baseAspectRatio == AspectRatio.RATIO_16_9)
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .build()
        return imageCaptureBuilder
            .setResolutionSelector(resolutionSelector)
            .setJpegQuality(_photoQuality.value.jpegQuality)
            .apply {
                setOutputFormat(
                    if (ultraHdr.value == UltraHDRAvailabilityState.ON)
                        ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
                    else
                        ImageCapture.OUTPUT_FORMAT_JPEG
                )
            }
            .build()
    }

    /**
     * (Re)binds the preview + image-capture use cases as a [UseCaseGroup] sharing a
     * [ViewPort] of the selected aspect ratio, so the preview and the saved file are
     * framed identically (WYSIWYG). The viewport's crop rect is what yields a true
     * square output for 1:1 — CameraX has no native 1:1 aspect-ratio strategy.
     */
    private fun bindImageUseCases(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value ?: CameraSelector.LENS_FACING_BACK)
            .build()
        provider.unbind(imageCapture)
        imageCapture = buildImageCapture()
        // A ViewPort's aspect ratio is expressed in the *output* (rotated) orientation,
        // so the rational must follow how the device is held: portrait inverts the
        // landscape sensor rational (16:9 -> 9:16) for a tall crop, landscape keeps it
        // as-is. Without this the saved crop comes out wide even in portrait.
        val rotation = activity.getScreenOrientation()
        val baseRational = _photoAspectRatio.value.rational
        val isPortrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        val orientedRational = if (isPortrait)
            Rational(baseRational.denominator, baseRational.numerator)
        else
            baseRational
        val viewPort = ViewPort.Builder(orientedRational, rotation).build()
        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(imageCapture!!)
            .setViewPort(viewPort)
            .build()
        try {
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup).apply {
                _exposureState.value = cameraInfo.exposureState
            }
            zoomState = camera!!.cameraInfo.zoomState
            cameraControl = camera?.cameraControl
            refreshZoomState()
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to bind image use cases")
        }
    }

    /** Switch the still-capture framing (4:3 / 16:9 / 1:1) and rebind. */
    suspend fun changeAspectRatio(aspect: PhotoAspectRatio, lifecycleOwner: LifecycleOwner) {
        if (_photoAspectRatio.value == aspect) return
        _photoAspectRatio.update { aspect }
        sharedPrefsManager.putString(SharedPrefsManager.KEY_PHOTO_ASPECT_RATIO, aspect.name)
        _previewAlpha.update { 0.5f }
        bindImageUseCases(lifecycleOwner)
        delay(250)
        _previewAlpha.update { 1f }
    }

    /** Switch JPEG quality (High / Standard) and rebind. */
    fun changePhotoQuality(quality: PhotoQuality, lifecycleOwner: LifecycleOwner) {
        if (_photoQuality.value == quality) return
        _photoQuality.update { quality }
        sharedPrefsManager.putString(SharedPrefsManager.KEY_PHOTO_QUALITY, quality.name)
        bindImageUseCases(lifecycleOwner)
    }




    fun pinchZoom(zoom: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        if (zoomState != null) {
            val maxZoomRatio = zoomState.maxZoomRatio
            val minZoomRatio = zoomState.minZoomRatio
            val currentZoomRatio = zoomState.zoomRatio
            val newZoomRatio = (currentZoomRatio * zoom).coerceIn(minZoomRatio, maxZoomRatio)
            cameraControl?.setZoomRatio(newZoomRatio)
            _zoomRatio.value = newZoomRatio
        }
    }

    /** Jump to (or smoothly drive, from the slider) an absolute zoom ratio. */
    fun setZoomRatio(target: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
        val clamped = target.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cameraControl?.setZoomRatio(clamped)
        _zoomRatio.value = clamped
    }

    /** Pull the live min/current/max zoom out of the camera after a (re)bind. */
    private fun refreshZoomState() {
        camera?.cameraInfo?.zoomState?.value?.let { zs ->
            _minZoomRatio.value = zs.minZoomRatio
            _maxZoomRatio.value = zs.maxZoomRatio
            _zoomRatio.value = zs.zoomRatio
        }
    }

    suspend fun changeQuality(quality:Quality,lifecycleOwner: LifecycleOwner) {
        _selectedQuality.update{ quality}
        cameraProvider?.unbindAll()
        _previewAlpha.update { 0.5f }
        delay(800)
        _previewAlpha.update { 1f }

        videoCapture = null
        recorder = null
        recorder = Recorder.Builder()

            .setQualitySelector(QualitySelector.from(_selectedQuality.value!!, FallbackStrategy.lowerQualityThan(
                _selectedQuality.value!!)))
            .build()
        videoCapture = VideoCapture.withOutput(recorder!!)
        try {
            camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,CameraSelector.Builder().requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build(),
                previewUseCase,videoCapture)
            camera?.cameraInfo?.supportedFrameRateRanges.let { ranges->
                _supportedFrameRates.update { ranges as Set<Range<Int>> }

            }
            zoomState = camera!!.cameraInfo.zoomState
            cameraControl = camera?.cameraControl
            cameraControl?.enableTorch(_torchOn.value)
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to bind video capture with quality $quality")
        }

    }


suspend fun bindUseCasesForVideo(lifecycleOwner: LifecycleOwner) {
    cameraProvider = (cameraProvider?: ProcessCameraProvider.awaitInstance(app.applicationContext)).also {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        val qualities = getSupportedQualities(cameraSelector,it)
        _cameraQualities.update { qualities }
        it.unbindAll()
    }
    //cameraProvider?.unbindAll()
    recorder = Recorder.Builder()
        .apply {
            selectedQuality.value?.let {
                setQualitySelector(QualitySelector.from(it, FallbackStrategy.lowerQualityThan(it)))
            }
        }
        .build()
    videoCapture = VideoCapture
        .withOutput(recorder!!)
    try {
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,CameraSelector.Builder().requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build(),
            previewUseCase,videoCapture)
        zoomState = camera!!.cameraInfo.zoomState
        cameraControl = camera?.cameraControl
        cameraControl?.enableTorch(_torchOn.value)
    } catch (ex:Exception){
        Timber.e("Binding failed")
    }


}
    suspend fun bindUseCasesForImage(lifecycleOwner: LifecycleOwner) {
        cameraProvider = cameraProvider?: ProcessCameraProvider.awaitInstance(app.applicationContext)
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        if (!isUltraHdrSupported(cameraSelector, cameraProvider!!)) {
            _ultraHdr.update { UltraHDRAvailabilityState.NOT_SUPPORTED }
        }
        bindImageUseCases(lifecycleOwner)
    }

    suspend fun toggleUltraHdr(lifecycleOwner: LifecycleOwner) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        val isUltraHdrSupported = isUltraHdrSupported(cameraSelector,cameraProvider!!)
        if (!isUltraHdrSupported) {
            _ultraHdr.update { UltraHDRAvailabilityState.NOT_SUPPORTED }
        } else {
            if (ultraHdr.value == UltraHDRAvailabilityState.ON) {
                _ultraHdr.update { UltraHDRAvailabilityState.OFF }
            } else {
                _ultraHdr.update { UltraHDRAvailabilityState.ON }
            }
            _previewAlpha.update { 0.5f }
            delay(800)
            _previewAlpha.update { 1f }
            bindImageUseCases(lifecycleOwner)
        }

    }



    fun captureImage() {
        _shutterFlashTrigger.update { it + 1 }

        val metadata = Metadata().apply { isReversedHorizontal = false }

        val outputFile = File(capturesDir, "${System.currentTimeMillis()}.jpg")
        val outputOptions = OutputFileOptions.Builder(outputFile)
            .setMetadata(metadata)
            .build()

        imageCapture?.setTargetRotation(activity.getScreenOrientation())

        imageCapture?.takePicture(
            outputOptions,
            mExec,
            object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: Uri.fromFile(outputFile)
                    val capturedTime = System.currentTimeMillis()
                    val newMedia = Media(savedUri, false, capturedTime)
                    _thumbPreviewUri.value = newMedia
                    _lastCapturedMedia.value = newMedia
                    _mediaFiles.value = listOf(newMedia) + mediaFiles.value

                    CoroutineScope(Dispatchers.IO).launch {
                        sendLocalCameraEvent(savedUri, CameraEventType.NEW_IMAGE)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e("Error capturing image")
                }
            }
        )
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // SECURITY BOUNDARY — CERTIFICATION PIPELINE ONLY
    //
    // certifyAndSave() and startCertificationUpload() are the sole entry points
    // into the registration pipeline. [uri] must originate from _lastCapturedMedia,
    // which is set exclusively by:
    //   • ImageCapture.takePicture() → OnImageSavedCallback (CameraX physical capture)
    //   • VideoRecordEvent.Finalize callback (video recording completion)
    //   • loadMediaFiles() reading capturesDir (app-private directory)
    //
    // _lastCapturedMedia is `private`; no external class can write to it.
    // VerifyViewModel and VerifyActivity have zero reference to this class,
    // so an externally-shared image can never reach this pipeline.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * On-device certification pipeline called when the user taps "완료" in PhotoEditScreen:
     *
     * 0. Session freshness gate — reject captures older than [SESSION_MAX_AGE_MS] (10 min).
     *    Read from the actual file's lastModified() so the check cannot be bypassed by
     *    keeping the app open with a stale ViewModel state.
     * 1. Compress [bitmap] to JPEG bytes
     * 2. SHA-256 of those bytes
     * 3. DCT perceptual hash (pHash) via ru.avicorp:phashcalc (avbase/pHashCalc)
     * 4. Generate "TS-XXXXXX" auth ID
     * 5. Overwrite the capture file with the filtered JPEG
     *
     * Server upload is deferred to a later step. Drives [certificationState].
     */
    fun certifyAndSave(
        uri: android.net.Uri,
        bitmap: android.graphics.Bitmap,
        captureTimestampMs: Long
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _certificationState.value = CertificationState.Processing

            // 0. Session freshness gate
            //    Prefer file metadata over the passed-in timestamp so that a long-lived
            //    app session cannot bypass this check.
            val fileAgeMs = try {
                System.currentTimeMillis() - uri.toFile().lastModified()
            } catch (_: Exception) {
                System.currentTimeMillis() - captureTimestampMs
            }
            if (fileAgeMs > SESSION_MAX_AGE_MS) {
                Timber.w("Session expired: capture is %d ms old (limit %d ms)", fileAgeMs, SESSION_MAX_AGE_MS)
                _lastCapturedMedia.value = null
                _certificationState.value = CertificationState.SessionExpired
                return@launch
            }

            // 1. Compress to JPEG bytes (single pass — reused for hash + disk write)
            val imageBytes = java.io.ByteArrayOutputStream().also { baos ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, baos)
            }.toByteArray()

            val service = CertificationService()

            // 2. SHA-256 — cryptographic integrity fingerprint
            val sha256 = service.calculateSha256(imageBytes)

            // 3. pHash — DCT perceptual hash for visual-similarity comparison
            val pHash = service.calculatePHash(imageBytes, app.cacheDir)

            // 4. Unique auth ID
            val authId = service.generateAuthId()

            Timber.d("Certification: authId=%s sha256=%s pHash=%s", authId, sha256, pHash)

            // 5. Overwrite the capture file with the edited JPEG
            try {
                FileOutputStream(uri.toFile()).use { os -> os.write(imageBytes) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save edited bitmap to %s", uri)
            }

            _certificationState.value = CertificationState.Done(
                authId             = authId,
                sha256Hash         = sha256,
                pHash              = pHash,
                captureTimestampMs = captureTimestampMs
            )
        }
    }

    /** Resets certification state back to Idle (called after nickname is confirmed or cancelled). */
    fun resetCertificationState() {
        _certificationState.value = CertificationState.Idle
    }

    fun resetUploadState() {
        _uploadState.value = UploadState.Idle
    }

    fun resetWatermarkState() {
        _watermarkState.value = WatermarkState.Idle
    }

    /**
     * Loads the saved capture file, composites a certification watermark strip
     * (QR code + authId + TrueSnap branding), and saves a temp JPEG to cacheDir
     * so it can be shared via FileProvider. Result drives [watermarkState].
     */
    fun generateWatermark(authId: String) {
        val mediaUri = _lastCapturedMedia.value?.uri ?: run {
            _watermarkState.value = WatermarkState.Failed("캡처 파일을 찾을 수 없습니다")
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _watermarkState.value = WatermarkState.Generating
            try {
                val photo = android.graphics.BitmapFactory.decodeFile(mediaUri.toFile().absolutePath)
                    ?: throw Exception("이미지 디코딩 실패")
                val watermarked = WatermarkComposer.compose(photo, authId)
                photo.recycle()

                val tempFile = java.io.File(app.cacheDir, "wm_share_${authId}.jpg")
                FileOutputStream(tempFile).use {
                    watermarked.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)
                }
                val shareUri = androidx.core.content.FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.provider",
                    tempFile
                )
                _watermarkState.value = WatermarkState.Ready(watermarked, shareUri)
            } catch (e: Exception) {
                Timber.e(e, "Watermark generation failed")
                _watermarkState.value = WatermarkState.Failed(e.message ?: "알 수 없는 오류")
            }
        }
    }

    /**
     * POSTs certification metadata (hashes + nickname) to the server.
     * No image is sent — only the computed fingerprints and user-supplied nickname.
     * Drives [uploadState].
     */
    fun startCertificationUpload(
        authId: String,
        sha256Hash: String,
        pHash: String?,
        captureTimestampMs: Long,
        nickname: String
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _uploadState.value = UploadState.Uploading
            val request = CertificationService.MetadataUploadRequest(
                authId             = authId,
                sha256Hash         = sha256Hash,
                pHash              = pHash,
                captureTimestampMs = captureTimestampMs,
                nickname           = nickname
            )
            _uploadState.value = when (val result = CertificationService().uploadMetadata(request)) {
                is CertificationService.MetadataUploadResult.Success ->
                    UploadState.Success(result.authId)
                is CertificationService.MetadataUploadResult.Failure ->
                    UploadState.Failure(result.authId, result.error)
            }
        }
    }

    /**
     * Returns recently confirmed names, newest first, up to [MAX_RECENT_NAMES].
     * Stored as a pipe-delimited string so no JSON dependency is needed.
     */
    fun getRecentNames(): List<String> {
        val raw = sharedPrefsManager.getString(SharedPrefsManager.KEY_RECENT_NAMES, "")
        return if (raw.isBlank()) emptyList()
        else raw.split("|").filter { it.isNotBlank() }
    }

    /**
     * Locks [nickname] to [authId] and prepends it to the recent-names list.
     * Duplicates are deduped (same name moved to front). List capped at [MAX_RECENT_NAMES].
     */
    fun saveNicknameForAuth(authId: String, nickname: String) {
        sharedPrefsManager.putString(
            SharedPrefsManager.NICKNAME_LOCK_PREFIX + authId,
            nickname
        )
        val updated = (listOf(nickname) + getRecentNames().filter { it != nickname })
            .take(MAX_RECENT_NAMES)
        sharedPrefsManager.putString(
            SharedPrefsManager.KEY_RECENT_NAMES,
            updated.joinToString("|")
        )
    }

    companion object {
        private const val MAX_RECENT_NAMES = 10
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        videoCapture?.targetRotation = activity.getScreenOrientation()

        if (recordingState.value != RecordingState.Idle && recordingState.value != RecordingState.Stopped) return

        val outputFile = File(
            capturesDir,
            "${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())}.mp4"
        )
        startTimer()

        val fileOutput = FileOutputOptions.Builder(outputFile).build()
        recording = recorder?.prepareRecording(app.applicationContext, fileOutput)
            ?.withAudioEnabled()
            ?.start(ContextCompat.getMainExecutor(app.applicationContext)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        _recordingState.update { RecordingState.Recording }
                    }
                    is VideoRecordEvent.Finalize -> {
                        stopTimer()
                        if (!recordEvent.hasError()) {
                            _recordingState.update { RecordingState.Stopped }

                            CoroutineScope(Dispatchers.IO).launch {
                                val savedUri = recordEvent.outputResults.outputUri
                                val capturedTime = System.currentTimeMillis()
                                val newMedia = Media(savedUri, true, capturedTime)
                                _thumbPreviewUri.value = newMedia
                                _lastCapturedMedia.value = newMedia
                                _mediaFiles.value = listOf(newMedia) + mediaFiles.value
                                sendLocalCameraEvent(savedUri, CameraEventType.NEW_VIDEO)
                            }
                        } else {
                            _recordingState.update { RecordingState.Error("Recording finished with error") }
                        }
                    }
                }
            }
    }

    fun pauseRecording() {
        if (_recordingState.value == RecordingState.Recording) {
            recording?.pause()
            _recordingState.update { RecordingState.Paused }
        }
    }

    fun resumeRecording() {
        if (_recordingState.value == RecordingState.Paused) {
            recording?.resume()
            _recordingState.update { RecordingState.Recording }
        }
    }

    fun stopRecording() {
        if (_recordingState.value == RecordingState.Recording || _recordingState.value == RecordingState.Paused) {
            recording?.stop()
            _recordingState.update { RecordingState.Idle }
        }
    }


    private fun sendLocalCameraEvent(newMediaFile: Uri, cameraEventType: CameraEventType) {
        val mw = getInstance(activity)
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

        // Notify in-app feed immediately (reaches our unexported ProofEventReceiver only).
        app.sendBroadcast(
            Intent(ProofMode.EVENT_MEDIA_CAPTURED).apply {
                setPackage(app.packageName)
                putExtra(ProofMode.EVENT_PROOF_EXTRA_URI, newMediaFile.toString())
            }
        )

        // Capture-authorization nonce bound to the file SHA-256.
        val captureNonce: ByteArray? = try {
            val digest = computeFileDigest(newMediaFile)
            digest?.let { CaptureAuthority.issueNonce(it) }
        } catch (e: Exception) {
            Timber.w(e, "failed to issue capture nonce for $newMediaFile")
            null
        }

        if (!prefs.getBoolean(ProofMode.PREFS_DOPROOF, false)) {
            val mimeType = if (cameraEventType == CameraEventType.NEW_VIDEO) "video/mp4" else "image/jpeg"
            mw?.ingestMedia(newMediaFile, true, null, mimeType, null, captureNonce)
        }
    }

    private fun computeFileDigest(uri: Uri): ByteArray? {
        val md = MessageDigest.getInstance("SHA-256")
        val input = app.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }


    suspend fun switchLensFacing(lifecycleOwner: LifecycleOwner,cameraMode: CameraMode) {
        val newFacing = if (lensFacing.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        lensFacing.value = newFacing
        sharedPrefsManager.putInt(SharedPrefsManager.KEY_LENS_FACING, newFacing)
        cameraProvider?.unbindAll()
        if (cameraMode == CameraMode.VIDEO) {
            bindUseCasesForVideo(lifecycleOwner)
        } else if (cameraMode == CameraMode.IMAGE) {
            bindUseCasesForImage(lifecycleOwner)
        }


    }

    fun updateExposureCompensation(compensationIndex:Int){
        cameraControl?.setExposureCompensationIndex(compensationIndex)
    }



    // Format the elapsed time
    private fun formatElapsedTime(elapsedTime: Long): String {
        val hours = (elapsedTime / 3600000).toInt()
        val minutes = (elapsedTime % 3600000 / 60000).toInt()
        val seconds = (elapsedTime % 60000 / 1000).toInt()

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(),"%02d:%02d", minutes, seconds)
        }
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
        _elapsedTime.value = "" // Reset the timer
        _recordTime.update { "" }
    }
    private fun startTimer() {
        startTime = System.currentTimeMillis()
        timerRunnable = object : Runnable {
            override fun run() {
                val timeDelta = System.currentTimeMillis() - startTime
                _elapsedTime.postValue(formatElapsedTime(timeDelta))
                _recordTime.update { formatElapsedTime(timeDelta) }
                handler.postDelayed(this, 1000)

            }
        }
        handler.post(timerRunnable)
    }


    override fun onCleared() {
        try {
        if (this::timerRunnable.isInitialized)
            handler.removeCallbacks(timerRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onCleared()
    }

    fun tapToFocus(tapCoordinates: Offset) {
        val point = surfaceOrientedMeteringPointFactory?.createPoint(tapCoordinates.x,tapCoordinates.y)
        if (point != null) {
            val meteringAction = FocusMeteringAction.Builder(point).build()
            if (camera?.cameraInfo?.isFocusMeteringSupported(meteringAction) == true){
                cameraControl?.startFocusAndMetering(meteringAction)

            }

        }

    }

    fun unbindAll() {
        cameraProvider?.unbindAll()
        recording?.stop()
        _recordingState.update { RecordingState.Idle }
    }


}

enum class CameraEventType {
    NEW_IMAGE,
    NEW_VIDEO
}

object CameraConstants {
    const val NEW_MEDIA_EVENT = "org.witness.proofmode.NEW_MEDIA"
}



enum class CameraMode{
    VIDEO,
    IMAGE
}

enum class UltraHDRAvailabilityState(val description: String) {
    ON("On"),
    OFF("Off"),
    NOT_SUPPORTED("Not supported")
}

/**
 * Still-capture framing. [rational] drives the shared [ViewPort] crop (so 1:1 yields
 * a real square output), while [baseAspectRatio] picks the sensor output strategy —
 * 1:1 is captured from the full 4:3 sensor area and cropped square by the viewport.
 */
enum class PhotoAspectRatio(val label: String, val rational: Rational, val baseAspectRatio: Int) {
    RATIO_4_3("4:3", Rational(4, 3), AspectRatio.RATIO_4_3),
    RATIO_16_9("16:9", Rational(16, 9), AspectRatio.RATIO_16_9),
    RATIO_1_1("1:1", Rational(1, 1), AspectRatio.RATIO_4_3)
}

/** JPEG quality presets for stills. */
enum class PhotoQuality(val label: String, val jpegQuality: Int) {
    HIGH("High", 100),
    STANDARD("Standard", 85)
}


