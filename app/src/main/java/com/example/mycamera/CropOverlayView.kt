package com.example.mycamera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cropRect = RectF()
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    // 拖拽控制
    private var isDragging = false
    private var dragHandle = -1
    private var lastX = 0f
    private var lastY = 0f

    // 控制点常量
    private companion object {
        const val HANDLE_SIZE = 50f
        const val MIN_CROP_SIZE = 100f
        const val HANDLE_LEFT_TOP = 0
        const val HANDLE_RIGHT_TOP = 1
        const val HANDLE_LEFT_BOTTOM = 2
        const val HANDLE_RIGHT_BOTTOM = 3
        const val HANDLE_CENTER = 4
        const val NO_HANDLE = -1
    }

    init {
        paint.color = Color.argb(128, 0, 0, 0) // 半透明黑色
        paint.style = Paint.Style.FILL

        handlePaint.color = Color.WHITE
        handlePaint.style = Paint.Style.FILL
        handlePaint.strokeWidth = 3f

        textPaint.color = Color.WHITE
        textPaint.textSize = 40f
        textPaint.textAlign = Paint.Align.CENTER
    }

    fun setBitmapSize(width: Int, height: Int) {
        bitmapWidth = width
        bitmapHeight = height
    }

    fun resetCropRect() {
        val padding = 0.2f // 20% 边距
        val left = width * padding
        val top = height * padding
        val right = width * (1 - padding)
        val bottom = height * (1 - padding)
        cropRect.set(left, top, right, bottom)
        invalidate()
    }

    fun getCropRectInBitmap(): Rect {
        if (bitmapWidth == 0 || bitmapHeight == 0) return Rect()

        val scaleX = bitmapWidth.toFloat() / width
        val scaleY = bitmapHeight.toFloat() / height

        return Rect(
            (cropRect.left * scaleX).toInt(),
            (cropRect.top * scaleY).toInt(),
            (cropRect.right * scaleX).toInt(),
            (cropRect.bottom * scaleY).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制外部阴影
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, paint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, paint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, paint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), paint)

        // 绘制裁剪框边框
        handlePaint.style = Paint.Style.STROKE
        handlePaint.color = Color.WHITE
        canvas.drawRect(cropRect, handlePaint)

        // 绘制控制点
        handlePaint.style = Paint.Style.FILL
        handlePaint.color = Color.WHITE

        // 四个角控制点
        canvas.drawCircle(cropRect.left, cropRect.top, HANDLE_SIZE / 2, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, HANDLE_SIZE / 2, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, HANDLE_SIZE / 2, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, HANDLE_SIZE / 2, handlePaint)

        // 显示尺寸信息
        val cropWidth = cropRect.width().toInt()
        val cropHeight = cropRect.height().toInt()
        val text = "${cropWidth} × ${cropHeight}"
        canvas.drawText(text, cropRect.centerX(), cropRect.centerY(), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragHandle = getTouchedHandle(event.x, event.y)
                isDragging = dragHandle != NO_HANDLE
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY

                    when (dragHandle) {
                        HANDLE_LEFT_TOP -> {
                            cropRect.left = (cropRect.left + dx).coerceAtLeast(0f)
                            cropRect.top = (cropRect.top + dy).coerceAtLeast(0f)
                        }
                        HANDLE_RIGHT_TOP -> {
                            cropRect.right = (cropRect.right + dx).coerceAtMost(width.toFloat())
                            cropRect.top = (cropRect.top + dy).coerceAtLeast(0f)
                        }
                        HANDLE_LEFT_BOTTOM -> {
                            cropRect.left = (cropRect.left + dx).coerceAtLeast(0f)
                            cropRect.bottom = (cropRect.bottom + dy).coerceAtMost(height.toFloat())
                        }
                        HANDLE_RIGHT_BOTTOM -> {
                            cropRect.right = (cropRect.right + dx).coerceAtMost(width.toFloat())
                            cropRect.bottom = (cropRect.bottom + dy).coerceAtMost(height.toFloat())
                        }
                        HANDLE_CENTER -> {
                            val newLeft = cropRect.left + dx
                            val newTop = cropRect.top + dy
                            val newRight = cropRect.right + dx
                            val newBottom = cropRect.bottom + dy

                            if (newLeft >= 0 && newRight <= width && newTop >= 0 && newBottom <= height) {
                                cropRect.set(newLeft, newTop, newRight, newBottom)
                            }
                        }
                    }

                    // 确保最小尺寸
                    if (cropRect.width() < MIN_CROP_SIZE) {
                        when (dragHandle) {
                            HANDLE_LEFT_TOP, HANDLE_LEFT_BOTTOM -> cropRect.left = cropRect.right - MIN_CROP_SIZE
                            HANDLE_RIGHT_TOP, HANDLE_RIGHT_BOTTOM -> cropRect.right = cropRect.left + MIN_CROP_SIZE
                        }
                    }
                    if (cropRect.height() < MIN_CROP_SIZE) {
                        when (dragHandle) {
                            HANDLE_LEFT_TOP, HANDLE_RIGHT_TOP -> cropRect.top = cropRect.bottom - MIN_CROP_SIZE
                            HANDLE_LEFT_BOTTOM, HANDLE_RIGHT_BOTTOM -> cropRect.bottom = cropRect.top + MIN_CROP_SIZE
                        }
                    }

                    lastX = event.x
                    lastY = event.y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                isDragging = false
                dragHandle = NO_HANDLE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getTouchedHandle(x: Float, y: Float): Int {
        val handles = arrayOf(
            floatArrayOf(cropRect.left, cropRect.top),      // 左上
            floatArrayOf(cropRect.right, cropRect.top),     // 右上
            floatArrayOf(cropRect.left, cropRect.bottom),   // 左下
            floatArrayOf(cropRect.right, cropRect.bottom)   // 右下
        )

        // 检查四个角
        for (i in handles.indices) {
            if (isPointInHandle(x, y, handles[i][0], handles[i][1])) {
                return i
            }
        }

        // 检查中心区域（用于移动）
        if (x in cropRect.left..cropRect.right && y in cropRect.top..cropRect.bottom) {
            return HANDLE_CENTER
        }

        return NO_HANDLE
    }

    private fun isPointInHandle(x: Float, y: Float, handleX: Float, handleY: Float): Boolean {
        return Math.abs(x - handleX) <= HANDLE_SIZE && Math.abs(y - handleY) <= HANDLE_SIZE
    }
}