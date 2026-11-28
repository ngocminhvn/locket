package com.locket.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Rational
import android.util.Size as AndroidSize
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import android.media.MediaMetadataRetriever
import android.widget.VideoView
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.locket.app.ui.LocketViewModel
import com.locket.app.ui.components.*
import com.locket.app.ui.theme.*
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.abs

@Composable
fun CameraScreen(
    viewModel: LocketViewModel,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit,
    onSwipeEnabledChange: (Boolean) -> Unit = {},
    sharedHeaderHeight: Dp? = null,
    sharedFooterHeight: Dp? = null,
    onReviewVisibleChange: (Boolean) -> Unit = {},
    chromeProgress: Float = 1f,
    chromeOffsetPx: Float = 0f
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraPreferences by rememberCameraPreferences()
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
    }
    var extensionActive by remember { mutableStateOf(false) }

    val currentUser by viewModel.currentUser.collectAsState()
    val friends by viewModel.friends.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    // Ban dang duoc tra loi rieng (dat khi bam vao moment cua ban do o Home)
    val replyToFriend by viewModel.replyToFriend.collectAsState()

    // Roi man hinh camera thi huy muc tieu tra loi, tranh gui nham lan sau
    DisposableEffect(Unit) {
        onDispose { viewModel.setReplyToFriend(null) }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var frontCamera by remember { mutableStateOf(true) }
    val cameraSelector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    var cameraError by remember { mutableStateOf<String?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var pinchingCamera by remember { mutableStateOf(false) }
    var cameraZoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomLimits by remember { mutableStateOf<ZoomState?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusRegionSupported by remember { mutableStateOf(false) }
    var focusSerial by remember { mutableIntStateOf(0) }
    var exposureDragging by remember { mutableStateOf(false) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf<IntRange?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoSession by remember { mutableStateOf(false) }
    var boundVideoSession by remember { mutableStateOf(false) }
    var holdingShutter by remember { mutableStateOf(false) }
    var resumeZoom by remember { mutableStateOf<Float?>(null) }
    var audioPermissionAsked by remember { mutableStateOf(false) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    var processingVideo by remember { mutableStateOf(false) }
    var capturingPhoto by remember { mutableStateOf(false) }
    var capturedVideo by remember { mutableStateOf<File?>(null) }
    var screenActive by remember { mutableStateOf(true) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            // TextureView supports transformed frame readback without the
            // SurfaceView PixelCopy wait and composes correctly inside the pager.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasAudioPermission = it
    }

    // State for captured or selected photo review
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Keep original camera JPEG bytes separate from the cropped review/upload bitmap.
    var capturedOriginalJpeg by remember { mutableStateOf<ByteArray?>(null) }
    var captureDetails by remember { mutableStateOf<String?>(null) }
    val canSwipe = !pinchingCamera && !exposureDragging && !holdingShutter && recording == null && !processingVideo && !capturingPhoto && capturedBitmap == null
    SideEffect {
        onSwipeEnabledChange(canSwipe)
        onReviewVisibleChange(capturedBitmap != null)
    }

    fun clearCapture() {
        capturedBitmap = null
        capturedOriginalJpeg = null
        captureDetails = null
        capturedVideo?.delete()
        capturedVideo = null
    }

    DisposableEffect(Unit) {
        screenActive = true
        onDispose {
            screenActive = false
            holdingShutter = false
            recording?.stop()
            capturedVideo?.delete()
        }
    }

    // Video keeps its own standard session when a photo extension is selected.
    val requestedExtension = if (videoSession) ExtensionMode.NONE else cameraPreferences.extension
    val requestedVideoHdr = videoSession && cameraPreferences.videoHdr
    DisposableEffect(hasCameraPermission, cameraSelector, lifecycleOwner, previewView, requestedExtension,
        requestedVideoHdr, cameraPreferences.stabilization,
        cameraPreferences.selfieFlash, cameraPreferences.mirror, activity) {
        var active = true
        var provider: ProcessCameraProvider? = null
        boundCamera = null
        pinchingCamera = false
        cameraZoomRatio = 1f
        zoomLimits = null
        focusPoint = null
        focusRegionSupported = false
        exposureDragging = false
        exposureIndex = 0
        exposureRange = null
        imageCapture = null
        videoCapture = null
        hasFlash = false
        cameraError = null
        extensionActive = false
        boundVideoSession = false
        val job = if (hasCameraPermission) scope.launch {
            try {
                val cameraProvider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
                if (!active) return@launch
                provider = cameraProvider
                if (!cameraProvider.hasCamera(cameraSelector)) {
                    cameraError = "Thiết bị không có camera này"
                    return@launch
                }
                var selected = cameraSelector
                if (requestedExtension != ExtensionMode.NONE) {
                    try {
                        val manager = withContext(Dispatchers.IO) { ExtensionsManager.getInstanceAsync(context, cameraProvider).get() }
                        if (!active) return@launch
                        if (manager.isExtensionAvailable(cameraSelector, requestedExtension)) {
                            selected = manager.getExtensionEnabledCameraSelector(cameraSelector, requestedExtension)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { selected = cameraSelector }
                }
                val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                val viewPort = ViewPort.Builder(Rational(1, 1), rotation).setScaleType(ViewPort.FILL_CENTER).build()
                previewView.setScreenFlashWindow(activity?.window)
                fun bind(selector: CameraSelector, extension: Boolean, hdr: Boolean, advanced: Boolean, includeVideo: Boolean): Camera {
                    val info = cameraProvider.getCameraInfo(selector)
                    val preview = Preview.Builder().setTargetRotation(rotation).apply {
                        // Do not constrain the live stream to the upload resolution.
                        // Keep the ordinary photo preview SDR, avoiding HDR-to-SDR shifts.
                        if (!hdr && !extension) setDynamicRange(DynamicRange.SDR)
                        if (advanced && cameraPreferences.stabilization && !extension &&
                            Preview.getPreviewCapabilities(info).isStabilizationSupported) setPreviewStabilizationEnabled(true)
                    }.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val photo = ImageCapture.Builder()
                        .setTargetRotation(rotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .apply {
                            if (!extension && advanced) setResolutionSelector(cameraPhotoResolutionSelector())
                        }
                        .build()
                    if (frontCamera && !extension && cameraPreferences.selfieFlash && activity != null) {
                        previewView.screenFlash?.let { photo.setScreenFlash(it) }
                    }
                    val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(photo)
                    val video = if (includeVideo && !extension) {
                        val recorder = Recorder.Builder().setQualitySelector(
                            QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
                        ).build()
                        VideoCapture.Builder(recorder).apply {
                            setMirrorMode(if (cameraPreferences.mirror) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
                            if (hdr && DynamicRange.HLG_10_BIT in Recorder.getVideoCapabilities(info).supportedDynamicRanges) {
                                setDynamicRange(DynamicRange.HLG_10_BIT)
                            }
                        }.build().also { group.addUseCase(it) }
                    } else null
                    cameraProvider.unbindAll()
                    return cameraProvider.bindToLifecycle(lifecycleOwner, selector, group.build()).also {
                        imageCapture = photo
                        videoCapture = video
                        extensionActive = extension
                    }
                }
                val camera = if (selected != cameraSelector) {
                    try { bind(selected, true, false, false, false) }
                    catch (_: Exception) {
                        try { bind(cameraSelector, false, false, true, true) }
                        catch (_: Exception) {
                            try { bind(cameraSelector, false, false, false, true) }
                            catch (_: Exception) { bind(cameraSelector, false, false, false, false) }
                        }
                    }
                } else {
                    try { bind(cameraSelector, false, requestedVideoHdr, true, true) }
                    catch (_: Exception) {
                        try { bind(cameraSelector, false, false, false, true) }
                        catch (_: Exception) { bind(cameraSelector, false, false, false, false) }
                    }
                }
                boundCamera = camera
                zoomLimits = camera.cameraInfo.zoomState.value
                cameraZoomRatio = zoomLimits?.zoomRatio ?: 1f
                val exposure = camera.cameraInfo.exposureState
                exposureIndex = exposure.exposureCompensationIndex
                exposureRange = if (exposure.isExposureCompensationSupported &&
                    exposure.exposureCompensationRange.upper > exposure.exposureCompensationRange.lower) {
                    exposure.exposureCompensationRange.lower..exposure.exposureCompensationRange.upper
                } else null
                hasFlash = camera.cameraInfo.hasFlashUnit()
                resumeZoom?.let { ratio ->
                    val limits = camera.cameraInfo.zoomState.value
                    if (limits != null) {
                        val target = ratio.coerceIn(limits.minZoomRatio, limits.maxZoomRatio)
                        cameraZoomRatio = target
                        camera.cameraControl.setZoomRatio(target)
                    }
                    resumeZoom = null
                }
                boundVideoSession = videoSession
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (active) cameraError = "Không thể mở camera: ${e.localizedMessage ?: "lỗi không xác định"}"
            }
        } else null
        onDispose {
            active = false
            job?.cancel()
            provider?.unbindAll()
            previewView.setScreenFlashWindow(null)
        }
    }

    // Observe hardware zoom changes; do not carry front-camera limits to the rear lens.
    DisposableEffect(boundCamera, lifecycleOwner) {
        val camera = boundCamera
        val observer = androidx.lifecycle.Observer<ZoomState> { state ->
            if (boundCamera === camera) {
                zoomLimits = state
                if (!pinchingCamera) cameraZoomRatio = state.zoomRatio
            }
        }
        camera?.cameraInfo?.zoomState?.observe(lifecycleOwner, observer)
        onDispose { camera?.cameraInfo?.zoomState?.removeObserver(observer) }
    }

    LaunchedEffect(focusSerial, exposureDragging) {
        if (focusPoint != null && !exposureDragging) {
            delay(5000)
            focusPoint = null
        }
    }

    fun requestZoom(camera: Camera, ratio: Float) {
        if (camera !== boundCamera) return
        val limits = camera.cameraInfo.zoomState.value ?: return
        val next = ratio.coerceIn(limits.minZoomRatio, limits.maxZoomRatio)
        if (!next.isFinite()) return
        cameraZoomRatio = next
        val future = camera.cameraControl.setZoomRatio(next)
        future.addListener({
            try { future.get() } catch (_: Exception) {
                // Superseded requests are normal during a pinch.
                if (camera === boundCamera && !pinchingCamera && cameraZoomRatio == next) {
                    cameraZoomRatio = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun requestExposure(camera: Camera, index: Int) {
        if (camera !== boundCamera) return
        val range = exposureRange ?: return
        val next = index.coerceIn(range.first, range.last)
        if (next == exposureIndex) return
        exposureIndex = next
        val future = camera.cameraControl.setExposureCompensationIndex(next)
        future.addListener({
            try { future.get() } catch (_: Exception) {
                if (camera === boundCamera && exposureIndex == next) {
                    exposureIndex = camera.cameraInfo.exposureState.exposureCompensationIndex
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun meterAt(camera: Camera, position: Offset) {
        if (camera !== boundCamera || previewView.width <= 0 || previewView.height <= 0 ||
            previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) return
        val point = previewView.meteringPointFactory.createPoint(position.x, position.y)
        // Leave CameraX continuous auto focus, AE and AWB defaults intact; tap
        // temporarily selects the supported metering regions before auto cancel.
        val action = FocusMeteringAction.Builder(point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        focusRegionSupported = camera.cameraInfo.isFocusMeteringSupported(action)
        if (focusRegionSupported) {
            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({
                try { future.get() } catch (_: Exception) {
                    // A newer tap, a lens switch or lifecycle stop can cancel this request.
                }
            }, ContextCompat.getMainExecutor(context))
        }
        if (focusRegionSupported || exposureRange != null) {
            focusPoint = position
            focusSerial++
        }
    }

    LaunchedEffect(flashMode, hasFlash, imageCapture, frontCamera, cameraPreferences.selfieFlash, extensionActive) {
        val capture = imageCapture ?: return@LaunchedEffect
        capture.flashMode = when {
            frontCamera && !extensionActive && cameraPreferences.selfieFlash && capture.screenFlash != null &&
                flashMode != ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_SCREEN
            !frontCamera && hasFlash -> flashMode
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }
    LaunchedEffect(boundCamera, cameraPreferences.lowLight, requestedVideoHdr, extensionActive, flashMode) {
        val camera = boundCamera ?: return@LaunchedEffect
        if (!camera.cameraInfo.isLowLightBoostSupported || extensionActive) return@LaunchedEffect
        val enable = cameraPreferences.lowLight && !requestedVideoHdr && flashMode == ImageCapture.FLASH_MODE_OFF
        val future = camera.cameraControl.enableLowLightBoostAsync(enable)
        future.addListener({ try { future.get() } catch (_: Exception) {
            // Some stream combinations cannot enable low-light boost.
        } }, ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(recording) {
        val activeRecording = recording ?: return@LaunchedEffect
        // Standard account duration from the checked-in API spec.
        delay(2500)
        activeRecording.stop()
    }

    LaunchedEffect(recordingStartedAt) {
        val startedAt = recordingStartedAt
        if (startedAt == 0L) return@LaunchedEffect
        while (true) {
            recordingElapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceIn(0L, 2500L)
            delay(50)
        }
    }

    fun takePhoto() {
        val capture = imageCapture
        if (capture == null || holdingShutter || videoSession || recording != null || processingVideo || capturingPhoto || capturedBitmap != null) return
        capturingPhoto = true
        val captureStarted = SystemClock.elapsedRealtime()
        val lens = if (frontCamera) "Camera trước" else "Camera sau"
        val zoomAtCapture = zoomLimits?.zoomRatio
        val captureMode = if (extensionActive) "CameraX Extension (${cameraPreferences.extension})" else "CameraX ImageCapture"
        fun details(mode: String): String = buildString {
            append("$lens · $mode")
            zoomAtCapture?.let { append("\nZoom camera: ${String.format(java.util.Locale.US, "%.2f", it)}×") }
            append("\nThời gian chụp/xử lý: ${SystemClock.elapsedRealtime() - captureStarted} ms")
        }

        // Normal photos use the same transformed frame the user sees.
        // Never silently switch to a differently processed still photograph.
        val wantsStill = capture.flashMode != ImageCapture.FLASH_MODE_OFF ||
            flashMode != ImageCapture.FLASH_MODE_OFF || extensionActive
        if (!wantsStill) {
            val started = SystemClock.elapsedRealtime()
            if (previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) {
                capturingPhoto = false
                Toast.makeText(context, "Camera chưa sẵn sàng, hãy thử lại", Toast.LENGTH_SHORT).show()
                return
            }
            var frame: Bitmap? = null
            var result: Bitmap? = null
            try {
                frame = previewView.bitmap
                if (frame == null || !hasPreviewPixels(frame)) {
                    Toast.makeText(context, "Không lấy được khung camera, hãy thử lại", Toast.LENGTH_SHORT).show()
                } else {
                    result = if (frontCamera && !cameraPreferences.mirror) {
                        Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height,
                            Matrix().apply { setScale(-1f, 1f) }, true)
                    } else frame
                    // Publish immediately; do not queue a second capture or JPEG encode.
                    if (cameraPreferences.showPhotoInfo) captureDetails = details("PreviewView bitmap")
                    capturedOriginalJpeg = null
                    capturedBitmap = result
                    android.util.Log.d("LocketCamera", "preview capture ${SystemClock.elapsedRealtime() - started}ms, ${result.width}x${result.height}")
                }
            } catch (e: Exception) {
                android.util.Log.w("LocketCamera", "Preview capture failed", e)
                Toast.makeText(context, "Không thể chụp ảnh, hãy thử lại", Toast.LENGTH_SHORT).show()
            } finally {
                if (frame !== result) frame?.recycle()
                capturingPhoto = false
            }
            return
        }

        // Real still capture is reserved for explicitly selected flash or
        // camera extensions; those intentionally can differ from the preview.
        val mirrorPhoto = frontCamera && cameraPreferences.mirror
        val mainExecutor = ContextCompat.getMainExecutor(context)
        capture.takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var photo: Bitmap? = null
                var originalJpeg: ByteArray? = null
                var failure: Exception? = null
                try {
                    val buffer = image.planes[0].buffer.duplicate()
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val full = imageProxyToBitmap(image, mirrorPhoto, bytes)
                    originalJpeg = bytes
                    try {
                        photo = full
                    } finally {
                        if (photo !== full) full.recycle()
                    }
                } catch (e: Exception) {
                    failure = e
                } finally {
                    image.close()
                }
                val result = photo
                val sourceJpeg = originalJpeg
                val error = failure
                mainExecutor.execute {
                    capturingPhoto = false
                    if (screenActive && result != null && error == null) {
                        if (cameraPreferences.showPhotoInfo) captureDetails = details(captureMode)
                        capturedOriginalJpeg = sourceJpeg
                        capturedBitmap = result
                    } else {
                        result?.recycle()
                        if (screenActive) Toast.makeText(context, "Không thể đọc ảnh: ${error?.message ?: "lỗi không xác định"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute {
                    capturingPhoto = false
                    if (screenActive) Toast.makeText(context, "Lỗi chụp: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    fun restorePhotoSession() {
        if (videoSession) {
            resumeZoom = boundCamera?.cameraInfo?.zoomState?.value?.zoomRatio ?: resumeZoom
            videoSession = false
        }
    }

    fun startVideo() {
        if (!holdingShutter || recording != null || processingVideo || capturingPhoto || capturedBitmap != null) return
        val capture = videoCapture
        if (capture == null) {
            holdingShutter = false
            restorePhotoSession()
            Toast.makeText(context, "Camera này chưa sẵn sàng quay video", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File.createTempFile("locket-video-", ".mp4", context.cacheDir)
        try {
            var pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            if (hasAudioPermission && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }
            recordingElapsedMs = 0L
            recordingStartedAt = 0L
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Start) {
                    recordingStartedAt = SystemClock.elapsedRealtime()
                    if (!holdingShutter) recording?.stop()
                }
                if (event is VideoRecordEvent.Finalize) {
                    recordingStartedAt = 0L
                    recording = null
                    holdingShutter = false
                    if (screenActive) restorePhotoSession()
                    if (!screenActive || event.hasError()) {
                        file.delete()
                        if (screenActive) Toast.makeText(context, "Không thể quay video (${event.error})", Toast.LENGTH_SHORT).show()
                    } else {
                        processingVideo = true
                        scope.launch {
                            try {
                                val thumbnail = withContext(Dispatchers.IO) {
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(file.absolutePath)
                                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    } finally { retriever.release() }
                                }
                                if (thumbnail == null || !screenActive) {
                                    file.delete()
                                    if (screenActive) Toast.makeText(context, "Không đọc được video", Toast.LENGTH_SHORT).show()
                                } else {
                                    capturedVideo = file
                                    capturedBitmap = thumbnail
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                file.delete()
                                throw e
                            } catch (e: Exception) {
                                file.delete()
                                Toast.makeText(context, "Không đọc được video: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally { processingVideo = false }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            holdingShutter = false
            restorePhotoSession()
            Toast.makeText(context, "Lỗi quay video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // A hold can temporarily switch from photo extensions to a normal video
    // session (or HDR video). Never start recording after the finger is lifted.
    LaunchedEffect(holdingShutter, videoSession, boundVideoSession, boundCamera, videoCapture, cameraError) {
        if (!holdingShutter || recording != null) return@LaunchedEffect
        if (videoSession && !boundVideoSession && cameraError == null) return@LaunchedEffect
        if (boundCamera == null && cameraError == null) return@LaunchedEffect
        startVideo()
    }
    val openLatest = {
        if (!holdingShutter && recording == null && !processingVideo && !capturingPhoto && capturedBitmap == null) {
            viewModel.selectFriend(null)
            onNavigate("latest")
        }
    }
    val tapShutter by rememberUpdatedState<() -> Unit>({ takePhoto() })
    val beginHold by rememberUpdatedState<() -> Unit>({
        if (recording == null && !holdingShutter && !processingVideo && !capturingPhoto &&
            capturedBitmap == null && boundCamera != null) {
            if (!hasAudioPermission && !audioPermissionAsked) {
                audioPermissionAsked = true
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                holdingShutter = true
                if (cameraPreferences.extension != ExtensionMode.NONE || cameraPreferences.videoHdr) {
                    resumeZoom = cameraZoomRatio
                    videoSession = true
                }
            }
        }
    })
    val endHold by rememberUpdatedState<() -> Unit>({
        holdingShutter = false
        val activeRecording = recording
        if (activeRecording != null) activeRecording.stop()
        else restorePhotoSession()
    })

    // Gallery Picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bmp = context.contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) }
                if (bmp != null) {
                    clearCapture()
                    if (cameraPreferences.showPhotoInfo) captureDetails = "Ảnh từ thư viện"
                    capturedBitmap = bmp
                } else Toast.makeText(context, "Không thể đọc ảnh", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Không thể đọc ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val review = capturedBitmap?.let { bitmap ->
        val isPhoto = capturedVideo == null
        val prepared = remember(bitmap, isPhoto) {
            if (isPhoto) viewModel.prepareMomentThumbnail(bitmap) else null
        }
        var persistingUpload by remember(bitmap) { mutableStateOf(false) }
        val transferred = remember(bitmap) { booleanArrayOf(false) }
        DisposableEffect(prepared) {
            onDispose { if (!transferred[0]) prepared?.cancel() }
        }
        var snapshotFile by remember(bitmap, isPhoto) { mutableStateOf<File?>(null) }
        LaunchedEffect(bitmap, prepared) {
            if (prepared == null) return@LaunchedEffect
            var temp: File? = null
            try {
                // Display the frozen real frame immediately while its JPEG
                // snapshot and independent upload thumbnail are prepared.
                try {
                    withContext(Dispatchers.IO) {
                        val file = File.createTempFile("locket-snapshot-", ".jpg", context.cacheDir)
                        temp = file
                        file.outputStream().use { out ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                        }
                    }
                    snapshotFile = temp
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    temp?.delete()
                    temp = null
                    android.util.Log.w("LocketCamera", "JPEG snapshot cache failed", e)
                }
                awaitCancellation()
            } finally {
                temp?.delete()
            }
        }
        val moments by viewModel.moments.collectAsState()
        // Fetch the real personal history once if initial sync has not covered it.
        // Never synthesize a streak when no moment data is available.
        LaunchedEffect(currentUser.uid) {
            if (currentUser.uid.isNotBlank() &&
                viewModel.ownHistoryCoveredThrough.value == null &&
                viewModel.ownHistoryHasMore.value &&
                !viewModel.ownHistoryLoading.value &&
                moments.none { it.userUid == currentUser.uid }) {
                viewModel.loadOwnHistory()
            }
        }
        val ownStreak = remember(moments, currentUser.uid) {
            viewModel.calculateStreak(moments.filter { it.userUid == currentUser.uid })
        }
        rememberMomentReviewSlots(
            bitmap = bitmap,
            snapshotFile = snapshotFile,
            originalJpeg = capturedOriginalJpeg,
            showPhotoInfo = cameraPreferences.showPhotoInfo,
            captureDetails = captureDetails,
            preparedThumbnail = prepared,
            videoFile = capturedVideo,
            friends = friends,
            replyToFriend = replyToFriend,
            streak = ownStreak,
            isPosting = persistingUpload,
            onCancel = {
                clearCapture()
                viewModel.setReplyToFriend(null)
            },
            onSend = { caption, recipients ->
                if (!persistingUpload) {
                    persistingUpload = true
                    val video = capturedVideo
                    scope.launch {
                        try {
                            if (viewModel.enqueueMoment(bitmap, caption, recipients, video, prepared)) {
                                // Only dismiss after the exact upload payload is durable.
                                transferred[0] = true
                                clearCapture()
                                viewModel.setReplyToFriend(null)
                                Toast.makeText(context, "Đã thêm vào hàng đợi gửi", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, "Hàng đợi đầy hoặc chưa đăng nhập", Toast.LENGTH_SHORT).show()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Không lưu được hàng đợi", Toast.LENGTH_LONG).show()
                        } finally { persistingUpload = false }
                    }
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (sharedHeaderHeight == null) Modifier.background(AppearancePrefs.cameraBrush(AppearancePrefs.cameraTheme(context))) else Modifier)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar (Matches 02-camera.jpg with real friend count and user avatar)
            val friendsLabel = "${friends.size} Bạn bè"
            if (sharedHeaderHeight != null || review != null) {
                val height = sharedHeaderHeight ?: (66.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                Box(Modifier.fillMaxWidth().height(height)) {
                    review?.header?.invoke()
                }
            } else LocketTopBar(
                title = friendsLabel,
                avatarUrl = currentUser.profilePictureUrl,
                onSpeakerClick = {
                    viewModel.syncAllData()
                    Toast.makeText(context, "Đang đồng bộ dữ liệu mới nhất từ Locket...", Toast.LENGTH_SHORT).show()
                },
                onTitleClick = onOpenFriends,
                onAvatarClick = onOpenProfile
            )

            Spacer(modifier = Modifier.weight(0.55f))

            // Main Squircle Camera Viewfinder (Matches 02-camera.jpg)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.0f)
                    .clip(RoundedCornerShape(48.dp))
                    .background(Color(0xFF1E1E1E))
            ) {
                if (hasCameraPermission) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { previewView }
                    )
                }
                if (review != null) {
                    review.frame()
                } else {
                if (hasCameraPermission) {
                    // This transparent gesture surface is below the controls, so tapping
                    // flash or a quick-zoom button never also meters the camera.
                    Box(Modifier.matchParentSize().pointerInput(boundCamera) {
                        val camera = boundCamera ?: return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var scaling = false
                            var moved = false
                            var requestedZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: cameraZoomRatio
                            val range = exposureRange
                            val adjusting = range != null && focusPoint != null &&
                                down.position.x >= size.width - 64.dp.toPx() &&
                                abs(down.position.y - size.height / 2f) <= 82.dp.toPx()
                            val initialExposure = camera.cameraInfo.exposureState.exposureCompensationIndex
                            if (adjusting) {
                                exposureDragging = true
                                exposureIndex = initialExposure
                                down.consume()
                            }
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pointer = event.changes.firstOrNull { it.id == down.id }
                                    if (event.changes.count { it.pressed } >= 2 && !adjusting) {
                                        if (!scaling) {
                                            scaling = true
                                            pinchingCamera = true
                                            focusPoint = null
                                            requestedZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: cameraZoomRatio
                                        }
                                        val limits = camera.cameraInfo.zoomState.value
                                        val factor = event.calculateZoom()
                                        if (limits != null && factor.isFinite() && factor > 0f) {
                                            val next = (requestedZoom * factor).coerceIn(limits.minZoomRatio, limits.maxZoomRatio)
                                            if (abs(next - requestedZoom) > 0.001f) {
                                                requestedZoom = next
                                                requestZoom(camera, next)
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (scaling) {
                                        event.changes.forEach { it.consume() }
                                    } else if (adjusting && range != null) {
                                        if (pointer != null) {
                                            val delta = (down.position.y - pointer.position.y) / 120.dp.toPx()
                                            val next = (initialExposure + delta * (range.last - range.first)).roundToInt()
                                            requestExposure(camera, next)
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (pointer != null) {
                                        if ((pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                        // Do not intercept ordinary one-finger history swipes.
                                        if (!pointer.pressed && !moved && !pointer.isConsumed &&
                                            event.changes.size == 1 &&
                                            event.changes.none { it.id != down.id }) {
                                            meterAt(camera, pointer.position)
                                            pointer.consume()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            } finally {
                                pinchingCamera = false
                                exposureDragging = false
                                if (scaling) cameraZoomRatio = camera.cameraInfo.zoomState.value?.zoomRatio ?: requestedZoom
                                if (adjusting) focusSerial++
                            }
                        }
                    })

                    val point = focusPoint
                    if (point != null && focusRegionSupported) {
                        Canvas(Modifier.matchParentSize()) {
                            val half = 24.dp.toPx()
                            val x = point.x.coerceIn(half, (size.width - half).coerceAtLeast(half))
                            val y = point.y.coerceIn(half, (size.height - half).coerceAtLeast(half))
                            drawRoundRect(
                                color = LocketYellow,
                                topLeft = Offset(x - half, y - half),
                                size = Size(half * 2, half * 2),
                                cornerRadius = CornerRadius(5.dp.toPx()),
                                style = Stroke(1.5.dp.toPx())
                            )
                        }
                    }
                    val range = exposureRange
                    if (point != null && range != null) {
                        Column(
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.WbSunny, contentDescription = "Vuốt lên xuống để chỉnh sáng",
                                tint = LocketYellow, modifier = Modifier.size(18.dp))
                            Canvas(Modifier.width(24.dp).height(96.dp)) {
                                val x = size.width / 2
                                val fraction = ((exposureIndex - range.first).toFloat() /
                                    (range.last - range.first)).coerceIn(0f, 1f)
                                val top = 5.dp.toPx()
                                val bottom = size.height - top
                                drawLine(Color.White.copy(alpha = 0.55f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                                drawCircle(LocketYellow, 4.dp.toPx(), Offset(x, bottom - fraction * (bottom - top)))
                            }
                        }
                    }

                    val camera = boundCamera
                    val limits = zoomLimits
                    if (camera != null && limits != null && limits.maxZoomRatio - limits.minZoomRatio > 0.01f) {
                        val presets = remember(limits.minZoomRatio, limits.maxZoomRatio) {
                            cameraQuickZoomRatios(limits.minZoomRatio, limits.maxZoomRatio)
                        }
                        if (pinchingCamera) {
                            Text(formatCameraZoom(cameraZoomRatio), color = Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                    .padding(horizontal = 12.dp, vertical = 6.dp))
                        } else {
                            Row(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                presets.forEach { ratio ->
                                    val selected = abs(cameraZoomRatio - ratio) < 0.04f
                                    Box(Modifier.size(40.dp, 34.dp).clip(CircleShape)
                                        .background(if (selected) LocketYellow else Color.Transparent)
                                        .clickable { requestZoom(camera, ratio) }
                                        .semantics { this.selected = selected },
                                        contentAlignment = Alignment.Center) {
                                        Text(formatCameraZoom(ratio), color = if (selected) Color.Black else Color.White,
                                            fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (presets.none { abs(cameraZoomRatio - it) < 0.04f }) {
                                    Text(formatCameraZoom(cameraZoomRatio), color = LocketYellow,
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp))
                                }
                            }
                        }
                    }
                    cameraError?.let { error ->
                        Text(error, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LocketSheetBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = LocketYellow,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Chạm để cấp quyền Camera",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = LocketYellow)
                            ) {
                                Text("Cấp quyền", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Flash Icon Toggle in Top-Left of Viewfinder
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .clickable(enabled = (hasFlash || (frontCamera && cameraPreferences.selfieFlash && !extensionActive && activity != null)) &&
                            !holdingShutter && recording == null && !capturingPhoto) {
                            flashMode = if (frontCamera && cameraPreferences.selfieFlash) {
                                if (flashMode == ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                            } else when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                        }
                        .align(Alignment.TopStart),
                    contentAlignment = Alignment.Center
                ) {
                    val flashIcon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Outlined.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Outlined.FlashAuto
                        else -> Icons.Outlined.FlashOff
                    }
                    Icon(
                        imageVector = flashIcon,
                        contentDescription = "Flash",
                        tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) LocketYellow else Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Keep zoom at the bottom; move the recording status out of its way.
                if (recording != null || holdingShutter) {
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                            .clip(RoundedCornerShape(24.dp)).background(LocketPillBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(9.dp).background(LocketRed, CircleShape))
                        Text(
                            text = if (recordingStartedAt == 0L) "Đang chuẩn bị..." else
                                java.lang.String.format(java.util.Locale.ROOT, "%d.%d s",
                                    recordingElapsedMs / 1000, recordingElapsedMs / 100 % 10),
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                review?.indicators?.invoke()
            }

            // Shutter and review controls occupy the same 98 dp row.
            if (review != null) {
                review.controls()
            } else Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = chromeOffsetPx }
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Photo stack / Gallery picker button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .locketHeaderTransition { chromeProgress }
                        .clickable(enabled = chromeProgress >= 0.999f && !holdingShutter && recording == null && !processingVideo && !capturingPhoto) { galleryLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = "Thư viện",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }

                // Center: Shutter Button
                Box(
                    modifier = Modifier
                        .size(98.dp)
                        .locketHeaderTransition { chromeProgress }
                        .border(4.dp, if (recording != null) Color.Red else LocketYellow, CircleShape)
                        .semantics {
                            contentDescription = "Chụp ảnh, giữ để quay video"
                            onClick { if (chromeProgress >= 0.999f) { tapShutter(); true } else false }
                        }
                        .then(if (chromeProgress >= 0.999f) Modifier.pointerInput(Unit) {
                            var held = false
                            detectTapGestures(
                                onPress = {
                                    held = false
                                    try { tryAwaitRelease() } finally { if (held) endHold() }
                                },
                                onLongPress = { held = true; beginHold() },
                                onTap = { tapShutter() }
                            )
                        } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(if (recording != null) 40.dp else 82.dp)
                        .background(if (recording != null || holdingShutter) Color.Red else Color.White,
                            if (recording != null) RoundedCornerShape(10.dp) else CircleShape))
                }

                // Right: Camera Flip Button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .locketHeaderTransition { chromeProgress }
                        .clickable(enabled = chromeProgress >= 0.999f && !holdingShutter && recording == null && !processingVideo && !capturingPhoto) {
                            frontCamera = !frontCamera
                            flashMode = ImageCapture.FLASH_MODE_OFF
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Đổi camera",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                if (review == null) Row(
                    modifier = Modifier.graphicsLayer { translationY = chromeOffsetPx }
                        .locketHeaderTransition { chromeProgress }
                        .clickable(enabled = chromeProgress >= 0.999f) { openLatest() }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Lịch sử", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.weight(0.55f))

            // Bottom Navigation Bar
            if (sharedFooterHeight != null || review != null) {
                Spacer(Modifier.height(sharedFooterHeight ?: (70.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())))
            } else LocketBottomBar(
                currentScreen = "camera",
                onNavigate = onNavigate
            )
        }

        if (review != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 54.dp)) {
                review.recipients()
            }
        }
    }
}

// Ratios are hardware-reported zoom bounds, not a claim of optical lens availability.
private fun cameraQuickZoomRatios(min: Float, max: Float): List<Float> {
    val values = mutableListOf<Float>()
    if (min < 0.99f) values += min
    values += listOf(1f, 2f, 3f, 5f).filter { it in min..max }
    if (values.isEmpty()) values += min
    if (max > values.last() + 0.1f && values.size < 5) values += max
    return values.distinct()
}

private fun formatCameraZoom(ratio: Float): String =
    String.format(java.util.Locale.US, "%.1f", ratio).removeSuffix(".0") + "×"

private fun imageProxyToBitmap(image: ImageProxy, mirror: Boolean, bytes: ByteArray): Bitmap {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("Không giải mã được ảnh JPEG")

    // ImageProxy.cropRect is expressed in the unrotated buffer coordinates.
    val rect = image.cropRect
    val scaleX = source.width.toFloat() / image.width
    val scaleY = source.height.toFloat() / image.height
    val left = (rect.left * scaleX).toInt().coerceIn(0, source.width - 1)
    val top = (rect.top * scaleY).toInt().coerceIn(0, source.height - 1)
    val right = (rect.right * scaleX).toInt().coerceIn(left + 1, source.width)
    val bottom = (rect.bottom * scaleY).toInt().coerceIn(top + 1, source.height)
    // Center-crop in sensor coordinates first (square is rotation invariant).
    // Combine rotation and optional horizontal mirror in one bitmap operation.
    val side = minOf(right - left, bottom - top)
    val x = left + (right - left - side) / 2
    val y = top + (bottom - top - side) / 2
    val transform = Matrix().apply {
        postRotate(image.imageInfo.rotationDegrees.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    return try {
        Bitmap.createBitmap(source, x, y, side, side, transform, true).also {
            if (it !== source) source.recycle()
        }
    } catch (e: Exception) {
        source.recycle()
        throw e
    }
}


private const val PHOTO_SIDE = 720

private fun cameraPhotoResolutionSelector(): ResolutionSelector =
    ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(AndroidSize(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        )
        .build()

// CameraX may return an empty transparent bitmap when the surface copy fails.
// Opaque black is a valid camera frame, e.g. in a dark room.
private fun hasPreviewPixels(bitmap: Bitmap): Boolean =
    bitmap.width > 0 && bitmap.height > 0 &&
        (bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) ushr 24 != 0 ||
            bitmap.getPixel(bitmap.width / 4, bitmap.height / 4) ushr 24 != 0 ||
            bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4) ushr 24 != 0)

private fun cameraSquare720(bitmap: Bitmap, mirror: Boolean = false): Bitmap {
    val side = minOf(bitmap.width, bitmap.height)
    val target = minOf(PHOTO_SIDE, side)
    val scale = target.toFloat() / side
    val matrix = Matrix().apply {
        setScale(if (mirror) -scale else scale, scale)
    }
    return Bitmap.createBitmap(
        bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2,
        side, side, matrix, true
    )
}
