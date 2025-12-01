package com.example.mycamera

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView

/**
 * 只负责：
 * - 文本显示
 * - 拖动
 * - 长按回调交给外面（OverlaysManager）处理
 */
class DraggableTextView(context: Context) : AppCompatTextView(context) {

    var onEditRequest: ((DraggableTextView) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f

    init {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setTextColor(Color.WHITE)
        textSize = 18f

        setOnLongClickListener {
            onEditRequest?.invoke(this)
            true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bringToFront()
                lastX = event.rawX
                lastY = event.rawY
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                x += dx
                y += dy
                lastX = event.rawX
                lastY = event.rawY
            }
        }
        return true
    }
}
