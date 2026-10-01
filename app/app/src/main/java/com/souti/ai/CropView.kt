package com.souti.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** 裁剪视图：图片按 fitXY 铺满（进来时已经是"预览看到的那一帧"，所以画面与预览完全一致），
 *  框内保持原样不加任何遮罩，只在框外压一层官方同款 #B0000000 暗罩，
 *  上面再叠白色裁剪框 + 四角抱括。拖角改大小、拖框平移，crop() 从原始分辨率位图里取像素。 */
class CropView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null)
    : View(ctx, attrs) {

    private var bmp: Bitmap? = null
    private val imgM = Matrix()          // bitmap -> view
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB0000000.toInt(); style = Paint.Style.FILL
    }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 8f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE
    }
    private val rect = RectF()           // 裁剪框（view 坐标）
    private var mode = 0                 // 0 无 1 平移 2-5 左上/右上/左下/右下
    private var lastX = 0f; private var lastY = 0f

    fun setImage(b: Bitmap) {
        if (bmp !== b) release()
        bmp = b
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutImage()
    }

    private fun layoutImage() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        imgM.reset()
        imgM.postScale(width.toFloat() / b.width, height.toFloat() / b.height)
        val iw = width * 0.84f; val ih = height * 0.80f
        rect.set(width / 2f - iw / 2f, height / 2f - ih / 2f,
                 width / 2f + iw / 2f, height / 2f + ih / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, imgM, paint)
        drawScrim(canvas)
        canvas.drawRect(rect, frame)
        // 四角抱括：拖动时每帧都画，这里不留任何数组/对象分配，免得低端机一路掉帧一路 GC
        val len = 46f
        canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, corner)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, corner)
        canvas.drawLine(rect.right, rect.top, rect.right - len, rect.top, corner)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, corner)
        canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, corner)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - len, corner)
        canvas.drawLine(rect.right, rect.bottom, rect.right - len, rect.bottom, corner)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - len, corner)
    }

    /** 用完就把位图还给系统：一帧取景图在这台机器上是 800x600x4 ≈ 2MB。 */
    fun release() {
        bmp?.takeIf { !it.isRecycled }?.recycle()
        bmp = null
        invalidate()
    }

    /** 框外压暗、框内一个像素都不动：直接铺上/下/左/右四条，
     *  不用 Path 挖洞（这台机器的硬件画布对 EVEN_ODD 挖洞不可靠，实测会把整张图压暗）。 */
    private fun drawScrim(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, rect.top, scrim)                       // 上
        canvas.drawRect(0f, rect.bottom, w, h, scrim)                     // 下
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, scrim)      // 左
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, scrim)      // 右
    }

    private fun cornerAt(x: Float, y: Float): Int {
        val t = 70f
        fun hit(cx: Float, cy: Float, m: Int) =
            if (Math.abs(x - cx) < t && Math.abs(y - cy) < t) m else 0
        return hit(rect.left, rect.top, 2)
            .takeIf { it != 0 } ?: hit(rect.right, rect.top, 3)
            .takeIf { it != 0 } ?: hit(rect.left, rect.bottom, 4)
            .takeIf { it != 0 } ?: hit(rect.right, rect.bottom, 5)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (bmp == null) return false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                mode = cornerAt(ev.x, ev.y).takeIf { it != 0 }
                    ?: if (rect.contains(ev.x, ev.y)) 1 else 0
                lastX = ev.x; lastY = ev.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX; val dy = ev.y - lastY
                when (mode) {
                    1 -> rect.offset(dx, dy)
                    2 -> { rect.left += dx; rect.top += dy }
                    3 -> { rect.right += dx; rect.top += dy }
                    4 -> { rect.left += dx; rect.bottom += dy }
                    5 -> { rect.right += dx; rect.bottom += dy }
                }
                if (mode >= 2) {
                    rect.left = rect.left.coerceIn(0f, width - 120f)
                    rect.right = rect.right.coerceIn(rect.left + 120f, width.toFloat())
                    rect.top = rect.top.coerceIn(0f, height - 120f)
                    rect.bottom = rect.bottom.coerceIn(rect.top + 120f, height.toFloat())
                } else if (mode == 1) {
                    if (rect.left < 0) rect.offset(-rect.left, 0f)
                    if (rect.right > width) rect.offset(width - rect.right, 0f)
                    if (rect.top < 0) rect.offset(0f, -rect.top)
                    if (rect.bottom > height) rect.offset(0f, height - rect.bottom)
                }
                lastX = ev.x; lastY = ev.y
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; return true }
        }
        return super.onTouchEvent(ev)
    }

    /** 按当前框裁剪出位图（原图分辨率像素）。 */
    fun crop(): Bitmap? {
        val b = bmp ?: return null
        val inv = Matrix(); imgM.invert(inv)
        val src = RectF(rect)
        inv.mapRect(src)
        val l = src.left.toInt().coerceIn(0, b.width - 1)
        val t = src.top.toInt().coerceIn(0, b.height - 1)
        val r = src.right.toInt().coerceIn(l + 1, b.width)
        val bt = src.bottom.toInt().coerceIn(t + 1, b.height)
        return Bitmap.createBitmap(b, l, t, r - l, bt - t)
    }
}
