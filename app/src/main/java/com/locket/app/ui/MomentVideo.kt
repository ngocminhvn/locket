package com.locket.app.ui

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Shared, bounded progressive video cache. Prefetch writes the same cache as playback. */
@OptIn(UnstableApi::class)
internal object MomentVideoCache {
    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val PREFETCH_BYTES = 4L * 1024 * 1024
    private val worker = Executors.newFixedThreadPool(2)
    private var cache: SimpleCache? = null

    @Synchronized
    fun factory(context: Context): CacheDataSource.Factory {
        val app = context.applicationContext
        val shared = cache ?: SimpleCache(File(app.cacheDir, "moment_video"), LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(app)).also { cache = it }
        return CacheDataSource.Factory().setCache(shared)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    suspend fun prefetch(context: Context, url: String) = suspendCancellableCoroutine<Unit> { continuation ->
        if (url.isBlank()) { continuation.resume(Unit); return@suspendCancellableCoroutine }
        val writerRef = java.util.concurrent.atomic.AtomicReference<CacheWriter?>()
        val task = worker.submit {
            try {
                if (!continuation.isActive) return@submit
                val source = factory(context).createDataSource()
                val spec = DataSpec.Builder().setUri(url).setLength(PREFETCH_BYTES).build()
                val writer = CacheWriter(source, spec, null, null)
                writerRef.set(writer)
                if (continuation.isActive) writer.cache() else writer.cancel()
            } catch (_: Exception) {
                // Playback can still fetch directly if prefetch fails or reaches EOF.
            } finally { if (continuation.isActive) continuation.resume(Unit) }
        }
        continuation.invokeOnCancellation { writerRef.get()?.cancel(); task.cancel(true) }
    }
}

/** Transparent TextureView until the decoder reports its first rendered frame.
 * The caller keeps the real thumbnail underneath throughout playback. */
@OptIn(UnstableApi::class)
@Composable
internal fun MomentVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val factory by produceState<DataSource.Factory?>(null, url) {
        value = withContext(Dispatchers.IO) { runCatching { MomentVideoCache.factory(context) }.getOrElse { DefaultDataSource.Factory(context.applicationContext) } }
    }
    val cacheFactory = factory
    if (!foreground || cacheFactory == null) return
    key(url) {
        val player = remember(url, cacheFactory) {
            ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory)).build()
        }
        var rendered by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { rendered = true }
                override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
                override fun onPlayerError(error: PlaybackException) { failed = true; rendered = false }
            }
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(url))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.playWhenReady = true
            player.prepare()
            onDispose { player.removeListener(listener); player.release() }
        }
        Box(modifier) {
            AndroidView(factory = { ctx -> TextureView(ctx).apply {
                alpha = 0f
                player.setVideoTextureView(this)
            } }, modifier = Modifier.fillMaxSize(), update = { view ->
                val width = view.width.toFloat()
                val height = view.height.toFloat()
                val vw = videoSize.width * videoSize.pixelWidthHeightRatio
                val vh = videoSize.height.toFloat()
                if (width > 0 && height > 0 && vw > 0 && vh > 0) {
                    val ratio = (vw / vh) / (width / height)
                    view.setTransform(android.graphics.Matrix().apply {
                        setScale(maxOf(1f, ratio), maxOf(1f, 1f / ratio), width / 2f, height / 2f)
                    })
                }
                view.alpha = if (rendered) 1f else 0f
            })
            if (!rendered && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
            if (failed) Text("Không tải được video", modifier = Modifier.align(Alignment.Center), color = Color.White)
        }
    }
}
