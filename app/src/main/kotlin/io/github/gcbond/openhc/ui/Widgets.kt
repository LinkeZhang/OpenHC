package io.github.gcbond.openhc.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// 界面的配色、字号和公共控件。
//
// android4-support 分支：原来是 Compose 的 Material3 主题 + 一堆自定义 composable，现在全部换成
// 系统 View + 手写 drawable。不依赖任何 UI 库，minSdk 16 就能跑。

/** 配色。车机上常年开着，固定用深色，不跟系统深浅色走。 */
internal object Palette {
    const val Background = 0xFF111822.toInt()
    const val Card = 0x26FFFFFF
    const val Text = 0xFFFFFFFF.toInt()
    const val Muted = 0xFFB0B6C0.toInt()
    const val Accent = 0xFF8FB8FF.toInt()
    const val Warn = 0xFFFFB74D.toInt()
    const val On = 0xFF2D7DF6.toInt()
    const val SwitchOff = 0xFF4A505A.toInt()
    const val Button = 0xFF59606B.toInt()
    const val SegmentBackground = 0x1AFFFFFF
    const val Divider = 0x1FFFFFFF
    const val PinBox = 0xFF565D65.toInt()
    const val PinText = 0xFFEBF4FE.toInt()
    const val PrimaryStart = 0xFF1590FA.toInt()
    const val PrimaryEnd = 0xFF00ACEB.toInt()
    const val Secondary = 0xFF365173.toInt()
    const val Raised = 0xFF1E2632.toInt()
}

/** 公共尺寸（dp 转 px 用 [dp]）。 */
internal object Dimens {
    /** 页面左右留白（宽屏）。 */
    const val PageSide = 28f

    /** 页面左右留白（窄屏）。 */
    const val PageSideNarrow = 16f

    /** 视口宽度到这个值算宽屏。 */
    const val WIDE_PAGE_MIN = 900f

    /** 高度低于这个值算「矮屏」（横屏车机常见）。 */
    const val CompactHeight = 480f
}

internal fun Context.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

/** 圆角实心背景。 */
internal fun Context.roundedBackground(
    color: Int,
    radiusDp: Float,
    strokeColor: Int? = null,
    strokeDp: Float = 1f,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radiusDp).toFloat()
    setColor(color)
    if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
}

/** 渐变胶囊背景（主按钮）。 */
internal fun gradientPill(context: Context): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.PrimaryStart, Palette.PrimaryEnd)).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(24f).toFloat()
    }

/** 造一个统一风格的文字控件。 */
internal fun Context.textView(
    text: CharSequence,
    sizeSp: Float = 15f,
    color: Int = Palette.Text,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
}

/** 纵向排布。 */
internal fun Context.column(): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

/** 横向排布。 */
internal fun Context.row(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

/** 页面标题（首页/设置页左上）。 */
internal fun pageTitle(context: Context, text: String): TextView =
    context.textView(text, sizeSp = 26f, bold = true).apply {
        setPadding(0, context.dp(12f), 0, context.dp(4f))
    }

/** 顶栏小按钮（「设置」「返回」）。 */
internal fun topBarButton(context: Context, text: String, onClick: () -> Unit): TextView =
    context.textView(text, sizeSp = 14f, color = Palette.Text).apply {
        gravity = Gravity.CENTER
        setPadding(context.dp(16f), context.dp(9f), context.dp(16f), context.dp(9f))
        background = context.roundedBackground(Palette.Button, 20f)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

/** 卡片容器：半透明底 + 圆角 + 内边距。 */
internal fun cardBox(context: Context, content: LinearLayout): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.roundedBackground(Palette.Card, 14f)
        setPadding(context.dp(16f), context.dp(14f), context.dp(16f), context.dp(14f))
        addView(content)
    }

/** 胶囊按钮。[primary] 为 true 时用蓝色渐变，否则用次级底色。 */
internal fun pillButton(
    context: Context,
    text: String,
    primary: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
): TextView = context.textView(text, sizeSp = 15f, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(context.dp(22f), context.dp(11f), context.dp(22f), context.dp(11f))
    background = if (primary) gradientPill(context) else context.roundedBackground(Palette.Secondary, 24f)
    alpha = if (enabled) 1f else 0.45f
    isEnabled = enabled
    isClickable = enabled
    isFocusable = enabled
    setOnClickListener { if (enabled) onClick() }
}

/** 小号操作按钮（设置行右侧）。 */
internal fun actionButton(context: Context, text: String, onClick: () -> Unit): TextView =
    context.textView(text, sizeSp = 13f, color = Palette.Text).apply {
        gravity = Gravity.CENTER
        setPadding(context.dp(14f), context.dp(7f), context.dp(14f), context.dp(7f))
        background = context.roundedBackground(Palette.Button, 16f)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

/** 小标题（分组名、卡片名）。 */
internal fun cardHeading(context: Context, text: String): TextView =
    context.textView(text, sizeSp = 13f, color = Palette.Accent, bold = true)

/** 分组框：小标题 + 一组行，行之间画细线。 */
internal fun settingsGroup(context: Context, title: String, rows: List<View>): LinearLayout {
    val inner = context.column()
    inner.addView(cardHeading(context, title))
    inner.addView(View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(8f))
    })
    rows.forEachIndexed { index, row ->
        if (index > 0) {
            inner.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = context.dp(8f)
                    bottomMargin = context.dp(8f)
                }
                setBackgroundColor(Palette.Divider)
            })
        }
        inner.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return cardBox(context, inner)
}

/** 分组里的一行：标题 + 可选说明 + 右侧控件。 */
internal fun settingsRow(
    context: Context,
    title: String,
    description: String? = null,
    trailing: View? = null,
    onClick: (() -> Unit)? = null,
): LinearLayout {
    val left = context.column().apply {
        addView(context.textView(title, sizeSp = 15f))
        if (description != null) {
            addView(context.textView(description, sizeSp = 13f, color = Palette.Muted).apply {
                setPadding(0, context.dp(3f), 0, 0)
            })
        }
    }
    return context.row().apply {
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.dp(12f)
            })
        }
        setPadding(0, context.dp(6f), 0, context.dp(6f))
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
}

/** 一行说明文字（次要色）。 */
internal fun rowDescription(context: Context, text: String, warn: Boolean = false): TextView =
    context.textView(text, sizeSp = 13f, color = if (warn) Palette.Warn else Palette.Muted).apply {
        setPadding(0, context.dp(4f), 0, context.dp(4f))
    }

/**
 * 开关行。安卓 4.1 的 Switch 控件长得比较旧，这里用自绘的「胶囊开关」保持一致外观。
 */
internal fun switchRow(
    context: Context,
    title: String,
    description: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
): LinearLayout {
    val toggle = PillSwitchView(context).apply {
        isChecked = checked
        setOnCheckedChange { onChange(it) }
    }
    return settingsRow(context, title, description, toggle)
}

/**
 * 自绘胶囊开关：关是灰色空心，开是蓝色实心 + 白色圆点。
 * 用 [View] 手绘，避免老版本 Switch 的样式差异。
 */
internal class PillSwitchView(context: Context) : View(context) {
    var isChecked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var listener: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isChecked = !isChecked
            listener?.invoke(isChecked)
        }
    }

    fun setOnCheckedChange(l: (Boolean) -> Unit) {
        listener = l
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(48f), context.dp(28f))
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // 只有 RectF 版 drawRoundRect 是 API 1 就有的；float 版（和 Path.addRoundRect）要 API 21，
        // 安卓 4.1 上会 NoSuchMethodError。
        if (isChecked) {
            paint.color = Palette.On
            canvas.drawRoundRect(rectF(0f, 0f, w, h), r, r, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(w - r, r, r - context.dp(3f), paint)
        } else {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = context.dp(1.5f).toFloat()
            paint.color = Palette.SwitchOff
            val inset = paint.strokeWidth / 2f
            canvas.drawRoundRect(rectF(inset, inset, w - inset, h - inset), r, r, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = Palette.SwitchOff
            canvas.drawCircle(r, r, r - context.dp(4f), paint)
        }
    }
}

/** 复用一个 RectF 画圆角矩形（drawRoundRect 的 RectF 重载是 API 1 就有）。 */
private fun rectF(l: Float, t: Float, r: Float, b: Float): android.graphics.RectF =
    android.graphics.RectF(l, t, r, b)

/**
 * 分段选择（一排互斥选项）。
 *
 * 安卓 4.1 没有 Material 的 SegmentedButton，这里用一排 TextView 手写：
 * 选中的那个用主色底，其余透明。
 */
internal fun segmented(
    context: Context,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = context.roundedBackground(Palette.SegmentBackground, 10f)
        setPadding(context.dp(3f), context.dp(3f), context.dp(3f), context.dp(3f))
    }
    options.forEachIndexed { index, label ->
        val cell = context.textView(label, sizeSp = 13f, color = if (index == selected) Palette.Text else Palette.Muted).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(10f), context.dp(7f), context.dp(10f), context.dp(7f))
            if (index == selected) background = context.roundedBackground(Palette.On, 8f)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (index != selected) onSelect(index) }
        }
        row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    return row
}

/** 连接码方框：一位一个框。 */
internal fun pinBoxes(context: Context, pin: String, boxDp: Float, gapDp: Float): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    pin.forEachIndexed { index, ch ->
        row.addView(context.textView(ch.toString(), sizeSp = 22f, color = Palette.PinText, bold = true).apply {
            gravity = Gravity.CENTER
            background = context.roundedBackground(Palette.PinBox, 8f)
        }, LinearLayout.LayoutParams(context.dp(boxDp), context.dp(boxDp)).apply {
            if (index > 0) leftMargin = context.dp(gapDp)
        })
    }
    return row
}

/** 整页纵向滚动容器。 */
internal fun scrollPage(context: Context, content: View): ScrollView =
    ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(Palette.Background)
        addView(content)
    }
