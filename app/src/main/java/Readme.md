# 图片编辑器（Android）
多功能图片编辑 App，支持相册选图、相机拍照、裁剪、旋转、滤镜、文字与贴纸叠加、夜间模式以及带水印保存等功能。
本项目使用 Kotlin + AndroidX 实现，重点兼顾功能完整度与性能要求（启动速度、编辑响应速度、内存占用等）。

## 1. 功能概览（核心功能与部分扩展功能）



1. 相册选图
    - 使用系统的 `ACTION_OPEN_DOCUMENT`/`GetContent` 图片选择器。
    - 支持「Photos / Albums」两层 Tab。
    - 选中后进入编辑界面，可对大图进行缩放查看。

2. 相机拍照
    - 通过 `Camera` + `FileProvider` 保存到临时文件，再加载进编辑页。
    - 处理相机权限拒绝情况，给出友好提示。

3. 图片缩放查看
    - 使用 `PhotoView`（`com.github.chrisbanes.photoview.PhotoView`）。
    - 支持双指缩放、拖拽平移查看细节。

4. 图片裁剪
    - 支持两种裁剪方式：
        - 自定义拖拽裁剪框（`CropOverlayView`）；
        - 固定比例裁剪：`1:1`、`4:3`、`3:4`、`16:9`、`9:16` 等。
    - 自由裁剪时，裁剪框可拖动四角控制点及整体移动。

5. 旋转与翻转
    - 旋转：90° 顺时针。
    - 翻转：水平翻转、垂直翻转。
    - 旋转 / 翻转时会同步调整贴纸层的旋转与位置。

6. 亮度与对比度调整
    - 亮度：范围 `[-100, 100]`。
    - 对比度：范围 `[0.5, 1.5]`。
    - 使用 `SeekBar` 调节，基于 `ColorMatrix` 实时预览。

7. 文字编辑模块
    - 点击「添加文字」可在图上创建 `DraggableTextView`。
    - 支持：
        - 拖动位置；
        - 修改文字内容；
        - 字号（12–36sp）；
        - 透明度（50%–100%）；
        - 粗体 / 斜体；
        - 常用字体族（默认 / 衬线 / 无衬线 / 等宽）；
        - 预设颜色 + RGB 自定义颜色。
    - 长按文字弹出编辑对话框。
    - 文字在保存时会合成到图片上。

8. 文字平移与旋转（扩展）
    - 文字支持拖动平移；
    - 旋转功能目前主要应用在贴纸层，文字可通过旋转图片实现整体效果。

9. 贴纸功能（扩展）
    - 提供至少 10 个内置贴纸图标（使用系统 drawable 模拟）。
    - 通过网格弹窗选择贴纸。
    - 贴纸可：
        - 拖动；
        - 双指缩放；
        - 双指旋转；
        - 长按删除。

10. 滤镜功能（扩展）
    - 内置 6 种滤镜（基于 `ColorMatrix` 手动实现）：
        - 原图
        - 黑白
        - 复古
        - 清新
        - 暖色调
        - 冷色调
    - 通过弹窗列表选择滤镜，实时预览。

11. 夜间模式（扩展）*
    - 勾选「夜间模式」切换深色 / 浅色主题。
    - 切换时通过 `AppCompatDelegate.setDefaultNightMode` 触发 `Activity` 重建。
    - 对图片与 overlay 做了状态保存与恢复，切换主题不会丢失当前编辑内容。

12. 保存与水印
    - 将当前编辑结果（图像 + 文字 + 贴纸）合成一张新图。
    - 在右下角自动加上「训练营」水印文字。
    - Android 10+：
        - 使用 `MediaStore` + `RELATIVE_PATH=Pictures/MyEditor` 写入系统相册；
    - Android 10 以下：
        - 写入 `Pictures/MyEditor` 目录。
    - 保存异常时给予提示。

## 2. 项目结构

实际包名：`com.example.mycamera`

```text
app/
 ├─ manifests/
 │   └─ AndroidManifest.xml
 ├─ java + kotlin
 │   └─ com.example.mycamera/
 │       ├─ MainActivity.kt          // 核心 UI 与业务控制
 │       ├─ CropOverlayView.kt       // 自由裁剪覆盖层 View
 │       ├─ DraggableTextView.kt     // 可拖动文字
 │       ├─ DraggableStickerView.kt  // 可拖动/缩放/旋转贴纸
 │       └─ OverlaysManager.kt       // 文字/贴纸 overlay 管理
 ├─ res/
 │   ├─ layout/
 │   │   ├─ activity_main.xml        // 主界面布局
 │   │   ├─ dialog_edit_text.xml     // 文字编辑弹窗
 │   │   └─ dialog_color_picker.xml  // RGB 取色器弹窗
 │   ├─ menu/
 │   │   └─ menu_top.xml             // 顶部菜单
 │   ├─ values/
 │   │   ├─ colors.xml
 │   │   ├─ dimens.xml
 │   │   ├─ strings.xml
 │   │   └─ themes.xml               // 日间/夜间主题
 │   └─ xml/
 │       └─ file_paths.xml           // FileProvider 文件路径配置
```

## 3. 核心类

1. MainActivity
    - UI 控制中心，处理所有按钮点击、权限、ActivityResult 回调、状态保存与恢复、调用图像处理逻辑和 overlay 管理。

2. CropOverlayView
   - 自定义 View，用于绘制裁剪框和阴影，并处理用户拖动四角/中心来调整裁剪区域。

3. DraggableTextView
   - 继承 AppCompatTextView，实现 onTouchEvent 实时更新 x / y 来拖动文字；长按进入编辑。

4. DraggableStickerView
   - 继承 AppCompatImageView，支持单指拖动 + 双指缩放 + 双指旋转 + 长按删除。

5. OverlaysManager
   - 封装对 textOverlayContainer 的操作：添加文字/贴纸、保存和恢复 overlay 的相对坐标、截图 overlay 等。


## 4.开发与运行环境

1. 语言：Kotlin

2. IDE：Android Studio Hedgehog / Iguana 及以上

3. minSdk：建议 24+

4. targetSdk：34 或最新

5. 测试设备：
    - 模拟器：Pixel 7 Pro API 36

## 5.使用说明

1. 相册选图
   - 点击顶部「相册选图」；
   - 在系统选择器中选择照片；
   - 选中后会自动载入编辑界面。

2. 相机拍照
   - 点击「相机拍照」；
   - 首次会弹出相机权限请求，允许后即可拍照；
   - 拍照完成后自动回到编辑界面。

3. 裁剪
   - 底部点击「裁剪」→弹出菜单：
   - 自由裁剪：进入裁剪模式，拖动裁剪框调整区域；
   - 其他比例：直接按指定比例裁剪。

4. 旋转 / 翻转
   - 底部点击「旋转/翻转」；
   - 选择 90° 旋转 / 水平翻转 / 垂直翻转。

5. 亮度 / 对比度调整
   - 通过下方滑块调整；
   - 会实时应用到预览图像。

6. 添加文字
   - 点击「添加文字」；
   - 在弹窗中输入文本（真机可输入中文）、调整字号/透明度/颜色等；
   - 确认后文字会出现在图上，可拖动；

7. 贴纸
   - 点击「贴纸」；
   - 在网格列表中点选一个贴纸；
   - 贴纸添加后可拖动、双指缩放旋转、长按删除。

8. 滤镜
   - 点击「滤镜」；
   - 在弹窗中选择某种滤镜效果，立即预览。

9. 夜间模式
   - 顶部勾选「夜间模式」；
   - UI 切为深色主题，编辑内容会从状态中恢复，不会丢失。

10. 保存
    - 点击「保存图片」；
    - 在后台异步合成图片 + 叠加文字贴纸 + 加水印；
    - 成功后提示「已保存到相册」。

## 6.性能与内存说明

- 对加载图片使用采样缩放（inSampleSize）与 RGB_565，限制最大边约 2048 像素，降低内存占用。
- 所有耗时操作（加载、裁剪、旋转、保存）均在 Dispatchers.IO / Default 的协程中进行，主线程只负责更新 UI。
- 使用基于键（Uri）的 Bitmap 缓存，避免重复解码；并对缓存大小做了上限控制，防止长期持有大图。
- 亮度 / 对比度 / 滤镜操作基于 ColorMatrix 单次绘制，配合轻量级预览图，可在 0.5 秒内完成预览更新。
- 经实际模拟器测试：连续编辑多张图 App 无明显卡顿或 OOM。
- 更详细的性能优化说明见《技术文档》。