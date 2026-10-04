package com.regepower.mincalsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.regepower.mincalsync.sync.CalendarInfo
import com.regepower.mincalsync.sync.CalendarRepository
import com.regepower.mincalsync.sync.SyncRunner
import com.regepower.mincalsync.sync.SyncScheduler
import com.regepower.mincalsync.sync.SyncSettings

class MainActivity : Activity() {

    private lateinit var settings: SyncSettings
    private lateinit var permissionBox: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var sourceBtn: Button
    private lateinit var targetBtn: Button
    private lateinit var autoSwitch: Switch
    private lateinit var intervalField: EditText
    private lateinit var syncNowBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var statusText: TextView

    private var calendars: List<CalendarInfo> = emptyList()
    private var syncRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val dp get() = resources.displayMetrics.density
    private fun px(value: Int) = (value * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SyncSettings(this)
        setContentView(buildLayout())
        // First start: explain the app before anything is configured.
        if (savedInstanceState == null && !settings.isSourceChosen) showHelp()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (hasCalendarPermission()) {
            permissionBox.visibility = View.GONE
            content.visibility = View.VISIBLE
            loadCalendars()
        } else {
            permissionBox.visibility = View.VISIBLE
            content.visibility = View.GONE
        }
        autoSwitch.isChecked = settings.autoSync
        // Force-stop (common on Xiaomi) drops scheduled jobs; put the job back.
        if (settings.autoSync && !SyncScheduler.isScheduled(this)) {
            SyncScheduler.schedulePeriodic(this, settings.intervalHours)
        }
        refreshBattery()
        handler.removeCallbacks(statusPoller)
        handler.post(statusPoller)
    }

    override fun onPause() {
        handler.removeCallbacks(statusPoller)
        applyInterval()
        super.onPause()
    }

    // Plain Activity API: AndroidX ActivityResult would cost far more than it saves.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        AppShell.onResult(this, requestCode, resultCode, data, settings.prefs, SyncSettings.DEVICE_KEYS::contains) {
            if (settings.autoSync) SyncScheduler.schedulePeriodic(this, settings.intervalHours) else SyncScheduler.cancel(this)
            recreate()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR) refresh()
    }

    // ---- layout -------------------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(16))
        }

        root.addView(AppShell.header(this))

        permissionBox = card().apply {
            addView(TextView(context).apply { text = getString(R.string.perm_missing) })
            addView(
                button(R.string.btn_allow_calendar) {
                    requestPermissions(CALENDAR_PERMISSIONS, REQUEST_CALENDAR)
                }.also { style(it, R.color.md_error_container, R.color.md_on_error_container) },
                fullWidth(top = 8),
            )
        }
        root.addView(permissionBox, fullWidth(top = 8))

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(calendarCard(), fullWidth(top = 8))
        content.addView(autoCard(), fullWidth(top = 12))
        content.addView(statusCard(), fullWidth(top = 12))
        root.addView(content)

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun calendarCard() = card().apply {
        addView(header(R.string.section_calendars))
        addView(label(R.string.label_source))
        sourceBtn = button(R.string.pick_calendar) { pickSource() }
        addView(sourceBtn, fullWidth(top = 4))
        addView(label(R.string.label_target), fullWidth(top = 8))
        targetBtn = button(R.string.pick_calendar) { pickTarget() }
        targetBtn.tooltipText = getString(R.string.help_target)
        addView(targetBtn, fullWidth(top = 4))
    }

    private fun autoCard() = card().apply {
        addView(header(R.string.section_auto))
        autoSwitch = Switch(context).apply {
            text = getString(R.string.auto_switch)
            setOnCheckedChangeListener { _, checked -> onAutoToggled(checked) }
        }
        addView(autoSwitch, fullWidth(top = 4))

        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val help = getString(R.string.interval_help)
        row.addView(TextView(context).apply { text = getString(R.string.interval_label); tooltipText = help })
        intervalField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            setText(settings.intervalHours.toString())
            gravity = Gravity.END
            minEms = 3
            tooltipText = help
            contentDescription = help
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyInterval() }
        }
        row.addView(intervalField, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
        row.addView(TextView(context).apply { text = getString(R.string.interval_unit) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(4) })
        addView(row, fullWidth(top = 4))

        val buttons = LinearLayout(context)
        syncNowBtn = button(R.string.btn_sync_now) { syncNow() }
            .also { style(it, R.color.md_primary, R.color.md_on_primary) }
        batteryBtn = button(R.string.btn_battery) { requestBatteryExemption() }
        batteryBtn.tooltipText = getString(R.string.help_battery)
        buttons.addView(syncNowBtn, weighted())
        buttons.addView(batteryBtn, weighted())
        addView(buttons, fullWidth(top = 8))
    }

    private fun statusCard() = card().apply {
        addView(header(R.string.section_status))
        statusText = TextView(context)
        addView(statusText, fullWidth(top = 4))
    }

    // ---- actions ------------------------------------------------------------------------

    private fun loadCalendars() {
        Thread {
            val loaded = try {
                CalendarRepository(contentResolver).loadCalendars()
            } catch (e: SecurityException) {
                emptyList()
            }
            runOnUiThread {
                calendars = loaded
                settings.refreshIds(loaded)
                refreshCalendarButtons()
            }
        }.start()
    }

    private fun refreshCalendarButtons() {
        showChoice(sourceBtn, settings.source(calendars))
        showChoice(targetBtn, settings.target(calendars))
        val ready = isConfigured()
        syncNowBtn.isEnabled = ready && !syncRunning
        autoSwitch.isEnabled = ready
    }

    /** Tonal when chosen, error container while still missing. */
    private fun showChoice(button: Button, calendar: CalendarInfo?) {
        if (calendar != null) {
            button.text = CalendarPicker.label(this, calendar)
            style(button, R.color.md_container, R.color.md_on_container)
        } else {
            button.text = getString(R.string.pick_calendar)
            style(button, R.color.md_error_container, R.color.md_on_error_container)
        }
    }

    private fun pickSource() = pickCalendar(R.string.pick_source_title, calendars, settings.source(calendars)) { cal ->
        settings.setSource(cal)
        if (settings.target(calendars)?.id == cal.id) settings.setTarget(null)
        onCalendarsChanged()
    }

    private fun pickTarget() = pickCalendar(
        R.string.pick_target_title,
        calendars.filter { it.writable && it.id != settings.source(calendars)?.id },
        settings.target(calendars),
    ) { cal ->
        settings.setTarget(cal)
        onCalendarsChanged()
    }

    private fun pickCalendar(
        title: Int,
        options: List<CalendarInfo>,
        selected: CalendarInfo?,
        onPick: (CalendarInfo) -> Unit,
    ) = CalendarPicker.show(this, title, options, selected, onPick)

    private fun onCalendarsChanged() {
        refreshCalendarButtons()
        if (!isConfigured() && autoSwitch.isChecked) autoSwitch.isChecked = false
    }

    private fun onAutoToggled(enabled: Boolean) {
        settings.autoSync = enabled
        if (enabled) {
            applyInterval()
            SyncScheduler.schedulePeriodic(this, settings.intervalHours)
        } else {
            SyncScheduler.cancel(this)
        }
    }

    /** Reads the interval field, clamps it and reschedules when automatic sync is on. */
    private fun applyInterval() {
        val hours = intervalField.text.toString().toLongOrNull()?.coerceIn(1L, 168L)
            ?: SyncSettings.DEFAULT_INTERVAL_HOURS
        if (intervalField.text.toString() != hours.toString()) intervalField.setText(hours.toString())
        if (hours != settings.intervalHours) {
            settings.intervalHours = hours
            if (settings.autoSync) SyncScheduler.schedulePeriodic(this, hours)
        }
    }

    private fun syncNow() {
        if (syncRunning) return
        syncRunning = true
        syncNowBtn.isEnabled = false
        statusText.text = getString(R.string.status_running)
        Thread {
            SyncRunner.run(applicationContext)
            runOnUiThread {
                syncRunning = false
                refreshCalendarButtons()
                refreshStatus()
            }
        }.start()
    }

    private fun refreshStatus() {
        if (syncRunning) return
        statusText.text = settings.lastResult ?: getString(R.string.status_never)
    }

    private fun refreshBattery() {
        val exempt = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        val label = getString(R.string.btn_battery)
        if (exempt) {
            batteryBtn.text = "$label ✓"
            style(batteryBtn, R.color.md_container, R.color.md_on_container)
        } else {
            batteryBtn.text = label
            style(batteryBtn, R.color.md_error_container, R.color.md_on_error_container)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showHelp() = AppShell.showHelp(this)

    private fun isConfigured(): Boolean {
        val source = settings.source(calendars)
        val target = settings.target(calendars)
        return source != null && target != null && source.id != target.id
    }

    private fun hasCalendarPermission() = CALENDAR_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- widgets ------------------------------------------------------------------------

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        clipToOutline = true
        setPadding(px(16), px(12), px(16), px(16))
    }

    private fun header(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 13f
        setTextColor(getColor(R.color.md_primary))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun label(text: Int) = TextView(this).apply { setText(text) }

    private fun button(text: Int, onClick: () -> Unit) = Button(this).apply {
        setText(text)
        setOnClickListener { onClick() }
        style(this, R.color.md_container, R.color.md_on_container)
    }

    private fun style(b: Button, fill: Int, text: Int) {
        b.setBackgroundResource(R.drawable.bg_btn)
        b.backgroundTintList = ColorStateList.valueOf(getColor(fill))
        b.setTextColor(getColor(text))
        b.isAllCaps = false
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = px(44)
        b.setPadding(px(16), px(8), px(16), px(8))
    }

    private fun fullWidth(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(top) }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val m = px(3)
        setMargins(m, 0, m, 0)
    }

    companion object {
        private const val REQUEST_CALENDAR = 1
        private const val STATUS_POLL_MS = 2_000L
        private val CALENDAR_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }
}
