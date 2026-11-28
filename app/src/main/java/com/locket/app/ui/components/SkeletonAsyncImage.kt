package com.locket.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.locket.app.LocketApplication

/**
 * A cheap, static skeleton rather than an animated shimmer per row. No extra
 * network request and no synthetic photo. The existing parent clips its shape.
 */
private object ImageSkeletonPainter : Painter() {
    override val intrinsicSize: Size = Size.Unspecified
    override fun DrawScope.onDraw() {
        if (size.width <= 0f || size.height <= 0f) return
        drawRect(Brush.horizontalGradient(
            colors = listOf(Color(0xFF252527), Color(0xFF38383A), Color(0xFF252527)),
            startX = 0f, endX = size.width
        ))
    }
}

@Composable
internal fun SkeletonAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholder: Painter? = null,
    error: Painter? = null,
    contentScale: ContentScale = ContentScale.Fit,
    onLoading: ((AsyncImagePainter.State.Loading) -> Unit)? = null,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null
) {
    val app = LocalContext.current.applicationContext as? LocketApplication
    // All friend/post images keep their normal Coil model and cache behavior.
    // Only a URL belonging to the signed-in user can resolve to the private file.
    val snapshot = if (app != null && model is String) {
        val current by app.ownAvatarStore.snapshot.collectAsState()
        current
    } else null
    val actualModel = remember(model, snapshot, app) {
        if (app != null && model is String && app.sessionManager.isLoggedIn &&
            app.sessionManager.currentUser.profilePictureUrl == model) {
            val own = snapshot?.takeIf {
                it.owner == app.sessionManager.currentUser.uid && it.requestedUrl == model
            }
            when {
                own?.file?.isFile == true -> own.file
                own == null || own.loading -> null // own-avatar store is already responsible for fetching
                else -> model // normal Coil fallback after a failed refresh with no saved file
            }
        } else model
    }
    AsyncImage(
        model = actualModel,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = placeholder ?: ImageSkeletonPainter,
        error = error ?: ImageSkeletonPainter,
        fallback = placeholder ?: ImageSkeletonPainter,
        contentScale = contentScale,
        onLoading = onLoading,
        onSuccess = onSuccess,
        onError = onError
    )
}
