package com.juhao.murexide.ui.components.litehtml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Base64
import android.util.LruCache
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.Keep
import androidx.core.graphics.drawable.toBitmap
import androidx.core.text.HtmlCompat
import coil.Coil
import coil.request.Disposable
import coil.request.ImageRequest
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight static HTML view backed by litehtml.
 *
 * Parsing, layout and rasterization never run on the main thread. The native document is
 * rasterized into bounded 1024px tiles, so scrolling a message list only draws cached bitmaps.
 */
@Keep
class LiteHtmlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class FontData(
        val paint: Paint,
        val ascent: Float,
        val metrics: FloatArray
    )

    private data class ContentSpec(
        val html: String,
        val css: String
    )

    private val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
    private val nativeLock = Any()
    private val fontLock = Any()
    private val fonts = SparseArray<FontData>()
    private val nextFontId = AtomicInteger(1)
    private val imageRelayoutPosted = AtomicBoolean(false)
    private val imageBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val imageRequests = ConcurrentHashMap<String, Disposable>()
    private val requestedImages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val tiles = ConcurrentHashMap<Int, Bitmap>()
    private val tilesInFlight = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val drawClipBounds = Rect()
    private val fallbackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 14f * density
    }

    @Volatile
    private var nativeHandle = 0L
    @Volatile
    private var generation = 0
    @Volatile
    private var layoutGeneration = 0
    @Volatile
    private var contentSpec = ContentSpec("", "")
    @Volatile
    private var contentWidthPx = 0
    @Volatile
    private var documentHeightPx = 0
    @Volatile
    private var renderKey = ""
    @Volatile
    private var fallbackText = ""
    @Volatile
    private var plainContent = ""
    private var defaultFontSizeCssPx = 14
    private var onImageClick: ((String) -> Unit)? = null
    private var onLinkClick: ((String) -> Unit)? = null
    private var activeHit = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var released = false

    init {
        isFocusable = false
        isClickable = true
        setWillNotDraw(false)
    }

    fun updateContent(
        html: String,
        css: String,
        defaultFontSizeCssPx: Int,
        onImageClick: ((String) -> Unit)?,
        onLinkClick: ((String) -> Unit)?
    ) {
        this.onImageClick = onImageClick
        this.onLinkClick = onLinkClick
        this.defaultFontSizeCssPx = defaultFontSizeCssPx.coerceAtLeast(8)
        released = false

        val next = ContentSpec(html, css)
        if (next == contentSpec && nativeHandle != 0L) return

        generation += 1
        layoutGeneration += 1
        contentSpec = next
        fallbackText = ""
        plainContent = ""
        contentDescription = null
        imageRequests.values.forEach(Disposable::dispose)
        imageRequests.clear()
        requestedImages.clear()
        imageBitmaps.clear()
        imageRelayoutPosted.set(false)
        clearRenderedState()

        createDocument(generation, next)
    }

    fun updateCallbacks(
        onImageClick: ((String) -> Unit)?,
        onLinkClick: ((String) -> Unit)?
    ) {
        this.onImageClick = onImageClick
        this.onLinkClick = onLinkClick
    }

    fun release() {
        if (released) return
        released = true
        generation += 1
        layoutGeneration += 1
        clearRenderedState()
        imageRequests.values.forEach(Disposable::dispose)
        imageRequests.clear()
        requestedImages.clear()
        imageBitmaps.clear()
        synchronized(fontLock) {
            fonts.clear()
        }
    }

    private fun createDocument(expectedGeneration: Int, spec: ContentSpec) {
        RENDER_EXECUTOR.execute {
            val canRender = HtmlRenderPolicy.canRender(spec.html)
            val extractedText = if (canRender) plainText(spec.html) else spec.html.take(2_000)
            if (generation != expectedGeneration || released) return@execute
            plainContent = extractedText
            post {
                if (generation == expectedGeneration && !released) {
                    contentDescription = extractedText.take(MAX_ACCESSIBILITY_TEXT)
                }
            }
            if (!canRender) {
                showFallback(expectedGeneration, extractedText)
                return@execute
            }
            val handle = runCatching {
                nativeCreate(spec.html, spec.css, density, defaultFontSizeCssPx)
            }.getOrDefault(0L)

            if (handle == 0L) {
                showFallback(expectedGeneration, extractedText)
                return@execute
            }

            if (generation != expectedGeneration || released) {
                nativeDestroy(handle)
                return@execute
            }

            val oldHandle = synchronized(nativeLock) {
                if (generation != expectedGeneration || released) {
                    -1L
                } else {
                    val previous = nativeHandle
                    nativeHandle = handle
                    imageBitmaps.forEach { (url, bitmap) ->
                        nativeSetImageSize(handle, url, bitmap.width, bitmap.height)
                    }
                    previous
                }
            }
            if (oldHandle == -1L) {
                nativeDestroy(handle)
                return@execute
            }
            if (oldHandle != 0L) nativeDestroy(oldHandle)
            scheduleLayout(expectedGeneration)
        }
    }

    private fun scheduleLayout(expectedGeneration: Int = generation) {
        val widthPx = contentWidthPx
        if (widthPx <= 0 || released) return
        val expectedLayout = ++layoutGeneration
        RENDER_EXECUTOR.execute {
            if (generation != expectedGeneration || released) return@execute
            val heightCss = synchronized(nativeLock) {
                val handle = nativeHandle
                if (handle == 0L || generation != expectedGeneration) 0
                else runCatching {
                    nativeLayout(handle, max(1, (widthPx / density).toInt()))
                }.getOrDefault(0)
            }
            if (heightCss <= 0 || generation != expectedGeneration || layoutGeneration != expectedLayout) {
                if (heightCss <= 0 && generation == expectedGeneration && layoutGeneration == expectedLayout) {
                    showFallback(expectedGeneration, plainContent)
                }
                return@execute
            }
            val heightPx = max(1, ceil(heightCss * density).toInt())
            val key = digestKey(contentSpec, widthPx, density, expectedLayout)
            post {
                if (generation != expectedGeneration || layoutGeneration != expectedLayout || released) {
                    return@post
                }
                documentHeightPx = heightPx
                renderKey = key
                tiles.clear()
                tilesInFlight.clear()
                requestLayout()
                invalidate()
                // Render the first tile eagerly so a newly composed message settles quickly.
                ensureTile(0, expectedGeneration, expectedLayout)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        if (measuredWidth != contentWidthPx) {
            contentWidthPx = measuredWidth
            if (nativeHandle != 0L) scheduleLayout()
        }

        val desiredHeight = when {
            documentHeightPx > 0 -> documentHeightPx
            fallbackText.isNotBlank() -> fallbackLayout(measuredWidth).height + paddingTop + paddingBottom
            else -> (48f * density).toInt()
        }.coerceAtLeast(suggestedMinimumHeight)
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nativeHandle == 0L || documentHeightPx <= 0) {
            drawFallback(canvas)
            return
        }

        canvas.getClipBounds(drawClipBounds)
        val first = (drawClipBounds.top.coerceAtLeast(0) / TILE_HEIGHT_PX)
        val last = (drawClipBounds.bottom.coerceAtMost(documentHeightPx - 1).coerceAtLeast(0) / TILE_HEIGHT_PX)
        tiles.keys.forEach { index ->
            if (index < first - 1 || index > last + 1) tiles.remove(index)
        }
        for (index in first..last) {
            val bitmap = tiles[index] ?: TileCache.get(tileKey(index))
            if (bitmap != null && !bitmap.isRecycled) {
                tiles[index] = bitmap
                canvas.drawBitmap(bitmap, 0f, (index * TILE_HEIGHT_PX).toFloat(), TILE_PAINT)
            } else {
                ensureTile(index, generation, layoutGeneration)
            }
        }
    }

    private fun ensureTile(index: Int, expectedGeneration: Int, expectedLayout: Int) {
        if (index < 0 || index * TILE_HEIGHT_PX >= documentHeightPx || !tilesInFlight.add(index)) return
        val widthPx = contentWidthPx
        val topPx = index * TILE_HEIGHT_PX
        val heightPx = min(TILE_HEIGHT_PX, documentHeightPx - topPx)
        val cacheKey = tileKey(index)

        TileCache.get(cacheKey)?.let { cached ->
            tiles[index] = cached
            tilesInFlight.remove(index)
            postInvalidateOnAnimation(0, topPx, widthPx, topPx + heightPx)
            return
        }

        RENDER_EXECUTOR.execute {
            var bitmap: Bitmap? = null
            try {
                if (generation != expectedGeneration || layoutGeneration != expectedLayout || released) return@execute
                bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val tileCanvas = Canvas(bitmap)
                val topCss = topPx / density
                val heightCss = heightPx / density
                tileCanvas.scale(density, density)
                tileCanvas.translate(0f, -topCss)
                synchronized(nativeLock) {
                    val handle = nativeHandle
                    if (handle == 0L || generation != expectedGeneration || layoutGeneration != expectedLayout) {
                        bitmap.recycle()
                        bitmap = null
                        return@synchronized
                    }
                    nativeDraw(handle, tileCanvas, topCss, heightCss)
                }
            } catch (_: Throwable) {
                bitmap?.recycle()
                bitmap = null
            } finally {
                val completed = bitmap
                post {
                    tilesInFlight.remove(index)
                    if (completed == null) return@post
                    if (generation != expectedGeneration || layoutGeneration != expectedLayout || released) {
                        completed.recycle()
                        return@post
                    }
                    tiles[index] = completed
                    TileCache.put(cacheKey, completed)
                    postInvalidateOnAnimation(0, topPx, widthPx, topPx + heightPx)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / density
        val y = event.y / density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = synchronized(nativeLock) {
                    if (nativeHandle == 0L) 0 else nativeHitTest(nativeHandle, x, y)
                }
                if (hit == 0) return false
                activeHit = hit
                downX = event.x
                downY = event.y
                moved = false
                synchronized(nativeLock) {
                    if (nativeHandle != 0L) nativePointerDown(nativeHandle, x, y)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeHit == 0) return false
                if (!moved && (kotlin.math.abs(event.x - downX) > touchSlop ||
                            kotlin.math.abs(event.y - downY) > touchSlop)) {
                    moved = true
                    synchronized(nativeLock) {
                        if (nativeHandle != 0L) nativePointerCancel(nativeHandle)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeHit == 0) return false
                if (!moved) {
                    val needsRelayout = synchronized(nativeLock) {
                        nativeHandle != 0L && nativePointerUp(nativeHandle, x, y)
                    }
                    if (needsRelayout) scheduleLayout()
                    performClick()
                }
                activeHit = 0
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (activeHit != 0) {
                    synchronized(nativeLock) {
                        if (nativeHandle != 0L) nativePointerCancel(nativeHandle)
                    }
                }
                activeHit = 0
                return false
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun clearRenderedState() {
        documentHeightPx = 0
        renderKey = ""
        tiles.clear()
        tilesInFlight.clear()
        val oldHandle = synchronized(nativeLock) {
            val value = nativeHandle
            nativeHandle = 0L
            value
        }
        if (oldHandle != 0L) RENDER_EXECUTOR.execute { nativeDestroy(oldHandle) }
    }

    private fun showFallback(expectedGeneration: Int, text: String) {
        post {
            if (generation != expectedGeneration || released) return@post
            fallbackText = text.ifBlank { contentSpec.html.take(2_000) }
            requestLayout()
            invalidate()
        }
    }

    private fun drawFallback(canvas: Canvas) {
        if (fallbackText.isBlank() || contentWidthPx <= 0) return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        fallbackLayout(contentWidthPx).draw(canvas)
        canvas.restore()
    }

    private fun fallbackLayout(width: Int): StaticLayout {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        return StaticLayout.Builder.obtain(fallbackText, 0, fallbackText.length, fallbackPaint, available)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun tileKey(index: Int): String = "$renderKey:$index"

    private fun imageLoaded(url: String, bitmap: Bitmap) {
        if (released || bitmap.width <= 0 || bitmap.height <= 0) return
        imageBitmaps[url] = bitmap
        val expectedGeneration = generation
        RENDER_EXECUTOR.execute {
            synchronized(nativeLock) {
                val handle = nativeHandle
                if (handle != 0L && generation == expectedGeneration && !released) {
                    nativeSetImageSize(handle, url, bitmap.width, bitmap.height)
                }
            }
            if (generation == expectedGeneration && !released && imageRelayoutPosted.compareAndSet(false, true)) {
                postDelayed({
                    imageRelayoutPosted.set(false)
                    if (generation == expectedGeneration && !released) scheduleLayout(expectedGeneration)
                }, IMAGE_RELAYOUT_DEBOUNCE_MS)
            }
        }
    }

    // Methods below are invoked by AndroidDocumentContainer through JNI.

    @Keep
    private fun createFontFromNative(
        family: String,
        size: Float,
        weight: Int,
        italic: Boolean,
        decoration: Int
    ): Int {
        val style = when {
            weight >= 600 && italic -> Typeface.BOLD_ITALIC
            weight >= 600 -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val normalizedFamily = family.substringBefore(',').trim().trim('\'', '"').ifBlank { "sans-serif" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size.coerceAtLeast(1f)
            typeface = Typeface.create(normalizedFamily, style)
            isUnderlineText = decoration and 0x01 != 0
            isStrikeThruText = decoration and 0x04 != 0
        }
        val fm = paint.fontMetrics
        val bounds = Rect()
        paint.getTextBounds("x", 0, 1, bounds)
        val metrics = floatArrayOf(
            paint.textSize,
            (fm.descent - fm.ascent).coerceAtLeast(1f),
            (-fm.ascent).coerceAtLeast(1f),
            fm.descent.coerceAtLeast(0f),
            bounds.height().toFloat().coerceAtLeast(paint.textSize * 0.5f),
            paint.measureText("0").coerceAtLeast(1f),
            paint.textSize / 5f,
            paint.textSize / 3f
        )
        val id = nextFontId.getAndIncrement()
        synchronized(fontLock) {
            fonts.put(id, FontData(paint, -fm.ascent, metrics))
        }
        return id
    }

    @Keep
    private fun deleteFontFromNative(id: Int) {
        synchronized(fontLock) { fonts.remove(id) }
    }

    @Keep
    private fun fontMetricsFromNative(id: Int): FloatArray =
        synchronized(fontLock) { fonts.get(id)?.metrics?.copyOf() } ?: DEFAULT_FONT_METRICS.copyOf()

    @Keep
    private fun textWidthFromNative(id: Int, text: String): Float =
        synchronized(fontLock) { fonts.get(id)?.paint?.measureText(text) ?: 0f }

    @Keep
    private fun drawTextFromNative(
        canvas: Canvas,
        fontId: Int,
        text: String,
        color: Int,
        x: Float,
        y: Float,
        @Suppress("UNUSED_PARAMETER") width: Float
    ) {
        val data = synchronized(fontLock) { fonts.get(fontId) } ?: return
        data.paint.color = color
        canvas.drawText(text, x, y + data.ascent, data.paint)
    }

    @Keep
    private fun drawRectFromNative(
        canvas: Canvas,
        color: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float
    ) {
        if (Color.alpha(color) == 0 || right <= left || bottom <= top) return
        synchronized(SOLID_PAINT) {
            SOLID_PAINT.color = color
            SOLID_PAINT.shader = null
            if (radiusX > 0f || radiusY > 0f) {
                canvas.drawRoundRect(left, top, right, bottom, radiusX, radiusY, SOLID_PAINT)
            } else {
                canvas.drawRect(left, top, right, bottom, SOLID_PAINT)
            }
        }
    }

    @Keep
    private fun drawImageFromNative(
        canvas: Canvas,
        url: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val bitmap = imageBitmaps[url] ?: return
        if (bitmap.isRecycled || right <= left || bottom <= top) return
        canvas.drawBitmap(bitmap, null, RectF(left, top, right, bottom), IMAGE_PAINT)
    }

    @Keep
    private fun drawGradientFromNative(
        canvas: Canvas,
        type: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        p1: Float,
        p2: Float,
        p3: Float,
        p4: Float,
        colors: IntArray,
        offsets: FloatArray
    ) {
        if (colors.isEmpty() || right <= left || bottom <= top) return
        if (colors.size == 1) {
            drawRectFromNative(canvas, colors[0], left, top, right, bottom, 0f, 0f)
            return
        }
        val safeOffsets = offsets.takeIf { it.size == colors.size }
        val shader: Shader = when (type) {
            1 -> RadialGradient(p1, p2, p3.coerceAtLeast(1f), colors, safeOffsets, Shader.TileMode.CLAMP)
            2 -> SweepGradient(p1, p2, colors, safeOffsets).also {
                it.setLocalMatrix(Matrix().apply { setRotate(p3, p1, p2) })
            }
            else -> LinearGradient(p1, p2, p3, p4, colors, safeOffsets, Shader.TileMode.CLAMP)
        }
        synchronized(SOLID_PAINT) {
            SOLID_PAINT.shader = shader
            canvas.drawRect(left, top, right, bottom, SOLID_PAINT)
            SOLID_PAINT.shader = null
        }
    }

    @Keep
    private fun saveClipFromNative(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusX: Float,
        radiusY: Float
    ) {
        canvas.save()
        if (radiusX > 0f || radiusY > 0f) {
            val path = Path().apply {
                addRoundRect(RectF(left, top, right, bottom), radiusX, radiusY, Path.Direction.CW)
            }
            canvas.clipPath(path)
        } else {
            canvas.clipRect(left, top, right, bottom)
        }
    }

    @Keep
    private fun restoreClipFromNative(canvas: Canvas) {
        runCatching { canvas.restore() }
    }

    @Keep
    private fun requestImageFromNative(url: String) {
        if (!HtmlRenderPolicy.isAllowedImageUrl(url) || !requestedImages.add(url) || released) return
        if (url.startsWith("data:image/", ignoreCase = true)) {
            RENDER_EXECUTOR.execute {
                decodeDataImage(url)?.let { imageLoaded(url, it) }
            }
            return
        }
        post {
            if (released) return@post
            val targetWidth = contentWidthPx.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val request = ImageRequest.Builder(context.applicationContext)
                .data(url)
                .allowHardware(false)
                .size(targetWidth, MAX_IMAGE_EDGE)
                .target(
                    onSuccess = { drawable ->
                        imageRequests.remove(url)
                        RENDER_EXECUTOR.execute {
                            val bitmap = when (drawable) {
                                is BitmapDrawable -> drawable.bitmap
                                else -> runCatching {
                                    drawable.toBitmap(
                                        width = drawable.intrinsicWidth.coerceIn(1, MAX_IMAGE_EDGE),
                                        height = drawable.intrinsicHeight.coerceIn(1, MAX_IMAGE_EDGE),
                                        config = Bitmap.Config.ARGB_8888
                                    )
                                }.getOrNull()
                            }
                            if (bitmap != null && bitmap.width.toLong() * bitmap.height <= MAX_IMAGE_PIXELS) {
                                imageLoaded(url, bitmap)
                            }
                        }
                    },
                    onError = { imageRequests.remove(url) }
                )
                .build()
            imageRequests[url] = Coil.imageLoader(context).enqueue(request)
        }
    }

    @Keep
    private fun dispatchLinkFromNative(url: String) {
        post { if (!released) onLinkClick?.invoke(url) }
    }

    @Keep
    private fun dispatchImageFromNative(url: String) {
        post { if (!released) onImageClick?.invoke(url) }
    }

    private external fun nativeCreate(
        html: String,
        css: String,
        density: Float,
        defaultFontSize: Int
    ): Long

    private external fun nativeLayout(handle: Long, width: Int): Int
    private external fun nativeDraw(handle: Long, canvas: Canvas, tileTop: Float, tileHeight: Float)
    private external fun nativeSetImageSize(handle: Long, url: String, width: Int, height: Int)
    private external fun nativeHitTest(handle: Long, x: Float, y: Float): Int
    private external fun nativePointerDown(handle: Long, x: Float, y: Float)
    private external fun nativePointerUp(handle: Long, x: Float, y: Float): Boolean
    private external fun nativePointerCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TILE_HEIGHT_PX = 1024
        private const val MAX_ACCESSIBILITY_TEXT = 8_000
        private const val MAX_IMAGE_EDGE = 4096
        private const val MAX_IMAGE_PIXELS = 16L * 1024L * 1024L
        private const val IMAGE_RELAYOUT_DEBOUNCE_MS = 32L
        private val DEFAULT_FONT_METRICS = floatArrayOf(14f, 17f, 13f, 4f, 8f, 8f, 3f, 5f)
        private val RENDER_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "litehtml-render").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        private val TILE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val IMAGE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val SOLID_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        init {
            System.loadLibrary("murexide_litehtml")
        }

        private fun plainText(html: String): String = runCatching {
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        }.getOrDefault(html).ifBlank { html.take(2_000) }

        private fun digestKey(spec: ContentSpec, width: Int, density: Float, revision: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(spec.html.toByteArray(Charsets.UTF_8))
            digest.update(spec.css.toByteArray(Charsets.UTF_8))
            digest.update("$width:$density:$revision".toByteArray())
            return digest.digest().take(12).joinToString("") { "%02x".format(it) }
        }

        private fun decodeDataImage(url: String): Bitmap? {
            val comma = url.indexOf(',')
            if (comma <= 0 || !url.substring(0, comma).contains(";base64", true)) return null
            val encoded = url.substring(comma + 1)
            if (encoded.length > 24 * 1024 * 1024) return null
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > MAX_IMAGE_EDGE ||
                bounds.outHeight / sample > MAX_IMAGE_EDGE ||
                (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_IMAGE_PIXELS
            ) {
                sample *= 2
            }
            return BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }
    }
}

private object TileCache {
    private val maxBytes = min(
        32L * 1024L * 1024L,
        Runtime.getRuntime().maxMemory() / 16L
    ).coerceAtLeast(8L * 1024L * 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            runCatching { value.allocationByteCount }.getOrDefault(value.byteCount)
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)?.takeUnless(Bitmap::isRecycled)

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled && bitmap.allocationByteCount <= maxBytes) cache.put(key, bitmap)
    }
}
