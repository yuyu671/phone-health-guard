package com.anxin.guard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 全屏"该休息啦"提醒，配合设备管理器锁屏使用。
 *
 * 设计要点：
 * - 使用悬浮窗实现，不劫持桌面；
 * - 不获取焦点，用户仍能下拉通知栏、接听来电（来电界面会盖在悬浮窗之上）；
 * - 服务检测到通话中会立即隐藏并暂停锁屏，通话结束后再恢复。
 */
class OverlayController(
    private val context: Context,
    private val onEmergencyDial: () -> Unit
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var root: View? = null
    private var countdownView: TextView? = null

    private fun dp(value: Int): Int = (value * density).toInt()

    fun show(remainMs: Long) {
        main.post {
            if (root == null) addOverlay()
            countdownView?.text = formatCountdown(remainMs)
        }
    }

    fun hide() {
        main.post {
            val view = root ?: return@post
            root = null
            countdownView = null
            try {
                windowManager.removeView(view)
            } catch (ignored: IllegalArgumentException) {
                // 已经被系统移除
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun addOverlay() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(96), dp(32), dp(48))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF12211F.toInt(), 0xFF1D3631.toInt())
            )
        }

        container.addView(label("手机运行内存不足", 30f, Color.WHITE, bold = true))
        container.addView(
            label(
                "后台程序有点多，手机正在自动清理",
                16f,
                Color.parseColor("#C9DBD5"),
                topDp = 12
            )
        )
        container.addView(
            label(
                "清理完成倒计时",
                14f,
                Color.parseColor("#8FA8A2"),
                topDp = 40
            )
        )

        val countdown = label("00:00", 60f, Color.parseColor("#FFD27A"), bold = true, topDp = 4)
        countdownView = countdown
        container.addView(countdown)

        container.addView(
            label(
                "清理结束后，手机会自动恢复正常。",
                14f,
                Color.parseColor("#8FA8A2"),
                topDp = 8
            )
        )
        container.addView(
            label(
                "休息一下：起身走走、喝口水、看看窗外",
                16f,
                Color.parseColor("#E3F0EC"),
                topDp = 48
            )
        )

        val dialButton = label("紧急拨号", 17f, Color.parseColor("#12211F"), bold = true, topDp = 32)
        dialButton.setPadding(dp(36), dp(14), dp(36), dp(14))
        dialButton.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.parseColor("#FFD27A"))
        }
        dialButton.setOnClickListener {
            // 先确认拨号界面真的能打开，再放行；否则老人会白白拿到一段自由使用时间
            val launched = try {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (ignored: Throwable) {
                false
            }
            if (launched) onEmergencyDial()
        }
        container.addView(dialButton)

        container.addView(
            label(
                "紧急通话、短信收发不受影响",
                13f,
                Color.parseColor("#7F9993"),
                topDp = 18
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(container, params)
            root = container
        } catch (ignored: Throwable) {
            root = null
            countdownView = null
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun label(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        topDp: Int = 0
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(topDp)
        }
    }
}
