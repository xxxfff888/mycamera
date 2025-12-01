package com.example.mycamera

import android.app.AlertDialog
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 负责贴纸：
 * - 单指拖动
 * - 双指缩放 & 旋转
 * - 长按删除
 */
class DraggableStickerView(context: Context) : AppCompatImageView(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var pointerId1 = -1
    private var pointerId2 = -1
    private var initialDist = 0f
    private var initialScale = 1f
    private var initialAngle = 0f
    private var initialRotationView = 0f

    init {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        isClickable = true
        isFocusable = true

        setOnLongClickListener {
            AlertDialog.Builder(context)
                .setMessage("删除这个贴纸？")
                .setPositiveButton("删除") { _, _ ->
                    (parent as? ViewGroup)?.removeView(this)
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bringToFront()
                pointerId1 = event.getPointerId(event.actionIndex)
                lastX = event.rawX
                lastY = event.rawY
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId2 == -1) {
                    pointerId2 = event.getPointerId(event.actionIndex)
                    val i1 = event.findPointerIndex(pointerId1)
                    val i2 = event.findPointerIndex(pointerId2)
                    if (i1 >= 0 && i2 >= 0) {
                        initialDist = distance(
                            event.getX(i1), event.getY(i1),
                            event.getX(i2), event.getY(i2)
                        )
                        initialScale = scaleX
                        initialRotationView = rotation
                        initialAngle = angle(
                            event.getX(i1), event.getY(i1),
                            event.getX(i2), event.getY(i2)
                        )
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerId1 != -1 && pointerId2 != -1 && event.pointerCount >= 2) {
                    val i1 = event.findPointerIndex(pointerId1)
                    val i2 = event.findPointerIndex(pointerId2)
                    if (i1 >= 0 && i2 >= 0) {
                        val newDist = distance(
                            event.getX(i1), event.getY(i1),
                            event.getX(i2), event.getY(i2)
                        )
                        if (initialDist > 0f) {
                            val scale = (newDist / initialDist)
                            scaleX = initialScale * scale
                            scaleY = initialScale * scale
                        }

                        val newAngle = angle(
                            event.getX(i1), event.getY(i1),
                            event.getX(i2), event.getY(i2)
                        )
                        rotation = initialRotationView + (newAngle - initialAngle)
                    }
                } else {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    x += dx
                    y += dy
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == pointerId2) {
                    pointerId2 = -1
                } else if (pointerId == pointerId1) {
                    pointerId1 = pointerId2
                    pointerId2 = -1
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                pointerId1 = -1
                pointerId2 = -1
            }
        }
        return true
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot((x2 - x1), (y2 - y1))

    private fun angle(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
}
