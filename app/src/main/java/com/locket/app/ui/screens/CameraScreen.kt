package com.locket.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    viewModel: LocketViewModel,
    onNavigate: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenProfile: () -> Unit
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

    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isRollcallMode by remember { mutableStateOf(false) }

    // State for captured or selected photo review
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var captionText by remember { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }

    // Gallery Picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(it)
                val bmp = BitmapFactory.decodeStream(inputStream)
                capturedBitmap = bmp
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
            val friendsLabel = if (friends.isNotEmpty()) "${friends.size} Bạn bè" else "Thêm bạn bè"
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

            Spacer(modifier = Modifier.height(12.dp))

            // Main Squircle Camera Viewfinder (Matches 02-camera.jpg)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(1.0f)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color(0xFF1E1E1E))
            ) {
                if (hasCameraPermission) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val capture = ImageCapture.Builder()
                                    .setFlashMode(flashMode)
                                    .setTargetRotation(previewView.display.rotation)
                                    .build()
                                imageCapture = capture

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        capture
                                    )
                                } catch (exc: Exception) {
                                    // Binding failed
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        update = {
                            imageCapture?.flashMode = flashMode
                        }
                    )
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
                        .clickable {
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
                            .size(width = 46.dp, height = 36.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (!isRollcallMode) LocketYellow else Color.Transparent)
                            .clickable { isRollcallMode = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Ảnh",
                            tint = if (!isRollcallMode) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 46.dp, height = 36.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isRollcallMode) LocketYellow else Color.Transparent)
                            .clickable { isRollcallMode = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Screenshot,
                            contentDescription = "Rollcall",
                            tint = if (isRollcallMode) Color.Black else Color.White,
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
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF262628))
                        .clickable { galleryLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Thư viện",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Center: Shutter Button
                ShutterButton(
                    onClick = {
                        val capture = imageCapture
                        if (capture != null && hasCameraPermission) {
                            val executor = Executors.newSingleThreadExecutor()
                            capture.takePicture(
                                executor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bmp = imageProxyToBitmap(image)
                                        image.close()
                                        scope.launch {
                                            capturedBitmap = bmp
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        scope.launch {
                                            Toast.makeText(context, "Lỗi chụp: ${exception.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        } else {
                            capturedBitmap = createDemoBitmap()
                        }
                    }
                )

                // Right: Camera Flip Button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF262628))
                        .clickable {
                            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Đổi camera",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // "1 Lịch sử v" pill (Underneath Shutter, matches 02-camera.jpg)
            Row(
                modifier = Modifier
                    .liquidGlassEffect(RoundedCornerShape(20.dp), backgroundColor = Color(0xCC1F1F21))
                    .clickable { onNavigate("feed") }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(LocketYellow, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "1",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Lịch sử",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                                capturedBitmap = null
                                viewModel.setReplyToFriend(null)
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
                        capturedBitmap?.let { bmp ->
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
                                caption = captionText,
                                recipients = replyToFriend?.let { listOf(it.uid) } ?: emptyList(),
                                onSuccess = {
                                    isPosting = false
                                    capturedBitmap = null
                                    captionText = ""
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

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val planeProxy = image.planes[0]
    val buffer = planeProxy.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotationDegrees = image.imageInfo.rotationDegrees
    return if (rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    } else {
        bmp
    }
}

private fun createDemoBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.DKGRAY
    }
    canvas.drawRect(0f, 0f, 600f, 600f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 42f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    canvas.drawText("Locket Photo", 300f, 300f, paint)
    return bitmap
}
