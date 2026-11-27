package com.locket.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import android.media.MediaMetadataRetriever
import android.widget.VideoView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun CameraScreen(
    viewModel: LocketViewModel,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit,
    onSwipeEnabledChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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
    var hasFlash by remember { mutableStateOf(false) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoMode by remember { mutableStateOf(false) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var processingVideo by remember { mutableStateOf(false) }
    var capturingPhoto by remember { mutableStateOf(false) }
    var capturedVideo by remember { mutableStateOf<File?>(null) }
    var screenActive by remember { mutableStateOf(true) }
    val previewView = remember(context) {
        PreviewView(context).apply {
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
    var captionText by remember { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }
    val canSwipe = recording == null && !processingVideo && !capturingPhoto && capturedBitmap == null && !isPosting
    SideEffect { onSwipeEnabledChange(canSwipe) }

    fun clearCapture() {
        capturedBitmap = null
        capturedVideo?.delete()
        capturedVideo = null
        captionText = ""
    }

    DisposableEffect(Unit) {
        screenActive = true
        onDispose {
            screenActive = false
            recording?.stop()
            if (!isPosting) capturedVideo?.delete()
        }
    }

    // Rebind whenever the selected lens changes; AndroidView.factory runs only once.
    DisposableEffect(hasCameraPermission, cameraSelector, lifecycleOwner, previewView) {
        var active = true
        var provider: ProcessCameraProvider? = null
        imageCapture = null
        videoCapture = null
        hasFlash = false
        cameraError = null
        if (hasCameraPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (active) {
                    try {
                        val cameraProvider = future.get()
                        provider = cameraProvider
                        if (!cameraProvider.hasCamera(cameraSelector)) {
                            cameraError = "Thiết bị không có camera này"
                        } else {
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                            // Match the square FILL_CENTER preview with the actual captured sensor region.
                            val viewPort = ViewPort.Builder(Rational(1, 1), rotation)
                                .setScaleType(ViewPort.FILL_CENTER)
                                .build()
                            val photo = ImageCapture.Builder()
                                .setTargetRotation(rotation)
                                .setFlashMode(if (frontCamera) ImageCapture.FLASH_MODE_OFF else flashMode)
                                .build()
                            val video = VideoCapture.withOutput(
                                Recorder.Builder().setQualitySelector(
                                    QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
                                ).build()
                            )
                            cameraProvider.unbindAll()
                            val camera = try {
                                val group = UseCaseGroup.Builder()
                                    .setViewPort(viewPort)
                                    .addUseCase(preview)
                                    .addUseCase(photo)
                                    .addUseCase(video)
                                    .build()
                                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, group)
                                    .also { videoCapture = video }
                            } catch (_: IllegalArgumentException) {
                                // Some cameras cannot bind three use cases at once.
                                cameraProvider.unbindAll()
                                val group = UseCaseGroup.Builder()
                                    .setViewPort(viewPort)
                                    .addUseCase(preview)
                                    .addUseCase(photo)
                                    .build()
                                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, group)
                            }
                            hasFlash = camera.cameraInfo.hasFlashUnit()
                            imageCapture = photo
                        }
                    } catch (e: Exception) {
                        cameraError = "Không thể mở camera: ${e.localizedMessage ?: "lỗi không xác định"}"
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            active = false
            provider?.unbindAll()
        }
    }
    LaunchedEffect(flashMode, hasFlash, imageCapture) {
        imageCapture?.flashMode = if (hasFlash) flashMode else ImageCapture.FLASH_MODE_OFF
    }
    LaunchedEffect(recording) {
        val activeRecording = recording ?: return@LaunchedEffect
        // Standard account duration from the checked-in API spec.
        delay(2500)
        activeRecording.stop()
    }

    fun takePhoto() {
        val capture = imageCapture
        if (capture == null || recording != null || processingVideo || capturingPhoto || capturedBitmap != null) return
        capturingPhoto = true
        val mirrorPhoto = frontCamera
        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = imageProxyToBitmap(image, mirrorPhoto)
                    if (screenActive) capturedBitmap = bitmap
                } catch (e: Exception) {
                    if (screenActive) Toast.makeText(context, "Không thể đọc ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    image.close()
                    capturingPhoto = false
                }
            }
            override fun onError(exception: ImageCaptureException) {
                capturingPhoto = false
                if (screenActive) Toast.makeText(context, "Lỗi chụp: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun startVideo() {
        if (recording != null || processingVideo || capturingPhoto || capturedBitmap != null) return
        val capture = videoCapture
        if (capture == null) {
            Toast.makeText(context, "Camera này chưa sẵn sàng quay video", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File.createTempFile("locket-video-", ".mp4", context.cacheDir)
        try {
            var pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            if (hasAudioPermission && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
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
            Toast.makeText(context, "Lỗi quay video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val openLatest = {
        if (recording == null && !processingVideo && !capturingPhoto && capturedBitmap == null) {
            viewModel.selectFriend(null)
            onNavigate("latest")
        }
    }
    val tapShutter by rememberUpdatedState<() -> Unit>({
        if (videoMode) {
            if (recording == null) startVideo() else recording?.stop()
        } else takePhoto()
    })
    val beginHold by rememberUpdatedState<() -> Unit>({ startVideo() })
    val endHold by rememberUpdatedState<() -> Unit>({ recording?.stop() })

    // Gallery Picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bmp = context.contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) }
                if (bmp != null) {
                    clearCapture()
                    capturedBitmap = bmp
                } else Toast.makeText(context, "Không thể đọc ảnh", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Không thể đọc ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocketBlack)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar (Matches 02-camera.jpg with real friend count and user avatar)
            val friendsLabel = "${friends.size} Bạn bè"
            LocketTopBar(
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
                    cameraError?.let { error ->
                        Text(error, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF242426)),
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
                        .clickable(enabled = hasFlash && recording == null) {
                            flashMode = when (flashMode) {
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

                // Mode Selector Pill (Bottom inside Viewfinder)
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .liquidGlassEffect(RoundedCornerShape(20.dp), backgroundColor = Color(0xD92C2C2E))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (!videoMode) LocketYellow else Color.Transparent)
                            .clickable(enabled = recording == null) { videoMode = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Ảnh",
                            tint = if (!videoMode) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (videoMode) LocketYellow else Color.Transparent)
                            .clickable(enabled = recording == null) {
                                videoMode = true
                                if (!hasAudioPermission) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video",
                            tint = if (videoMode) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Shutter & Controls Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Photo stack / Gallery picker button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable(enabled = recording == null && !processingVideo && !capturingPhoto) { galleryLauncher.launch("image/*") },
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
                        .border(4.dp, if (recording != null) Color.Red else LocketYellow, CircleShape)
                        .semantics {
                            contentDescription = if (videoMode) "Quay video" else "Chụp ảnh, giữ để quay video"
                            onClick { tapShutter(); true }
                        }
                        .pointerInput(Unit) {
                            var held = false
                            detectTapGestures(
                                onPress = {
                                    held = false
                                    try { tryAwaitRelease() } finally { if (held) endHold() }
                                },
                                onLongPress = { held = true; beginHold() },
                                onTap = { tapShutter() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(if (recording != null) 40.dp else 82.dp)
                        .background(if (recording != null || videoMode) Color.Red else Color.White,
                            if (recording != null) RoundedCornerShape(10.dp) else CircleShape))
                }

                // Right: Camera Flip Button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable(enabled = recording == null && !processingVideo && !capturingPhoto) {
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
            Row(
                modifier = Modifier.clickable { openLatest() }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Lịch sử", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.weight(0.55f))

            // Bottom Navigation Bar
            LocketBottomBar(
                currentScreen = "camera",
                onNavigate = onNavigate
            )
        }

        // Post Moment Review Dialog Sheet (Uploads directly via Locket API)
        if (capturedBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .liquidGlassEffect(RoundedCornerShape(32.dp), backgroundColor = Color(0xF018181A))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Gửi khoảnh khắc",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                if (!isPosting) {
                                    clearCapture()
                                    viewModel.setReplyToFriend(null)
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Hủy",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Photo Preview in Squircle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.0f)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color.Black)
                    ) {
                        val videoFile = capturedVideo
                        if (videoFile != null) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx -> VideoView(ctx).apply {
                                    setVideoPath(videoFile.absolutePath)
                                    setOnPreparedListener { player -> player.isLooping = true; start() }
                                    setOnErrorListener { _, _, _ ->
                                        Toast.makeText(ctx, "Không phát được video", Toast.LENGTH_SHORT).show()
                                        true
                                    }
                                } },
                                onRelease = { it.stopPlayback() }
                            )
                        } else capturedBitmap?.let { bmp ->
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Captured",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Caption Input
                    OutlinedTextField(
                        value = captionText,
                        onValueChange = { captionText = it },
                        placeholder = { Text("Thêm chú thích...", color = LocketTextDim) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = LocketYellow,
                            unfocusedBorderColor = Color(0x44FFFFFF),
                            focusedContainerColor = Color(0x33000000),
                            unfocusedContainerColor = Color(0x33000000)
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Dang tra loi rieng 1 ban: anh nay chi gui cho ban do
                    replyToFriend?.let { target ->
                        val firstName = target.displayName.trim().split("\\s+".toRegex()).lastOrNull()
                            ?: target.displayName
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Reply,
                                contentDescription = null,
                                tint = LocketYellow,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Đang trả lời $firstName",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.setReplyToFriend(null) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Hủy trả lời riêng",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Post Button (Calls real Locket API postMomentV2)
                    Button(
                        onClick = {
                            val bmp = capturedBitmap ?: return@Button
                            isPosting = true
                            viewModel.postMoment(
                                bitmap = bmp,
                                videoFile = capturedVideo,
                                caption = captionText,
                                recipients = replyToFriend?.let { listOf(it.uid) } ?: emptyList(),
                                onSuccess = {
                                    isPosting = false
                                    clearCapture()
                                    viewModel.setReplyToFriend(null)
                                    Toast.makeText(context, "Đã gửi khoảnh khắc lên Locket thành công! 💛", Toast.LENGTH_LONG).show()
                                    onNavigate("feed")
                                },
                                onError = { err ->
                                    isPosting = false
                                    Toast.makeText(context, "Lỗi đăng: $err", Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        enabled = !isPosting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocketYellow,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(26.dp)
                    ) {
                        if (isPosting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.Black,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Gửi khoảnh khắc 💛",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy, mirror: Boolean): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
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
    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    if (cropped !== source) source.recycle()

    val rotation = image.imageInfo.rotationDegrees
    val upright = if (rotation != 0) {
        Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
    } else cropped
    if (upright !== cropped) cropped.recycle()

    // Front PreviewView is mirrored by CameraX; match that orientation in the
    // saved photo, not merely in the review UI. Rear camera remains unmirrored.
    val size = minOf(upright.width, upright.height)
    val square = Bitmap.createBitmap(upright, (upright.width - size) / 2,
        (upright.height - size) / 2, size, size)
    if (square !== upright) upright.recycle()
    if (!mirror) return square
    val mirrored = Bitmap.createBitmap(square, 0, 0, square.width, square.height,
        Matrix().apply { setScale(-1f, 1f) }, true)
    if (mirrored !== square) square.recycle()
    return mirrored
}
