package com.juhao.murexide.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.flyjingfish.openimagelib.OpenImage
import com.flyjingfish.openimagelib.beans.DownloadParams
import com.flyjingfish.openimagelib.beans.OpenImageUrl
import com.flyjingfish.openimagelib.enums.MediaType
import com.flyjingfish.openimagelib.listener.OnPermissionsInterceptListener
import com.juhao.murexide.R
import com.juhao.murexide.utils.hasLegacyWritePermission
import com.juhao.murexide.utils.imageThumbnailUrl
import com.juhao.murexide.utils.requestLegacyStoragePermission
import java.util.WeakHashMap

data class OpenImageItem(
    val originalUrl: String,
    val thumbnailUrl: String = originalUrl,
    val messageId: String? = null,
    val imageId: Long? = null,
    val mediaType: MediaType = MediaType.IMAGE,
    val playbackUrl: String? = null
) : OpenImageUrl {
    override fun getImageUrl(): String = originalUrl

    override fun getVideoUrl(): String = playbackUrl.orEmpty()

    override fun getCoverImageUrl(): String = thumbnailUrl

    override fun getType(): MediaType = mediaType
}

fun imageMessagePreviewItem(
    url: String,
    messageId: String? = null,
    imageId: Long? = null
): OpenImageItem = OpenImageItem(
    originalUrl = url,
    thumbnailUrl = imageThumbnailUrl(url),
    messageId = messageId,
    imageId = imageId
)

fun fullImagePreviewItem(url: String): OpenImageItem = OpenImageItem(originalUrl = url)

fun videoMessagePreviewItem(
    url: String,
    messageId: String? = null,
    sequence: Long? = null
): OpenImageItem = OpenImageItem(
    originalUrl = url,
    thumbnailUrl = url,
    messageId = messageId,
    imageId = sequence,
    mediaType = MediaType.VIDEO,
    playbackUrl = url
)

data class MediaViewerPagination(
    val chatId: String,
    val chatType: Int
)

data class ImageViewerSourceBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val isCropped: Boolean
) {
    internal val isValid: Boolean
        get() = width > 0 && height > 0
}

fun showImageViewer(
    context: Context,
    images: List<OpenImageItem>,
    initialIndex: Int = 0,
    pagination: MediaViewerPagination? = null,
    sourceBounds: ImageViewerSourceBounds? = null
): Boolean {
    val activity = context.findActivity() ?: return false
    if (images.isEmpty()) return false

    val selectedIndex = initialIndex.coerceIn(images.indices)
    val viewerOptions = Bundle().apply {
        putStringArrayList(
            MurexideOpenImageActivity.EXTRA_MEDIA_URLS,
            ArrayList(images.map(OpenImageItem::originalUrl))
        )
        putStringArrayList(
            MurexideOpenImageActivity.EXTRA_MEDIA_MESSAGE_IDS,
            ArrayList(images.map { it.messageId.orEmpty() })
        )
        pagination?.let { options ->
            putString(MurexideOpenImageActivity.EXTRA_CHAT_ID, options.chatId)
            putInt(MurexideOpenImageActivity.EXTRA_CHAT_TYPE, options.chatType)
        }
    }
    val viewer = OpenImage.with(activity)
        .setImageUrlList(images)
        // Keep the first frame as a thumbnail, then let OpenImage load the
        // original image.  Keep one page on either side warm while swiping.
        .setBothLoadCover(true)
        .setPreloadCount(false, 1)
        .setOpenPageAnimTimeMs(WECHAT_TRANSITION_DURATION_MS)
        .setWechatExitFillInEffect(true)
        .setOpenImageStyle(R.style.Theme_Murexide_OpenImage)
        .setOpenImageActivityCls(
            MurexideOpenImageActivity::class.java,
            MurexideOpenImageActivity.EXTRA_VIEWER_OPTIONS,
            viewerOptions
        )
        .setOnPermissionsInterceptListener(object : OnPermissionsInterceptListener {
            override fun hasPermissions(
                openImageActivity: com.flyjingfish.openimagelib.OpenImageActivity,
                permissions: Array<String>
            ): Boolean {
                // MediaStore handles downloads without storage permissions on
                // Android 10 and later. Only legacy devices need WRITE access.
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    hasLegacyWritePermission(openImageActivity)
            }

            override fun requestPermission(
                openImageActivity: com.flyjingfish.openimagelib.OpenImageActivity,
                permissions: Array<String>,
                listener: com.flyjingfish.openimagelib.listener.OnRequestPermissionListener
            ) {
                requestLegacyStoragePermission(openImageActivity) { granted ->
                    listener.onCall(granted)
                }
            }
        })
        .setShowDownload(viewerDownloadParams(activity))
        .setOnItemLongClickListener { _, image, _ ->
            val mediaUrl = if (image.type == MediaType.VIDEO) image.videoUrl else image.imageUrl
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Media URL", mediaUrl))
            Toast.makeText(activity, "链接已复制", Toast.LENGTH_SHORT).show()
        }

    val launchSession = ImageViewerLaunchSession(activity)
    if (!launchSession.start()) return false
    viewer.setOnExitListener(launchSession::close)

    val origin = sourceBounds?.takeIf { it.isValid }
    val decorView = activity.window.decorView
    if (origin != null) {
        captureTransitionSource(activity, decorView, origin) { transitionSource ->
            if (!launchSession.isActive || activity.isFinishing || activity.isDestroyed) {
                transitionSource?.let(::removeTransitionSource)
                launchSession.close()
                return@captureTransitionSource
            }

            if (transitionSource == null) {
                showWithBoundsFallback(viewer, selectedIndex, launchSession)
                return@captureTransitionSource
            }

            if (!launchSession.attachTransitionSource(transitionSource)) {
                removeTransitionSource(transitionSource)
                return@captureTransitionSource
            }

            viewer
                // OpenImage names an ImageView source from its position in this list.
                // Align that position with the selected media so both transition names match.
                .setClickImageViews(List(images.size) { transitionSource })
                .setClickPosition(selectedIndex)
                .setSrcImageViewScaleType(sourceScaleType(origin), true)

            // A newly attached shared element can be laid out before Android has committed
            // its first frame. Launch on the following frame so the transition is reliable.
            transitionSource.doOnPreDraw {
                transitionSource.postOnAnimation {
                    if (
                        launchSession.isActive &&
                        transitionSource.isAttachedToWindow &&
                        !activity.isFinishing &&
                        !activity.isDestroyed
                    ) {
                        launchSession.show(viewer)
                    } else {
                        launchSession.close()
                    }
                }
            }
        }
        return true
    }

    viewer
        .setNoneClickView()
        .setClickPosition(selectedIndex)
    launchSession.show(viewer)
    return true
}

private fun showWithBoundsFallback(
    viewer: OpenImage,
    selectedIndex: Int,
    launchSession: ImageViewerLaunchSession
) {
    viewer
        .setNoneClickView()
        .setClickPosition(selectedIndex)
    launchSession.show(viewer)
}

/** Prevents two rapid taps from starting competing viewer transitions for one Activity. */
internal class ImageViewerLaunchGate {
    private val activeTokens = WeakHashMap<Any, Any>()

    @Synchronized
    fun tryAcquire(owner: Any, token: Any): Boolean {
        if (activeTokens.containsKey(owner)) return false
        activeTokens[owner] = token
        return true
    }

    @Synchronized
    fun release(owner: Any, token: Any) {
        if (activeTokens[owner] === token) {
            activeTokens.remove(owner)
        }
    }
}

private class ImageViewerLaunchSession(
    private val activity: Activity
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val lifecycleOwner = activity as? LifecycleOwner
    private var transitionSource: ImageView? = null
    private var hasLeftActivity = false
    private val launchTimeout = Runnable(::close)
    private val returnCleanup = Runnable(::close)

    var isActive: Boolean = false
        private set

    fun start(): Boolean {
        if (!imageViewerLaunchGate.tryAcquire(activity, this)) return false

        isActive = true
        lifecycleOwner?.lifecycle?.addObserver(this)
        if (!isActive) return false
        handler.postDelayed(launchTimeout, VIEWER_LAUNCH_TIMEOUT_MS)
        return true
    }

    fun attachTransitionSource(source: ImageView): Boolean {
        if (!isActive) return false
        transitionSource = source
        return true
    }

    fun show(viewer: OpenImage) {
        if (!isActive) return
        try {
            viewer.show()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        hasLeftActivity = true
        handler.removeCallbacks(launchTimeout)
    }

    override fun onResume(owner: LifecycleOwner) {
        if (hasLeftActivity) {
            handler.removeCallbacks(returnCleanup)
            handler.postDelayed(returnCleanup, VIEWER_RETURN_CLEANUP_DELAY_MS)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    fun close() {
        if (!isActive) return
        isActive = false
        handler.removeCallbacks(launchTimeout)
        handler.removeCallbacks(returnCleanup)
        lifecycleOwner?.lifecycle?.removeObserver(this)
        transitionSource?.let(::removeTransitionSource)
        transitionSource = null
        imageViewerLaunchGate.release(activity, this)
    }
}

@Suppress("DEPRECATION")
private fun captureTransitionSource(
    activity: Activity,
    decorView: View,
    bounds: ImageViewerSourceBounds,
    onReady: (ImageView?) -> Unit
) {
    val container = decorView as? ViewGroup ?: run {
        onReady(null)
        return
    }
    val decorLocation = IntArray(2)
    decorView.getLocationInWindow(decorLocation)
    val captureBounds = Rect(
        maxOf(bounds.left, decorLocation[0]),
        maxOf(bounds.top, decorLocation[1]),
        minOf(bounds.left + bounds.width, decorLocation[0] + decorView.width),
        minOf(bounds.top + bounds.height, decorLocation[1] + decorView.height)
    )
    if (captureBounds.width() <= 0 || captureBounds.height() <= 0) {
        onReady(null)
        return
    }

    val bitmap = runCatching {
        Bitmap.createBitmap(
            captureBounds.width(),
            captureBounds.height(),
            Bitmap.Config.ARGB_8888
        )
    }.getOrNull() ?: run {
        onReady(null)
        return
    }
    val left = captureBounds.left - decorLocation[0]
    val top = captureBounds.top - decorLocation[1]

    fun attachSource(): ImageView = ImageView(activity).apply {
        setImageBitmap(bitmap)
        scaleType = sourceScaleType(bounds)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        container.addView(
            this,
            FrameLayout.LayoutParams(captureBounds.width(), captureBounds.height()).apply {
                leftMargin = left
                topMargin = top
            }
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PixelCopy.request(
            activity.window,
            captureBounds,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS && decorView.isAttachedToWindow) {
                    onReady(attachSource())
                } else {
                    Log.w(TRANSITION_TAG, "PixelCopy failed with result $result")
                    bitmap.recycle()
                    onReady(null)
                }
            },
            Handler(Looper.getMainLooper())
        )
        return
    }

    val captured = runCatching {
        Canvas(bitmap).apply {
            translate(-left.toFloat(), -top.toFloat())
            decorView.draw(this)
        }
    }.isSuccess
    if (captured) {
        onReady(attachSource())
    } else {
        bitmap.recycle()
        onReady(null)
    }
}

private fun sourceScaleType(bounds: ImageViewerSourceBounds): ImageView.ScaleType {
    return if (bounds.isCropped) {
        ImageView.ScaleType.CENTER_CROP
    } else {
        ImageView.ScaleType.FIT_CENTER
    }
}

private fun removeTransitionSource(source: ImageView) {
    (source.parent as? ViewGroup)?.removeView(source)
    source.setImageDrawable(null)
}

private fun viewerDownloadParams(context: Context): DownloadParams {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()

    val layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        marginEnd = dp(14)
        bottomMargin = dp(72)
    }
    return DownloadParams()
        .setDownloadSrc(R.drawable.ic_media_viewer_download)
        .setDownloadLayoutParams(layoutParams)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val WECHAT_TRANSITION_DURATION_MS = 250L
private const val VIEWER_RETURN_CLEANUP_DELAY_MS = WECHAT_TRANSITION_DURATION_MS + 150L
private const val VIEWER_LAUNCH_TIMEOUT_MS = 5_000L
private const val TRANSITION_TAG = "MurexideMediaTransition"

private val imageViewerLaunchGate = ImageViewerLaunchGate()
