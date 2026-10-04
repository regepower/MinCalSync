package com.regepower.mincalsync

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.regepower.mincalsync.sync.CalendarInfo

/**
 * Calendar picker in the ZenDay style: grouped by account, colour dot per calendar,
 * readable names for Xiaomi's resource-key names, height-capped scroller (the built-in
 * choice list did not scroll on HyperOS). Single choice: tapping a row picks and closes.
 */
object CalendarPicker {
    private const val DIALOG_HEIGHT = 0.6f
    private const val XIAOMI_NAME_PREFIX = "calendar_displayname_"
    private const val XIAOMI_LOCAL_ACCOUNT = "account_name_local"
    private const val OPAQUE = 0xFF000000.toInt()

    fun show(
        activity: Activity,
        title: Int,
        options: List<CalendarInfo>,
        selected: CalendarInfo?,
        onPick: (CalendarInfo) -> Unit,
    ) {
        if (options.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(R.string.no_calendars)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.px(16), activity.px(4), activity.px(16), activity.px(8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(cappedScroller(activity, list))
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        var account: String? = null
        // Grouped by account: the repository already sorts by account, then name.
        for (calendar in options) {
            val pretty = prettyAccount(activity, calendar.accountName)
            if (pretty != account) {
                account = pretty
                list.addView(
                    accountHeader(activity, pretty),
                    fullWidth(activity, top = if (list.childCount == 0) 4 else 12),
                )
            }
            list.addView(
                row(activity, calendar, calendar.id == selected?.id) {
                    dialog.dismiss()
                    onPick(calendar)
                },
                fullWidth(activity),
            )
        }
        dialog.show()
    }

    /** Button text: readable calendar name and account. */
    fun label(activity: Activity, calendar: CalendarInfo): String {
        val name = prettyName(activity, calendar.displayName)
        val account = prettyAccount(activity, calendar.accountName)
        return if (account.isBlank() || account == name) name else "$name ($account)"
    }

    /** Row: radio button, colour dot, single-line name. Tapping anywhere picks it. */
    private fun row(activity: Activity, calendar: CalendarInfo, checked: Boolean, onClick: () -> Unit): View {
        val name = prettyName(activity, calendar.displayName)
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.px(48)
            background = rippleBackground(activity)
            isClickable = true
            contentDescription = name
            setOnClickListener { onClick() }
            addView(
                RadioButton(activity).apply {
                    buttonTintList = ColorStateList.valueOf(activity.getColor(R.color.md_primary))
                    isChecked = checked
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.dot)
                    setColorFilter(calendar.color or OPAQUE)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(activity.px(12), activity.px(12)).apply { marginStart = activity.px(8) },
            )
            addView(
                TextView(activity).apply {
                    text = name
                    textSize = 16f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = activity.px(12)
                },
            )
        }
    }

    private fun accountHeader(activity: Activity, text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(activity.getColor(R.color.md_primary))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(activity.px(4), 0, 0, activity.px(2))
    }

    /** ScrollView capped to a share of the screen height, scrollbar always visible. */
    private fun cappedScroller(activity: Activity, content: View): ScrollView {
        val maxHeight = (activity.resources.displayMetrics.heightPixels * DIALOG_HEIGHT).toInt()
        return object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(content)
        }
    }

    /** Some vendors (e.g. Xiaomi) store resource keys like "calendar_displayname_birthday" as names. */
    private fun prettyName(activity: Activity, raw: String): String {
        val key = raw.removePrefix(XIAOMI_NAME_PREFIX)
        if (key == raw) return raw
        return when (key) {
            "birthday" -> activity.getString(R.string.cal_birthdays)
            "xiaomi" -> "Xiaomi"
            else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun prettyAccount(activity: Activity, raw: String): String = when {
        raw == XIAOMI_LOCAL_ACCOUNT || raw.isBlank() -> activity.getString(R.string.cal_local)
        raw.all { it.isDigit() } -> activity.getString(R.string.cal_xiaomi_account, raw)
        else -> raw
    }

    private fun rippleBackground(activity: Activity) =
        activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { attrs ->
            attrs.getDrawable(0).also { attrs.recycle() }
        }

    private fun fullWidth(activity: Activity, top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.px(top) }

    private fun Activity.px(value: Int) = (value * resources.displayMetrics.density).toInt()
}
