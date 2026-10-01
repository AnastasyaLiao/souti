package com.souti.ai

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/** 拍照页：TextureView 预览 + 点击对焦框 + 快门。
 *  M60pro 扫描摄像头物理倒装且 HAL 报 FRONT，预览靠 rotationX=180° 矫正。
 *
 *  成片直接取 CameraX 渲染好的那一帧预览（previewView.bitmap），只补一次视图上的上下翻转，
 *  所以裁剪图层和预览是同一取景范围、同一比例，不做任何色彩拉伸。
 *
 *  摄像头抢占：这台机器的 HAL 全局只允许一路摄像头，别的进程占着时 CameraService 在
 *  connectHelper 里直接 REJECT（实测日志 "Too many cameras already open"），API 27 上不给
 *  前台应用任何抢占接口。所以这里能做的全做了——进页面就把占用摄像头的后台进程端掉、
 *  监听摄像头空闲事件一有空立刻重绑、并且不再摆任何"摄像头不可用"的界面。 */
class PhotoActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cropLayer: View
    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var focusHider: Runnable = Runnable { }
    private var stopped = false
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var lastBindAt = 0L
    private var enteredAt = 0L
    private var freeStage = 0

    companion object {
        private const val TAG = "PhotoDiag"
        /** 端占用者的时间点：正常开摄像头 1 秒内就出画面，太早动手会误伤。 */
        private val FREE_STAGES = longArrayOf(2500, 6000, 12000)
        /** 只可能这几个是"抢摄像头的"：笔厂自带应用 + 系统/工厂相机。
         *  不做这层过滤会顺手杀掉支付宝、Clash 这类只是声明了 CAMERA 权限的后台，把网络代理也带崩。 */
        private val CAMERA_APP_PREFIXES = listOf(
            "com.jxw.", "com.mediatek.camera", "com.mediatek.emcamera", "com.teksun.")
        /** 这几个即使声明了 CAMERA 也不动：桌面、音乐、应用市场，杀了影响机器正常用。 */
        private val NEVER_KILL = listOf("com.jxw.launcher", "com.jxw.music", "com.jxw.sdbmarket")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)

        previewView = findViewById(R.id.preview)
        cropLayer = findViewById(R.id.ll_crop)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // 词典笔摄像头倒装+系统按前置镜像：预览=上下倒+左右镜像，整体绕 X 轴翻转 180° 直接抵消
        previewView.rotationX = 180f
        previewView.previewStreamState.observe(this, streamObserver)

        findViewById<View>(R.id.iv_shutter).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.slot_scan).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.ll_done).setOnClickListener { onCropDone() }
        findViewById<View>(R.id.ll_retake).setOnClickListener { onRetake() }
        findViewById<View>(R.id.slot_book).setOnClickListener {
            startActivity(Intent(this, WrongBookActivity::class.java))
        }
        previewView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) showFocus(ev.x, ev.y)
            false
        }
        findViewById<View>(R.id.tv_hint).setOnClickListener { ensureCamera() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            startCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>,
                                            results: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, results)
        if (requestCode == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            ensureCamera()
    }

    /** 摄像头一空出来立刻抢绑，别等 CameraX 自己的 1 秒轮询。 */
    private val camAvailability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (isStreaming() || stopped || cropLayer.visibility == View.VISIBLE) return
            Log.i(TAG, "camera $cameraId reported free -> rebind")
            lastBindAt = 0
            ui.post { ensureCamera() }
        }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        runCatching {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .registerAvailabilityCallback(camAvailability, ui)
        }
    }

    /** 退到后台立刻释放摄像头：这台设备只允许一路摄像头，不释放会让下个应用拿到全黑画面。 */
    override fun onStop() {
        super.onStop()
        stopped = true
        ui.removeCallbacks(watchdog)
        runCatching {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .unregisterAvailabilityCallback(camAvailability)
        }
        runCatching { provider?.unbindAll() }
    }

    override fun onResume() {
        super.onResume()
        enteredAt = SystemClock.elapsedRealtime()
        freeStage = 0
        lastBindAt = 0
        ensureCamera()
        ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, 400)
    }

    /** 出画面后就不必 400ms 空转了：改成 2s 慢巡，只负责到点端占用者；
     *  真正的"掉了立刻接回来"交给 streamState 监听和摄像头空闲回调，两者都是事件驱动的。 */
    private val watchdog = object : Runnable {
        override fun run() {
            val cropping = cropLayer.visibility == View.VISIBLE
            if (!cropping && !isStreaming()) {
                if (freeStage < FREE_STAGES.size &&
                    SystemClock.elapsedRealtime() - enteredAt > FREE_STAGES[freeStage]) {
                    Log.i(TAG, "still no stream, evicting camera holders (stage $freeStage)")
                    freeStage++
                    freeCameraHoggers()
                }
                ensureCamera()
            }
            ui.postDelayed(this, if (isStreaming()) 2000 else 400)
        }
    }

    /** 预览流一断就马上重绑，不用等下一轮巡检。 */
    private val streamObserver = androidx.lifecycle.Observer<PreviewView.StreamState> { st ->
        if (st != PreviewView.StreamState.STREAMING &&
            cropLayer.visibility != View.VISIBLE && !stopped) {
            lastBindAt = 0
            ensureCamera()
        }
    }

    private fun isStreaming() =
        previewView.previewStreamState.value == PreviewView.StreamState.STREAMING

    private fun ensureCamera() {
        val p = provider ?: return
        // 裁剪这段时间镜头完全用不上，页面已经退到后台也不用：这两态下谁叫都别重绑。
        // 之前只挡 isStreaming()，解绑后 CameraService 立刻报"空闲"，回调又把传感器点着了，
        // 省电那一步等于白做（实测日志：unbindAll 之后 0.3 秒就出现 rebind + preview resolution）。
        if (stopped || cropLayer.visibility == View.VISIBLE) return
        if (isStreaming()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBindAt < 1200) return
        lastBindAt = now
        startCamera()
    }

    /** 端掉可能占着摄像头的后台进程。KILL_BACKGROUND_PROCESSES 是 normal 权限（本机实测），
     *  AMS 只会结束真正的后台进程，前台应用和系统常驻进程它自己会跳过，不会误伤。 */
    private fun freeCameraHoggers() {
        bg.execute {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val self = packageName
            val targets = packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .filter { it != self && it != "android" }
                .filter { pkg -> CAMERA_APP_PREFIXES.any { pkg.startsWith(it) } && pkg !in NEVER_KILL }
                .filter { usesCamera(it) }
            targets.forEach { pkg ->
                runCatching { am.killBackgroundProcesses(pkg) }
                    .onSuccess { Log.i(TAG, "killBackgroundProcesses($pkg)") }
            }
        }
    }

    private fun usesCamera(pkg: String) = runCatching {
        packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }.getOrDefault(false)

    private fun startCamera() {
        val p = provider ?: return
        // 这台词典笔的镜头本身分辨率就低，预览必须按传感器最高分辨率出流，否则识别会糊
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        runCatching {
            p.unbindAll()
            camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
            preview.resolutionInfo?.let {
                Log.i(TAG, "preview resolution = ${it.resolution} rot=${it.rotationDegrees}")
            }
        }.onFailure { Log.i(TAG, "bind failed: ${it.message}") }
    }

    /** 点击处：先打真对焦，再画方框。
     *  注意坐标系：previewView 上挂了 rotationX=180°，Android 会把触摸点换算进视图自身空间，
     *  所以 ev.y 已经是"翻转后"的视图坐标（实测屏幕 120 → ev.y 280）。
     *  · 对焦要的就是这个视图坐标，交给 MeteringPointFactory 去映射传感器；
     *  · 方框画在未旋转的父容器上，必须再翻回屏幕坐标，否则框会跑到对称位置。 */
    private fun showFocus(x: Float, yView: Float) {
        val screenY = previewView.height - yView
        Log.i(TAG, "tap view=${previewView.width}x${previewView.height} " +
            "down=($x,$yView) boxAt=($x,$screenY)")
        focusAt(x, yView)
        val corners = arrayOf(findViewById<ImageView>(R.id.fz_tl), findViewById(R.id.fz_tr),
            findViewById(R.id.fz_bl), findViewById(R.id.fz_br))
        // 官方那颗对焦框是正方形的，之前写成 360x220 的扁框，看着就是"框住了一大片"而不是"对上了这一点"
        val side = 200f * resources.displayMetrics.density
        val l = (x - side / 2).coerceAtLeast(0f).coerceAtMost(previewView.width - side)
        val t = (screenY - side / 2).coerceAtLeast(0f).coerceAtMost(previewView.height - side)
        val size = 24f * resources.displayMetrics.density
        fun place(iv: ImageView?, left: Float, top: Float) {
            iv ?: return
            val lp = iv.layoutParams as FrameLayout.LayoutParams
            lp.width = size.toInt(); lp.height = size.toInt()
            lp.leftMargin = left.toInt(); lp.topMargin = top.toInt()
            iv.layoutParams = lp
            iv.visibility = View.VISIBLE
        }
        place(corners[0], l, t)
        place(corners[1], l + side - size, t)
        place(corners[2], l, t + side - size)
        place(corners[3], l + side - size, t + side - size)
        corners[0]?.removeCallbacks(focusHider)
        focusHider = Runnable { corners.forEach { it?.visibility = View.GONE } }
        corners[0]?.postDelayed(focusHider, 2000L)
    }

    /** 真对焦：把点击点交给 CameraX 设 AF 区域并触发一次自动对焦。
     *  这台 MTK 的 HAL 实测支持 auto/macro/continuous-picture（dumpsys media.camera 里 focus-mode-values），
     *  所以框不再是纯装饰；不支持的机型上 isFocusMeteringSupported 会挡掉，不会抛异常。 */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        runCatching {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            if (!cam.cameraInfo.isFocusMeteringSupported(action)) {
                Log.i(TAG, "AF metering NOT supported (impl=${cam.cameraInfo.implementationType})")
                return
            }
            val f = cam.cameraControl.startFocusAndMetering(action)
            f.addListener({
                val r = runCatching { f.get() }
                Log.i(TAG, "AF fired ok=${r.getOrNull()?.isFocusSuccessful} err=${r.exceptionOrNull()?.javaClass?.simpleName}")
            }, bg)
        }.onFailure { Log.w(TAG, "focus failed: ${it.message}") }
    }

    private fun takePhoto() {
        // previewView.bitmap 由 CameraX 按上屏变换生成，横竖比例与取景范围就是预览看到的那一帧。
        // 它不含我们在视图上额外加的 rotationX=180°（词典笔镜头倒装），所以只差一次上下翻转；
        // 之前自己拿 TextureView.getTransform() 搬矩阵，实测横竖缩放不一致（0.75 vs 1.333），画面会歪。
        val src = runCatching { previewView.bitmap }.getOrNull()
        if (src == null || src.width == 0) {
            Toast.makeText(this, "预览未就绪，请稍候", Toast.LENGTH_SHORT).show(); return
        }
        val m = Matrix(); m.postScale(1f, -1f, src.width / 2f, src.height / 2f)
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        src.recycle()   // 取景帧用完就还，别攒着等 GC
        Log.i(TAG, "cropFrame=${bmp.width}x${bmp.height} view=${previewView.width}x${previewView.height}")
        findViewById<CropView>(R.id.crop_view).setImage(bmp)
        cropLayer.visibility = View.VISIBLE
        // 裁剪这段时间镜头完全用不上：解绑让传感器/ISP 停下来，这是这一页最省电的一步
        runCatching { provider?.unbindAll() }
        lastBindAt = SystemClock.elapsedRealtime()
    }

    /** 裁剪完成：落盘并送识别。 */
    private fun onCropDone() {
        val cv = findViewById<CropView>(R.id.crop_view)
        val cropped = cv.crop() ?: run {
            Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show(); return
        }
        val dir = File(filesDir, "photos").apply { mkdirs() }
        val out = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        cropped.recycle()
        cv.release()
        cropLayer.visibility = View.GONE
        startActivity(Intent(this, ResultActivity::class.java).putExtra("photo", out.absolutePath))
        prunePhotos()
    }

    /** 拍照留下的原图只用来送识别，攒着纯属占盘；识别已经把它转成 base64 了，最多留最近 5 张。 */
    private fun prunePhotos() {
        bg.execute {
            runCatching {
                File(filesDir, "photos").listFiles()?.sortedByDescending { it.lastModified() }
                    ?.drop(5)?.forEach { it.delete() }
            }
        }
    }

    private fun onRetake() {
        findViewById<CropView>(R.id.crop_view).release()
        cropLayer.visibility = View.GONE
        // 裁剪时把镜头解绑了，重拍要立刻绑回来
        lastBindAt = 0
        ensureCamera()
        // 重绑这一秒内流状态一定是 IDLE，别拿"页面进来多久了"那套计时去判定"有人占着镜头"，
        // 否则每次重拍都会顺手把笔厂那几个后台进程端一遍（实测日志：重拍后 0.1 秒就 evicting stage 0/1）。
        enteredAt = SystemClock.elapsedRealtime()
        freeStage = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        stopped = true
        ui.removeCallbacks(watchdog)
        bg.shutdownNow()
    }
}
