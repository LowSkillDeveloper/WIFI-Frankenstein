package com.lsd.wififrankenstein.util

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lsd.wififrankenstein.R

object BottomSheetMenu {

    fun show(
        context: Context,
        title: String? = null,
        items: List<BottomSheetMenuItem>,
        onItemClick: (BottomSheetMenuItem) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(
            createRootView(context, title, items, onItemClick, dialog)
        )
        dialog.show()
    }

    private fun createRootView(
        context: Context,
        title: String?,
        items: List<BottomSheetMenuItem>,
        onItemClick: (BottomSheetMenuItem) -> Unit,
        dialog: BottomSheetDialog
    ): View {
        val primaryColor = resolveColor(context, android.R.attr.colorPrimary)
        val surfaceColor = resolveColor(context, com.google.android.material.R.attr.colorOnSurface)

        val outerLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(context, 12)
                bottomMargin = dp(context, 12)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.text_secondary))
            outerLayout.addView(this)
        }

        val cardView = CardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = dp(context, 8).toFloat()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(context, 16), 0, dp(context, 16), 0)
            layoutParams = lp
        }

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
            clipToPadding = false
        }

        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }

        if (title != null) {
            container.addView(createSectionTitle(context, title))
            container.addView(createDivider(context))
        }

        val visibleItems = items.filter { it.visible }
        visibleItems.forEachIndexed { index, item ->
            val itemView = createItemView(context, item, primaryColor, surfaceColor)
            itemView.isEnabled = item.enabled
            itemView.alpha = if (item.enabled) 1.0f else 0.38f
            itemView.setOnClickListener {
                if (item.enabled) {
                    onItemClick(item)
                    dialog.dismiss()
                }
            }
            container.addView(itemView)
            if (index < visibleItems.size - 1) {
                container.addView(createDivider(context))
            }
        }

        scrollView.addView(container)
        cardView.addView(scrollView)
        outerLayout.addView(cardView)

        return outerLayout
    }

    private fun createSectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4))
        }
    }

    private fun createItemView(
        context: Context,
        item: BottomSheetMenuItem,
        primaryColor: Int,
        surfaceColor: Int
    ): TextView {
        return TextView(context).apply {
            text = item.title
            setTextColor(surfaceColor)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            val typedValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                typedValue,
                true
            )
            setBackgroundResource(typedValue.resourceId)
            isClickable = true
            isFocusable = true
            minHeight = dp(context, 48)

            if (item.iconResId != null) {
                val drawable = AppCompatResources.getDrawable(context, item.iconResId)?.mutate()
                drawable?.let {
                    DrawableCompat.setTint(it, primaryColor)
                    setCompoundDrawablesWithIntrinsicBounds(it, null, null, null)
                }
                compoundDrawablePadding = dp(context, 12)
            }
        }
    }

    private fun createDivider(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply {
                setMargins(dp(context, 16), 0, dp(context, 16), 0)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider_color))
        }
    }

    private fun resolveColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) {
            ContextCompat.getColor(context, tv.resourceId)
        } else {
            tv.data
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
