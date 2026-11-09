package cn.toside.music.nativeapp.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** An anchored, scrollable menu with an optional selection and disabled rows. */
internal class PlatformPopup(private val tagPrefix: String = "platform", private val outlined: Boolean = false) {
    private var window: PopupWindow? = null

    fun dismiss() { window?.dismiss() }

    @SuppressLint("RtlHardcoded") // Compare the resolved physical gravity when clamping to window edges.
    fun show(anchor: View, ids: List<String>, labels: List<String>, selected: String,
             palette: UiPalette, alignEnd: Boolean = false, disabled: Set<String> = emptySet(), onSelected: (String) -> Unit) {
        dismiss()
        if (!anchor.isAttachedToWindow || !anchor.isShown || ids.isEmpty()) return
        require(ids.size == labels.size)
        val context = anchor.context
        var owner = context
        while (owner is ContextWrapper && owner !is Activity && owner.baseContext !== owner) owner = owner.baseContext
        if (owner is Activity && (owner.isFinishing || owner.isDestroyed || owner.isChangingConfigurations)) return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun label() = TextView(context).apply {
            textSize = 14f; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            palette.textStyle(this)
        }
        val sample = label()
        val rowHeight = maxOf(dp(44), sample.paint.fontMetricsInt.let { it.bottom - it.top } + dp(20))
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val root = anchor.rootView
        val origin = IntArray(2).also(root::getLocationOnScreen)
        if (!frame.intersect(origin[0], origin[1], origin[0] + root.width, origin[1] + root.height)) return
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val below = (frame.bottom - location[1] - anchor.height).coerceAtLeast(0)
        val above = (location[1] - frame.top).coerceAtLeast(0)
        val availableHeight = maxOf(below, above)
        if (frame.isEmpty || availableHeight == 0) return
        val labelWidth = ceil(labels.maxOf { sample.paint.measureText(it) }).toInt()
        val width = maxOf(dp(88), labelWidth + dp(if (outlined) 40 else 28))
            .coerceAtMost(minOf(dp(224), frame.width()).coerceAtLeast(1))
        val popup = PopupWindow(context)
        val list = ListView(context).apply {
            tag = "$tagPrefix-menu"; divider = null; isVerticalScrollBarEnabled = false
            adapter = object : BaseAdapter() {
                override fun getCount() = ids.size
                override fun getItem(position: Int) = labels[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun areAllItemsEnabled() = disabled.isEmpty()
                override fun isEnabled(position: Int) = ids[position] !in disabled
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    ((convertView as? TextView) ?: label()).apply {
                        text = labels[position]; tag = "$tagPrefix-${ids[position]}"
                        isSelected = ids[position] == selected
                        isActivated = ids[position] == selected
                        this.isEnabled = ids[position] !in disabled
                        setTextColor(when { !this.isEnabled -> palette.faint; isActivated -> palette.primary; else -> palette.foreground })
                        layoutParams = android.widget.AbsListView.LayoutParams(-1, rowHeight)
                    }
            }
            setOnItemClickListener { _, _, position, _ ->
                if (ids[position] in disabled) return@setOnItemClickListener
                popup.dismiss()
                if (ids[position] != selected) onSelected(ids[position])
            }
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { popup.dismiss() }
        }
        popup.apply {
            contentView = list; this.width = width
            height = minOf(rowHeight * ids.size, availableHeight, dp(360))
            isFocusable = true; isOutsideTouchable = true; isClippingEnabled = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(2).toFloat()
                if (outlined) setStroke(dp(1), palette.divider)
            })
            elevation = dp(6).toFloat()
            setOnDismissListener {
                anchor.removeOnAttachStateChangeListener(detach)
                if (window === popup) window = null
            }
        }
        anchor.addOnAttachStateChangeListener(detach)
        window = popup
        val gravity = if (alignEnd) Gravity.END else Gravity.START
        val onRight = Gravity.getAbsoluteGravity(gravity, anchor.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT
        val left = if (onRight) location[0] + anchor.width - width else location[0]
        val offsetX = left.coerceIn(frame.left, frame.right - width) - left
        val offsetY = if (popup.height > below) -anchor.height - popup.height else 0
        try {
            popup.showAsDropDown(anchor, offsetX, offsetY, gravity)
        } catch (_: WindowManager.BadTokenException) {
            // A window can be revoked before its still-attached anchor receives the detach event.
            runCatching { popup.dismiss() }
            anchor.removeOnAttachStateChangeListener(detach)
            if (window === popup) window = null
            return
        }
        list.setSelection(ids.indexOf(selected).coerceAtLeast(0))
    }

    companion object { val sources = listOf("kw", "kg", "tx", "wy", "mg") }
}
