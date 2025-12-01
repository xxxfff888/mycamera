package com.example.mycamera

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.children

/**
 * 负责：
 * - 添加 / 编辑文字（对话框、字体、颜色）
 * - 添加贴纸（网格选择）
 * - Overlay 的保存 / 恢复（按相对坐标）
 * - 在裁剪 / 旋转 / 翻转之后调整 overlay 位置和缩放
 */
class OverlaysManager(
    private val activity: Activity,
    private val container: FrameLayout
) {

    // 贴纸资源列表（和原 MainActivity 中一样）
    private data class StickerSpec(val resId: Int)

    private val stickerList = listOf(
        StickerSpec(android.R.drawable.ic_menu_camera),
        StickerSpec(android.R.drawable.ic_menu_compass),
        StickerSpec(android.R.drawable.ic_menu_call),
        StickerSpec(android.R.drawable.ic_menu_gallery),
        StickerSpec(android.R.drawable.ic_menu_help),
        StickerSpec(android.R.drawable.ic_menu_manage),
        StickerSpec(android.R.drawable.ic_menu_myplaces),
        StickerSpec(android.R.drawable.ic_menu_week),
        StickerSpec(android.R.drawable.ic_dialog_email),
        StickerSpec(android.R.drawable.ic_delete)
    )

    // ---------- 对外 API ----------

    fun clearOverlays() {
        container.removeAllViews()
    }

    fun saveToBundle(): ArrayList<Bundle> {
        val list = ArrayList<Bundle>()
        val cw = if (container.width > 0) container.width.toFloat() else 1f
        val ch = if (container.height > 0) container.height.toFloat() else 1f

        for (child in container.children) {
            val b = Bundle()
            when (child) {
                is DraggableTextView -> {
                    b.putString("type", "text")
                    b.putString("text", child.text.toString())

                    val xNorm = child.x / cw
                    val yNorm = child.y / ch
                    b.putFloat("xNorm", xNorm)
                    b.putFloat("yNorm", yNorm)

                    val textSizePx = child.textSize
                    val textSizeRatio = textSizePx / cw
                    b.putFloat("textSizeRatio", textSizeRatio)

                    b.putInt("color", child.currentTextColor)
                    b.putFloat("alpha", child.alpha)
                    b.putFloat("rotation", child.rotation)
                }

                is DraggableStickerView -> {
                    b.putString("type", "sticker")
                    val resId = (child.tag as? Int) ?: -1
                    b.putInt("resId", resId)

                    val xNorm = child.x / cw
                    val yNorm = child.y / ch
                    b.putFloat("xNorm", xNorm)
                    b.putFloat("yNorm", yNorm)

                    b.putFloat("scale", child.scaleX)
                    b.putFloat("rotation", child.rotation)
                    b.putFloat("alpha", child.alpha)
                }

                else -> continue
            }
            list.add(b)
        }
        return list
    }

    fun restoreFromBundle(list: ArrayList<Bundle>) {
        container.removeAllViews()
        val cw = if (container.width > 0) container.width.toFloat() else 1f
        val ch = if (container.height > 0) container.height.toFloat() else 1f

        for (b in list) {
            when (b.getString("type")) {
                "text" -> {
                    val tv = DraggableTextView(activity)
                    tv.text = b.getString("text") ?: ""

                    val xNorm = b.getFloat("xNorm", 0.5f)
                    val yNorm = b.getFloat("yNorm", 0.5f)
                    tv.x = xNorm * cw
                    tv.y = yNorm * ch

                    val textSizeRatio = b.getFloat("textSizeRatio", 0.02f)
                    val textSizePx = textSizeRatio * cw
                    tv.textSize = (textSizePx / activity.resources.displayMetrics.scaledDensity)

                    tv.setTextColor(b.getInt("color", Color.WHITE))
                    tv.alpha = b.getFloat("alpha", 1f)
                    tv.rotation = b.getFloat("rotation", 0f)

                    // 长按再次编辑
                    tv.onEditRequest = { showAddTextDialog(it) }

                    container.addView(tv)
                }

                "sticker" -> {
                    val resId = b.getInt("resId", -1)
                    val sticker = DraggableStickerView(activity)
                    if (resId != -1) {
                        sticker.setImageResource(resId)
                        sticker.tag = resId
                    }

                    val xNorm = b.getFloat("xNorm", 0.5f)
                    val yNorm = b.getFloat("yNorm", 0.5f)
                    sticker.x = xNorm * cw
                    sticker.y = yNorm * ch

                    val scale = b.getFloat("scale", 1f)
                    sticker.scaleX = scale
                    sticker.scaleY = scale
                    sticker.rotation = b.getFloat("rotation", 0f)
                    sticker.alpha = b.getFloat("alpha", 1f)

                    container.addView(sticker)
                }
            }
        }
    }

    fun adjustAfterCrop(cropRect: Rect, originalWidth: Int, originalHeight: Int) {
        val newWidth = cropRect.width()
        val newHeight = cropRect.height()

        for (child in container.children) {
            val newX = (child.x - cropRect.left) * newWidth / originalWidth
            val newY = (child.y - cropRect.top) * newHeight / originalHeight
            child.x = newX
            child.y = newY

            val scaleX = newWidth.toFloat() / originalWidth
            val scaleY = newHeight.toFloat() / originalHeight
            val scale = kotlin.math.min(scaleX, scaleY)
            if (child is DraggableStickerView) {
                child.scaleX *= scale
                child.scaleY *= scale
            }
        }
    }

    fun adjustAfterRotation(degree: Float, originalWidth: Int, originalHeight: Int) {
        val centerX = originalWidth / 2f
        val centerY = originalHeight / 2f
        val matrix = android.graphics.Matrix().apply { postRotate(degree) }

        for (child in container.children) {
            val dx = child.x - centerX
            val dy = child.y - centerY
            val pts = floatArrayOf(dx, dy)
            matrix.mapPoints(pts)
            child.x = centerX + pts[0]
            child.y = centerY + pts[1]
            if (child is DraggableStickerView) {
                child.rotation += degree
            }
        }
    }

    fun adjustAfterFlip(horizontal: Boolean, originalWidth: Int, originalHeight: Int) {
        for (child in container.children) {
            if (horizontal) {
                child.x = originalWidth - child.x - child.width
            } else {
                child.y = originalHeight - child.y - child.height
            }
            if (child is DraggableStickerView) {
                if (horizontal) child.scaleX *= -1f else child.scaleY *= -1f
            }
        }
    }

    /**
     * 截取 overlay 容器的位图（供保存时使用，可选）
     */
    fun captureOverlayBitmap(): Bitmap? {
        if (container.width <= 0 || container.height <= 0) return null
        return try {
            Bitmap.createBitmap(
                container.width,
                container.height,
                Bitmap.Config.ARGB_8888
            ).apply {
                val c = android.graphics.Canvas(this)
                container.draw(c)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 添加 / 编辑文字 ----------

    fun showAddTextDialog(existing: DraggableTextView? = null) {
        if ((activity as? MainActivity)?.isFinishing == true) return

        val dialogView =
            LayoutInflater.from(activity).inflate(R.layout.dialog_edit_text, null)
        val etContent = dialogView.findViewById<EditText>(R.id.etContent)
        val seekSize = dialogView.findViewById<SeekBar>(R.id.seekSize)
        val tvSizeValue = dialogView.findViewById<TextView>(R.id.tvSizeValue)
        val seekAlpha = dialogView.findViewById<SeekBar>(R.id.seekAlpha)
        val tvAlphaValue = dialogView.findViewById<TextView>(R.id.tvAlphaValue)
        val cbBold = dialogView.findViewById<CheckBox>(R.id.cbBold)
        val cbItalic = dialogView.findViewById<CheckBox>(R.id.cbItalic)
        val spFontFamily = dialogView.findViewById<Spinner>(R.id.spinnerFont)
        val colorPreview = dialogView.findViewById<View>(R.id.colorPreview)
        val btnCustomColor = dialogView.findViewById<Button>(R.id.btnCustomColor)
        val colorPresetLayout = dialogView.findViewById<LinearLayout>(R.id.colorPresetLayout)
        val btnOk = dialogView.findViewById<Button>(R.id.btnOk)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        var selectedColor = existing?.currentTextColor ?: Color.WHITE
        colorPreview.setBackgroundColor(selectedColor)

        val fontFamilies = arrayOf("默认", "衬线体", "无衬线体", "等宽字体")
        val fontFamilyAdapter =
            ArrayAdapter(activity, android.R.layout.simple_spinner_item, fontFamilies)
        fontFamilyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFontFamily.adapter = fontFamilyAdapter

        existing?.let { tv ->
            val typeface = tv.typeface
            when {
                typeface == Typeface.SERIF -> spFontFamily.setSelection(1)
                typeface == Typeface.SANS_SERIF -> spFontFamily.setSelection(2)
                typeface == Typeface.MONOSPACE -> spFontFamily.setSelection(3)
                else -> spFontFamily.setSelection(0)
            }
        }

        seekSize.max = 24
        seekSize.progress = existing?.let {
            val sp = (it.textSize / activity.resources.displayMetrics.scaledDensity).toInt()
            (sp - 12).coerceIn(0, 24)
        } ?: 6
        tvSizeValue.text = "${12 + seekSize.progress}"

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                tvSizeValue.text = "${12 + progress}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekAlpha.max = 50
        val initAlpha = existing?.alpha ?: 1f
        seekAlpha.progress = ((initAlpha - 0.5f) * 100).toInt().coerceIn(0, 50)
        tvAlphaValue.text = "${(initAlpha * 100).toInt()}%"

        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                val alpha = 0.5f + progress / 100f
                tvAlphaValue.text = "${(alpha * 100).toInt()}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val presetColors = arrayOf(
            Color.WHITE,
            Color.BLACK,
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.CYAN,
            Color.MAGENTA,
            Color.GRAY,
            Color.parseColor("#FFA500")
        )
        val size = 24.dpToPx(activity)
        val margin = 4.dpToPx(activity)
        presetColors.forEach { c ->
            val v = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = margin }
                setBackgroundColor(c)
                setOnClickListener {
                    selectedColor = c
                    colorPreview.setBackgroundColor(c)
                }
            }
            colorPresetLayout.addView(v)
        }

        btnCustomColor.setOnClickListener {
            showColorPickerDialog(selectedColor) { c ->
                selectedColor = c
                colorPreview.setBackgroundColor(c)
            }
        }

        existing?.let { tv ->
            etContent.setText(tv.text)
            cbBold.isChecked = tv.typeface?.isBold ?: false
            cbItalic.isChecked = tv.typeface?.isItalic ?: false
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnOk.setOnClickListener {
            val text = etContent.text.toString()
            if (text.isBlank()) {
                Toast.makeText(activity, "请输入文字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sp = 12 + seekSize.progress
            val alpha = 0.5f + seekAlpha.progress / 100f
            val selectedFontFamily = spFontFamily.selectedItemPosition

            if (existing == null) {
                val tv = DraggableTextView(activity)
                tv.text = text
                applyTextStyle(tv, sp, alpha, cbBold.isChecked, cbItalic.isChecked, selectedColor, selectedFontFamily)
                tv.onEditRequest = { showAddTextDialog(it) }
                container.addView(tv)
            } else {
                existing.text = text
                applyTextStyle(
                    existing,
                    sp,
                    alpha,
                    cbBold.isChecked,
                    cbItalic.isChecked,
                    selectedColor,
                    selectedFontFamily
                )
            }
            dialog.dismiss()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun applyTextStyle(
        tv: DraggableTextView,
        sizeSp: Int,
        alpha: Float,
        bold: Boolean,
        italic: Boolean,
        color: Int,
        fontFamily: Int
    ) {
        tv.textSize = sizeSp.toFloat()
        tv.alpha = alpha
        tv.setTextColor(color)

        val baseTypeface = when (fontFamily) {
            1 -> Typeface.SERIF
            2 -> Typeface.SANS_SERIF
            3 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }

        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        tv.setTypeface(Typeface.create(baseTypeface, style), style)
    }

    private fun showColorPickerDialog(initialColor: Int, onColorSelected: (Int) -> Unit) {
        val dialogView =
            LayoutInflater.from(activity).inflate(R.layout.dialog_color_picker, null)
        val seekRed = dialogView.findViewById<SeekBar>(R.id.seekRed)
        val seekGreen = dialogView.findViewById<SeekBar>(R.id.seekGreen)
        val seekBlue = dialogView.findViewById<SeekBar>(R.id.seekBlue)
        val tvRed = dialogView.findViewById<TextView>(R.id.tvRed)
        val tvGreen = dialogView.findViewById<TextView>(R.id.tvGreen)
        val tvBlue = dialogView.findViewById<TextView>(R.id.tvBlue)
        val colorResult = dialogView.findViewById<View>(R.id.colorResult)
        val btnOk = dialogView.findViewById<Button>(R.id.btnOk)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val r0 = Color.red(initialColor)
        val g0 = Color.green(initialColor)
        val b0 = Color.blue(initialColor)
        seekRed.progress = r0
        seekGreen.progress = g0
        seekBlue.progress = b0
        updateColorValues(r0, g0, b0, tvRed, tvGreen, tvBlue, colorResult)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                val r = seekRed.progress
                val g = seekGreen.progress
                val b = seekBlue.progress
                updateColorValues(r, g, b, tvRed, tvGreen, tvBlue, colorResult)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        seekRed.setOnSeekBarChangeListener(listener)
        seekGreen.setOnSeekBarChangeListener(listener)
        seekBlue.setOnSeekBarChangeListener(listener)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("自定义颜色")
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnOk.setOnClickListener {
            val color = Color.rgb(seekRed.progress, seekGreen.progress, seekBlue.progress)
            onColorSelected(color)
            dialog.dismiss()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun updateColorValues(
        red: Int,
        green: Int,
        blue: Int,
        tvRed: TextView,
        tvGreen: TextView,
        tvBlue: TextView,
        colorResult: View
    ) {
        tvRed.text = "R: $red"
        tvGreen.text = "G: $green"
        tvBlue.text = "B: $blue"
        colorResult.setBackgroundColor(Color.rgb(red, green, blue))
    }

    private fun Int.dpToPx(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()

    // ---------- 贴纸 ----------
    fun showStickerDialog() {
        if ((activity as? MainActivity)?.isFinishing == true) return

        val gridView = GridView(activity).apply {
            numColumns = 4
            verticalSpacing = 8.dpToPx(context)
            horizontalSpacing = 8.dpToPx(context)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = stickerList.size
                override fun getItem(position: Int): Any = stickerList[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup?
                ): View {
                    val size = 56.dpToPx(parent!!.context)
                    val iv = (convertView as? ImageView) ?: ImageView(parent.context).apply {
                        layoutParams = AbsListView.LayoutParams(size, size)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(
                            8.dpToPx(context),
                            8.dpToPx(context),
                            8.dpToPx(context),
                            8.dpToPx(context)
                        )
                    }
                    iv.setImageResource(stickerList[position].resId)
                    return iv
                }
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择贴纸")
            .setView(gridView)
            .create()

        gridView.setOnItemClickListener { _, _, position, _ ->
            val spec = stickerList[position]
            val sticker = DraggableStickerView(activity)
            sticker.setImageResource(spec.resId)
            sticker.tag = spec.resId
            container.addView(sticker)
            dialog.dismiss()
        }

        dialog.show()
    }
}
