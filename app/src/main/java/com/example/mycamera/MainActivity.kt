package com.example.mycamera

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val STATE_IMAGE = "state_image"
        private const val STATE_BRIGHTNESS = "state_brightness"
        private const val STATE_CONTRAST = "state_contrast"
        private const val STATE_FILTER = "state_filter"
        private const val STATE_IS_NIGHT_MODE = "state_is_night_mode"
        private const val STATE_OVERLAYS = "state_overlays"

        // 预览 / 大图双轨最大边
        private const val PREVIEW_MAX_SIZE = 1024   // 交互用
        private const val FULL_MAX_SIZE = 2048      // 保存用
    }

    // 顶部 + 主视图
    private lateinit var toolbar: MaterialToolbar
    private lateinit var photoView: PhotoView
    private lateinit var textOverlayContainer: FrameLayout
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var btnOpenAlbum: Button
    private lateinit var btnOpenCamera: Button
    private lateinit var btnSaveImage: Button
    private lateinit var cbNightMode: CheckBox

    // 裁剪相关
    private lateinit var cropOverlayView: CropOverlayView
    private lateinit var cropControlLayout: LinearLayout
    private lateinit var btnCropConfirm: Button
    private lateinit var btnCropCancel: Button
    private var isCropMode = false

    // 相机
    private var cameraImageUri: Uri? = null

    /**
     * 预览基准图（仅做几何变换，不带滤镜/亮度）
     * 所有 applyAllEffects() 都基于它生成 currentBitmap
     */
    private var baseBitmap: Bitmap? = null

    /**
     * 当前展示图（预览），每次 applyAllEffects() 生成
     */
    private var currentBitmap: Bitmap? = null

    /**
     * 大图（仅做几何变化，不做滤镜/亮度），用于最终保存
     */
    private var fullBitmap: Bitmap? = null

    // 亮度 / 对比度
    private var currentBrightness = 0f
    private var currentContrast = 1f

    // 协程
    private val uiScope = MainScope()
    private var applyJob: Job? = null
    private var loadJob: Job? = null

    // 滤镜
    private enum class FilterType { ORIGINAL, BW, RETRO, FRESH, WARM, COOL }
    private var currentFilter: FilterType = FilterType.ORIGINAL

    // [Fix 1] 核心修复：把大小计算放在这里，只计算一次，永久固定。
    // 即使 Bitmap 后来被 recycle 了，这个 sizeKb 值也不会变，保证 LruCache 计数一致。
    private data class CachedImage(val preview: Bitmap, val full: Bitmap?) {
        val sizeKb: Int = run {
            val p = if (!preview.isRecycled) preview.byteCount / 1024 else 0
            val f = if (full != null && !full.isRecycled) full.byteCount / 1024 else 0
            max(1, p + f) // 确保至少为 1，避免除零或负数风险
        }
    }

    // 内存管理：LruCache，按位图字节数控制
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = maxMemoryKb / 8

    // [Fix 2] 核心修复：sizeOf 只读 CachedImage 里的固定字段
    private val bitmapCache = object : LruCache<String, CachedImage>(cacheSizeKb) {
        override fun sizeOf(key: String, value: CachedImage): Int {
            // 不再访问 value.preview.byteCount，直接读取计算好的 sizeKb
            return value.sizeKb
        }

        // 可选：当图片被移除出缓存时的回调（这里暂不做处理，交由 GC 或外部逻辑回收）
        // override fun entryRemoved(evicted: Boolean, key: String, oldValue: CachedImage, newValue: CachedImage?) {}
    }

    private var currentImageKey: String? = null

    // overlay 管理器（文字 + 贴纸 + 保存 / 恢复 / 调整）
    private lateinit var overlaysManager: OverlaysManager

    // ActivityResult
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>

    // 防止夜间模式切换时重复操作
    private var isSwitchingNightMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复夜间模式设置
        val isNightMode = savedInstanceState?.getBoolean(STATE_IS_NIGHT_MODE)
            ?: ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES)

        if (isNightMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        setContentView(R.layout.activity_main)

        // 绑定控件
        toolbar = findViewById(R.id.topAppBar)
        photoView = findViewById(R.id.photoView)
        textOverlayContainer = findViewById(R.id.textOverlayContainer)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekContrast = findViewById(R.id.seekContrast)
        btnOpenAlbum = findViewById(R.id.btnOpenAlbum)
        btnOpenCamera = findViewById(R.id.btnOpenCamera)
        btnSaveImage = findViewById(R.id.btnSaveImage)
        cbNightMode = findViewById(R.id.cbNightMode)

        cropOverlayView = findViewById(R.id.cropOverlayView)
        cropControlLayout = findViewById(R.id.cropControlLayout)
        btnCropConfirm = findViewById(R.id.btnCropConfirm)
        btnCropCancel = findViewById(R.id.btnCropCancel)

        // 初始化 overlay 管理器
        overlaysManager = OverlaysManager(this, textOverlayContainer)

        initActivityResults()
        initTopButtons()
        initSeekBars()
        initEditButtons()
        initCropControls()
        initNightModeToggle(isNightMode)

        // 恢复状态（图片 + overlay）
        restoreFromSavedInstance(savedInstanceState)

        isSwitchingNightMode = false
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        applyJob?.cancel()
        loadJob?.cancel()
        bitmapCache.evictAll()
        if (isFinishing) {
            baseBitmap?.recycle()
            currentBitmap?.recycle()
            fullBitmap?.recycle()
            baseBitmap = null
            currentBitmap = null
            fullBitmap = null
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        bitmapCache.evictAll()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_MODERATE,
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL -> bitmapCache.evictAll()
        }
    }

    // ---------- 状态恢复 / 保存 ----------

    private fun restoreFromSavedInstance(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        // 夜间模式 checkbox
        val isNight = savedInstanceState.getBoolean(STATE_IS_NIGHT_MODE, false)
        cbNightMode.isChecked = isNight
        updateNightModeTextColor(isNight)

        val bytes = savedInstanceState.getByteArray(STATE_IMAGE)
        if (bytes != null) {
            uiScope.launch(Dispatchers.Default) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    withContext(Dispatchers.Main) {
                        baseBitmap = bmp
                        currentBrightness = savedInstanceState.getFloat(STATE_BRIGHTNESS, 0f)
                        currentContrast = savedInstanceState.getFloat(STATE_CONTRAST, 1f)
                        val filterName =
                            savedInstanceState.getString(STATE_FILTER, FilterType.ORIGINAL.name)
                        currentFilter =
                            FilterType.valueOf(filterName ?: FilterType.ORIGINAL.name)

                        seekBrightness.progress = (currentBrightness + 100).toInt()
                        seekContrast.progress = ((currentContrast - 0.5f) * 100).toInt()
                        applyAllEffects()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "恢复图片失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 恢复 overlays（按比例）
        val overlays = savedInstanceState.getParcelableArrayList<Bundle>(STATE_OVERLAYS)
        if (overlays != null && overlays.isNotEmpty()) {
            uiScope.launch(Dispatchers.Main) {
                textOverlayContainer.post {
                    overlaysManager.restoreFromBundle(overlays)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(STATE_IS_NIGHT_MODE, cbNightMode.isChecked)

        baseBitmap?.let { bmp ->
            try {
                if (!bmp.isRecycled) {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    outState.putByteArray(STATE_IMAGE, baos.toByteArray())
                }
            } catch (_: Exception) {
            }
        }
        outState.putFloat(STATE_BRIGHTNESS, currentBrightness)
        outState.putFloat(STATE_CONTRAST, currentContrast)
        outState.putString(STATE_FILTER, currentFilter.name)

        // overlay 通过 OverlaysManager 保存
        val overlays = overlaysManager.saveToBundle()
        if (overlays.size > 0) {
            outState.putParcelableArrayList(STATE_OVERLAYS, overlays)
        }
    }

    // ---------- 夜间模式 ----------

    private fun initNightModeToggle(initialNightMode: Boolean) {
        cbNightMode.text = "夜间模式"
        cbNightMode.isChecked = initialNightMode
        updateNightModeTextColor(initialNightMode)

        cbNightMode.setOnCheckedChangeListener { _, checked ->
            if (isSwitchingNightMode) return@setOnCheckedChangeListener
            isSwitchingNightMode = true
            updateNightModeTextColor(checked)
            applySmoothNightModeTransition(checked)
        }
    }

    private fun applySmoothNightModeTransition(isNightMode: Boolean) {
        val fadeOut = android.view.animation.AlphaAnimation(1f, 0.3f).apply { duration = 150 }
        val fadeIn = android.view.animation.AlphaAnimation(0.3f, 1f).apply { duration = 150 }

        fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                val mode =
                    if (isNightMode) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                AppCompatDelegate.setDefaultNightMode(mode)
                uiScope.launch {
                    delay(50)
                    withContext(Dispatchers.Main) {
                        photoView.startAnimation(fadeIn)
                        isSwitchingNightMode = false
                    }
                }
            }

            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
        })

        photoView.startAnimation(fadeOut)
    }

    private fun updateNightModeTextColor(isNightMode: Boolean) {
        val textColor = if (isNightMode) Color.RED else Color.BLACK
        cbNightMode.setTextColor(textColor)
    }

    // ---------- 顶部按钮：相册 / 相机 / 保存 ----------

    private fun initTopButtons() {
        btnOpenAlbum.setOnClickListener {
            if (!isCropMode) pickImageLauncher.launch("image/*")
        }
        btnOpenCamera.setOnClickListener {
            if (!isCropMode) openCameraWithPermission()
        }
        btnSaveImage.setOnClickListener {
            if (!isCropMode) saveCurrentImage()
        }
    }

    private fun initActivityResults() {
        pickImageLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let { loadBitmapFromUri(it) }
            }

        takePictureLauncher =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (success && cameraImageUri != null) {
                    loadBitmapFromUri(cameraImageUri!!)
                }
            }

        cameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) openCamera() else
                    Toast.makeText(this, "相机权限被拒绝", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openCameraWithPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val imageFile =
            File(getExternalFilesDir("Pictures"), "camera_${System.currentTimeMillis()}.jpg")
        cameraImageUri =
            FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
        takePictureLauncher.launch(cameraImageUri)
    }

    // ---------- 裁剪控件 ----------

    private fun initCropControls() {
        cropOverlayView.visibility = View.GONE
        cropControlLayout.visibility = View.GONE
        btnCropConfirm.setOnClickListener { applyCrop() }
        btnCropCancel.setOnClickListener { exitCropMode() }
    }

    private fun startCropMode() {
        if (baseBitmap == null && currentBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        isCropMode = true
        cropOverlayView.visibility = View.VISIBLE
        cropControlLayout.visibility = View.VISIBLE
        val bmp = currentBitmap ?: baseBitmap!!
        cropOverlayView.setBitmapSize(bmp.width, bmp.height)
        cropOverlayView.resetCropRect()
        setEditButtonsEnabled(false)
    }

    private fun applyCrop() {
        val previewSrc = baseBitmap ?: currentBitmap ?: return
        val cropRectPreview = cropOverlayView.getCropRectInBitmap()
        if (cropRectPreview.width() <= 0 || cropRectPreview.height() <= 0) {
            Toast.makeText(this, "裁剪区域无效", Toast.LENGTH_SHORT).show()
            return
        }

        val hasOverlays = textOverlayContainer.childCount > 0
        val wPreview = previewSrc.width.toFloat()
        val hPreview = previewSrc.height.toFloat()

        val leftNorm = cropRectPreview.left / wPreview
        val topNorm = cropRectPreview.top / hPreview
        val rightNorm = cropRectPreview.right / wPreview
        val bottomNorm = cropRectPreview.bottom / hPreview

        uiScope.launch(Dispatchers.Default) {
            try {
                val croppedPreview = Bitmap.createBitmap(
                    previewSrc,
                    cropRectPreview.left,
                    cropRectPreview.top,
                    cropRectPreview.width(),
                    cropRectPreview.height()
                )

                val oldFull = fullBitmap
                val croppedFull = oldFull?.let { full ->
                    val fw = full.width.toFloat()
                    val fh = full.height.toFloat()
                    val fullRect = Rect(
                        (leftNorm * fw).toInt().coerceIn(0, full.width - 1),
                        (topNorm * fh).toInt().coerceIn(0, full.height - 1),
                        (rightNorm * fw).toInt().coerceIn(1, full.width),
                        (bottomNorm * fh).toInt().coerceIn(1, full.height)
                    )
                    val width = (fullRect.right - fullRect.left).coerceAtLeast(1)
                    val height = (fullRect.bottom - fullRect.top).coerceAtLeast(1)
                    Bitmap.createBitmap(full, fullRect.left, fullRect.top, width, height)
                }

                withContext(Dispatchers.Main) {
                    // 移除缓存后再替换
                    currentImageKey?.let { bitmapCache.remove(it) }

                    baseBitmap = croppedPreview
                    currentBitmap?.recycle()
                    currentBitmap = null
                    oldFull?.recycle()
                    fullBitmap = croppedFull
                    applyAllEffects()
                    exitCropMode()
                    if (hasOverlays) {
                        overlaysManager.adjustAfterCrop(
                            cropRectPreview,
                            previewSrc.width,
                            previewSrc.height
                        )
                    }
                }
            } catch (oom: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "图片过大，裁剪失败", Toast.LENGTH_LONG).show()
                    exitCropMode()
                }
            }
        }
    }

    private fun exitCropMode() {
        isCropMode = false
        cropOverlayView.visibility = View.GONE
        cropControlLayout.visibility = View.GONE
        setEditButtonsEnabled(true)
    }

    // ---------- 亮度 / 对比度 ----------

    private fun initSeekBars() {
        var brightnessJob: Job? = null
        var contrastJob: Job? = null

        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    brightnessJob?.cancel()
                    brightnessJob = uiScope.launch {
                        delay(50)
                        currentBrightness = (progress - 100).toFloat()
                        applyAllEffects()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    contrastJob?.cancel()
                    contrastJob = uiScope.launch {
                        delay(50)
                        currentContrast = 0.5f + progress / 100f
                        applyAllEffects()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------- 底部编辑按钮 ----------

    private fun initEditButtons() {
        findViewById<Button>(R.id.btnCropMenu).setOnClickListener { showCropMenu() }
        findViewById<Button>(R.id.btnRotateMenu).setOnClickListener { showRotateMenu() }
        findViewById<Button>(R.id.btnAddText).setOnClickListener {
            if (!isCropMode) overlaysManager.showAddTextDialog()
        }
        findViewById<Button>(R.id.btnFilter).setOnClickListener { showFilterDialog() }
        findViewById<Button>(R.id.btnSticker).setOnClickListener {
            if (!isCropMode) overlaysManager.showStickerDialog()
        }
    }

    private fun setEditButtonsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btnCropMenu).isEnabled = enabled
        findViewById<Button>(R.id.btnRotateMenu).isEnabled = enabled
        findViewById<Button>(R.id.btnAddText).isEnabled = enabled
        findViewById<Button>(R.id.btnFilter).isEnabled = enabled
        findViewById<Button>(R.id.btnSticker).isEnabled = enabled

        seekBrightness.isEnabled = enabled
        seekContrast.isEnabled = enabled
        btnOpenAlbum.isEnabled = enabled
        btnOpenCamera.isEnabled = enabled
        btnSaveImage.isEnabled = enabled
    }

    // ---------- 异步加载图片：preview + full 双轨 ----------

    private fun loadBitmapFromUri(uri: Uri) {
        loadJob?.cancel()
        applyJob?.cancel()

        Toast.makeText(this, "加载中...", Toast.LENGTH_SHORT).show()

        val imageKey = uri.toString()
        currentImageKey = imageKey

        // 先尝试从 LruCache 取
        val cached = bitmapCache.get(imageKey)
        if (cached != null) {
            // [Fix] 这里的 cached.preview 如果回收了，必须 remove
            if (cached.preview.isRecycled || cached.full?.isRecycled == true) {
                bitmapCache.remove(imageKey)
            } else {
                baseBitmap = cached.preview
                currentBitmap?.recycle()
                currentBitmap = null
                fullBitmap?.recycle()
                fullBitmap = cached.full
                resetAdjustments()
                Toast.makeText(this, "从缓存加载完成", Toast.LENGTH_SHORT).show()
                return
            }
        }

        loadJob = uiScope.launch(Dispatchers.IO) {
            try {
                val (preview, full) = decodePreviewAndFullFromUri(uri)

                withContext(Dispatchers.Main) {
                    if (currentImageKey != imageKey) {
                        preview?.recycle()
                        full?.recycle()
                        return@withContext
                    }

                    if (preview != null) {
                        baseBitmap?.recycle()
                        currentBitmap?.recycle()
                        fullBitmap?.recycle()

                        baseBitmap = preview
                        currentBitmap = null
                        fullBitmap = full

                        bitmapCache.put(imageKey, CachedImage(preview, full))
                        resetAdjustments()
                        Toast.makeText(this@MainActivity, "加载完成", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "加载图片失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (oom: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "图片太大，无法加载", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "加载失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // 解码预览 + 大图
    private fun decodePreviewAndFullFromUri(uri: Uri): Pair<Bitmap?, Bitmap?> {
        val preview =
            decodeSampledBitmapFromUri(uri, PREVIEW_MAX_SIZE, PREVIEW_MAX_SIZE, Bitmap.Config.RGB_565)
        val full =
            decodeSampledBitmapFromUri(uri, FULL_MAX_SIZE, FULL_MAX_SIZE, Bitmap.Config.ARGB_8888)
        return Pair(preview, full)
    }

    private fun decodeSampledBitmapFromUri(
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int,
        config: Bitmap.Config
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = config

            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun resetAdjustments() {
        seekBrightness.progress = 100
        seekContrast.progress = 50
        currentBrightness = 0f
        currentContrast = 1f
        currentFilter = FilterType.ORIGINAL
        overlaysManager.clearOverlays()
        exitCropMode()
        applyAllEffects()
    }

    private fun resetAdjustmentsWithoutClearingOverlays() {
        seekBrightness.progress = 100
        seekContrast.progress = 50
        currentBrightness = 0f
        currentContrast = 1f
        currentFilter = FilterType.ORIGINAL
        applyAllEffects()
    }

    // ---------- 预览：应用滤镜 + 亮度对比度（只对 preview 位图） ----------

    private fun applyAllEffects() {
        val src = baseBitmap ?: return
        if (src.isRecycled) return

        applyJob?.cancel()

        applyJob = uiScope.launch(Dispatchers.Default) {
            try {
                val cm = ColorMatrix()
                getFilterMatrix(currentFilter)?.let { cm.postConcat(it) }
                cm.postConcat(createBrightnessContrastMatrix(currentBrightness, currentContrast))

                val result =
                    Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(cm)
                    isDither = true
                    isFilterBitmap = true
                }
                canvas.drawBitmap(src, 0f, 0f, paint)

                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && applyJob?.isActive == true) {
                        currentBitmap?.recycle()
                        currentBitmap = result
                        photoView.setImageBitmap(result)
                    } else {
                        result.recycle()
                    }
                }
            } catch (oom: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "图片过大，无法应用效果",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "应用效果失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getFilterMatrix(type: FilterType): ColorMatrix? {
        return when (type) {
            FilterType.ORIGINAL -> null
            FilterType.BW -> ColorMatrix(
                floatArrayOf(
                    0.33f, 0.59f, 0.11f, 0f, 0f,
                    0.33f, 0.59f, 0.11f, 0f, 0f,
                    0.33f, 0.59f, 0.11f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.RETRO -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0.5f, 0.1f, 0f, -20f,
                    0.3f, 0.8f, 0.2f, 0f, -20f,
                    0.2f, 0.3f, 0.7f, 0f, -20f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.FRESH -> ColorMatrix(
                floatArrayOf(
                    1.1f, 0f, 0f, 0f, 10f,
                    0f, 1.1f, 0f, 0f, 10f,
                    0f, 0f, 1.1f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.WARM -> ColorMatrix(
                floatArrayOf(
                    1.1f, 0.1f, 0f, 0f, 10f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 0.9f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.COOL -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0f, 0f, -10f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 1.1f, 0f, 15f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
    }

    private fun createBrightnessContrastMatrix(brightness: Float, contrast: Float): ColorMatrix {
        val b = brightness * 2.55f
        val c = contrast
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, b,
                0f, c, 0f, 0f, b,
                0f, 0f, c, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    // ---------- 旋转 / 翻转 / 固定比例裁剪（preview + full 同步） ----------

    private fun rotateImage(degree: Float) {
        val previewSrc = baseBitmap ?: currentBitmap ?: return
        val fullSrc = fullBitmap
        val hasOverlays = textOverlayContainer.childCount > 0
        val originalWidth = previewSrc.width
        val originalHeight = previewSrc.height

        uiScope.launch(Dispatchers.Default) {
            try {
                val matrixPreview = Matrix().apply { postRotate(degree) }
                val rotatedPreview = Bitmap.createBitmap(
                    previewSrc, 0, 0, previewSrc.width, previewSrc.height, matrixPreview, true
                )

                val rotatedFull = fullSrc?.let { full ->
                    val m = Matrix().apply { postRotate(degree) }
                    Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
                }

                withContext(Dispatchers.Main) {
                    // 编辑后，原图已废弃，需从缓存中清理 key
                    currentImageKey?.let { bitmapCache.remove(it) }

                    baseBitmap?.recycle()
                    baseBitmap = rotatedPreview
                    currentBitmap?.recycle()
                    currentBitmap = null

                    fullBitmap?.recycle()
                    fullBitmap = rotatedFull

                    applyAllEffects()
                    if (hasOverlays) {
                        overlaysManager.adjustAfterRotation(
                            degree,
                            originalWidth,
                            originalHeight
                        )
                    }
                }
            } catch (oom: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "图片过大，旋转失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun flipImage(horizontal: Boolean) {
        val previewSrc = baseBitmap ?: currentBitmap ?: return
        val fullSrc = fullBitmap
        val hasOverlays = textOverlayContainer.childCount > 0
        val originalWidth = previewSrc.width
        val originalHeight = previewSrc.height

        uiScope.launch(Dispatchers.Default) {
            try {
                val matrixPreview = Matrix().apply {
                    if (horizontal) preScale(-1f, 1f) else preScale(1f, -1f)
                }
                val flippedPreview = Bitmap.createBitmap(
                    previewSrc, 0, 0, previewSrc.width, previewSrc.height, matrixPreview, true
                )

                val flippedFull = fullSrc?.let { full ->
                    val m = Matrix().apply {
                        if (horizontal) preScale(-1f, 1f) else preScale(1f, -1f)
                    }
                    Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
                }

                withContext(Dispatchers.Main) {
                    currentImageKey?.let { bitmapCache.remove(it) }

                    baseBitmap?.recycle()
                    baseBitmap = flippedPreview
                    currentBitmap?.recycle()
                    currentBitmap = null

                    fullBitmap?.recycle()
                    fullBitmap = flippedFull

                    applyAllEffects()
                    if (hasOverlays) {
                        overlaysManager.adjustAfterFlip(
                            horizontal,
                            originalWidth,
                            originalHeight
                        )
                    }
                }
            } catch (oom: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "图片过大，翻转失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cropCurrentImage(aspectRatio: Float?) {
        val previewSrc = baseBitmap ?: currentBitmap ?: return
        val fullSrc = fullBitmap
        val hasOverlays = textOverlayContainer.childCount > 0

        val w = previewSrc.width
        val h = previewSrc.height

        val rectPreview: Rect = if (aspectRatio == null) {
            val top = (h * 0.2f).toInt()
            val bottom = (h * 0.8f).toInt()
            Rect(0, top, w, bottom)
        } else {
            val currentRatio = w.toFloat() / h
            if (currentRatio > aspectRatio) {
                val targetW = (h * aspectRatio).toInt()
                val left = (w - targetW) / 2
                Rect(left, 0, left + targetW, h)
            } else {
                val targetH = (w / aspectRatio).toInt()
                val top = (h - targetH) / 2
                Rect(0, top, w, top + targetH)
            }
        }

        val leftNorm = rectPreview.left.toFloat() / w
        val topNorm = rectPreview.top.toFloat() / h
        val rightNorm = rectPreview.right.toFloat() / w
        val bottomNorm = rectPreview.bottom.toFloat() / h

        uiScope.launch(Dispatchers.Default) {
            try {
                val croppedPreview = Bitmap.createBitmap(
                    previewSrc,
                    rectPreview.left,
                    rectPreview.top,
                    rectPreview.width(),
                    rectPreview.height()
                )

                val croppedFull = fullSrc?.let { full ->
                    val fw = full.width.toFloat()
                    val fh = full.height.toFloat()
                    val fullRect = Rect(
                        (leftNorm * fw).toInt().coerceIn(0, full.width - 1),
                        (topNorm * fh).toInt().coerceIn(0, full.height - 1),
                        (rightNorm * fw).toInt().coerceIn(1, full.width),
                        (bottomNorm * fh).toInt().coerceIn(1, full.height)
                    )
                    val wF = (fullRect.right - fullRect.left).coerceAtLeast(1)
                    val hF = (fullRect.bottom - fullRect.top).coerceAtLeast(1)
                    Bitmap.createBitmap(full, fullRect.left, fullRect.top, wF, hF)
                }

                withContext(Dispatchers.Main) {
                    currentImageKey?.let { bitmapCache.remove(it) }

                    baseBitmap?.recycle()
                    baseBitmap = croppedPreview
                    currentBitmap?.recycle()
                    currentBitmap = null

                    fullBitmap?.recycle()
                    fullBitmap = croppedFull

                    applyAllEffects()
                    if (hasOverlays) {
                        overlaysManager.adjustAfterCrop(rectPreview, w, h)
                    }
                }
            } catch (oom: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "图片过大，裁剪失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- 裁剪 / 旋转菜单 ----------

    private fun showCropMenu() {
        if (baseBitmap == null && currentBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf("自由裁剪", "1:1", "16:9", "9:16", "4:3", "3:4")
        AlertDialog.Builder(this)
            .setTitle("选择裁剪方式")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startCropMode()
                    1 -> cropCurrentImage(1f / 1f)
                    2 -> cropCurrentImage(16f / 9f)
                    3 -> cropCurrentImage(9f / 16f)
                    4 -> cropCurrentImage(4f / 3f)
                    5 -> cropCurrentImage(3f / 4f)
                }
            }.show()
    }

    private fun showRotateMenu() {
        if (baseBitmap == null && currentBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf("旋转90°", "水平翻转", "垂直翻转")
        AlertDialog.Builder(this)
            .setTitle("旋转 / 翻转")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> rotateImage(90f)
                    1 -> flipImage(true)
                    2 -> flipImage(false)
                }
            }.show()
    }

    // ---------- 滤镜弹窗 ----------

    private fun showFilterDialog() {
        if (baseBitmap == null && currentBitmap == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf("原图", "黑白", "复古", "清新", "暖色调", "冷色调")
        AlertDialog.Builder(this)
            .setTitle("选择滤镜")
            .setItems(items) { _, which ->
                currentFilter = when (which) {
                    0 -> FilterType.ORIGINAL
                    1 -> FilterType.BW
                    2 -> FilterType.RETRO
                    3 -> FilterType.FRESH
                    4 -> FilterType.WARM
                    5 -> FilterType.COOL
                    else -> FilterType.ORIGINAL
                }
                applyAllEffects()
            }.show()
    }

    // ---------- 自定义颜色对话框所用的小工具 ----------

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

    // ---------- 保存图片（preview 快速预览 + full 后台合成） ----------

    private fun saveCurrentImage() {
        if (isCropMode) {
            Toast.makeText(this, "请先完成或取消裁剪", Toast.LENGTH_SHORT).show()
            return
        }
        if (fullBitmap == null && baseBitmap == null) {
            Toast.makeText(this, "请先打开一张图片", Toast.LENGTH_SHORT).show()
            return
        }

        // 在主线程抓取 overlay（View.draw 必须在主线程）
        val overlayBitmap: Bitmap? =
            if (textOverlayContainer.width > 0 && textOverlayContainer.height > 0) {
                try {
                    Bitmap.createBitmap(
                        textOverlayContainer.width,
                        textOverlayContainer.height,
                        Bitmap.Config.ARGB_8888
                    ).apply {
                        val c = Canvas(this)
                        textOverlayContainer.draw(c)
                    }
                } catch (e: Exception) {
                    null
                }
            } else null

        uiScope.launch {
            Toast.makeText(this@MainActivity, "保存中...", Toast.LENGTH_SHORT).show()

            val result = renderFullBitmapWithCurrentSettings(overlayBitmap)

            overlayBitmap?.recycle()

            if (result == null) {
                Toast.makeText(
                    this@MainActivity,
                    "保存失败：内存不足或图片为空",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                val fileName = "edited_${System.currentTimeMillis()}.jpg"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStore(fileName, result)
                } else {
                    saveToFile(fileName, result)
                }
            }

            result.recycle()

            Toast.makeText(
                this@MainActivity,
                if (success) "已保存到相册" else "保存失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 在后台线程对 fullBitmap 做一次完整合成：
     * 1. 滤镜 + 亮度对比度
     * 2. 合成 overlay（文字 + 贴纸）
     * 3. 添加“训练营”水印
     */
    private suspend fun renderFullBitmapWithCurrentSettings(overlayBitmap: Bitmap?): Bitmap? =
        withContext(Dispatchers.Default) {
            val src = fullBitmap ?: baseBitmap ?: return@withContext null
            // Safety check
            if (src.isRecycled) return@withContext null

            try {
                val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)

                // 先画应用滤镜 / 亮度 / 对比度后的底图
                val cm = ColorMatrix()
                getFilterMatrix(currentFilter)?.let { cm.postConcat(it) }
                cm.postConcat(createBrightnessContrastMatrix(currentBrightness, currentContrast))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(cm)
                    isDither = true
                    isFilterBitmap = true
                }
                canvas.drawBitmap(src, 0f, 0f, paint)

                // 再画 overlay（文字 + 贴纸容器整体截屏）
                overlayBitmap?.let { overlay ->
                    val scaleX = src.width.toFloat() / overlay.width
                    val scaleY = src.height.toFloat() / overlay.height
                    val scale = min(scaleX, scaleY)
                    val m = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(
                            (src.width - overlay.width * scale) / 2,
                            (src.height - overlay.height * scale) / 2
                        )
                    }
                    canvas.drawBitmap(overlay, m, null)
                }

                // 最后画“训练营”水印
                val wmText = "训练营"
                val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    alpha = 200
                    textSize = src.width * 0.04f
                    setShadowLayer(3f, 2f, 2f, Color.BLACK)
                }
                val bounds = Rect()
                wmPaint.getTextBounds(wmText, 0, wmText.length, bounds)
                val margin = src.width * 0.02f
                val x = src.width - bounds.width() - margin
                val y = src.height - margin
                canvas.drawText(wmText, x, y, wmPaint)

                result
            } catch (oom: OutOfMemoryError) {
                null
            }
        }

    private fun saveToMediaStore(fileName: String, bitmap: Bitmap): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyEditor")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri =
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveToFile(fileName: String, bitmap: Bitmap): Boolean {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "MyEditor"
            )
            if (!dir.exists() && !dir.mkdirs()) return false
            val imageFile = File(dir, fileName)
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}