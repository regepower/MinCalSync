package com.regepower.mincalsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.regepower.mincalsync.sync.CalendarInfo
import com.regepower.mincalsync.sync.CalendarRepository
import com.regepower.mincalsync.sync.SyncScheduler
import com.regepower.mincalsync.sync.SyncSettings
import com.regepower.mincalsync.ui.theme.MinCalSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

private var timberPlanted = false

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && !timberPlanted) {
            Timber.plant(Timber.DebugTree())
            timberPlanted = true
        }
        setContent {
            MinCalSyncTheme {
                MainScreen()
            }
        }
    }
}

private val calendarPermissions = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

private fun hasCalendarPermission(context: Context) = calendarPermissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val settings = remember { SyncSettings(context) }

    var permissionGranted by remember { mutableStateOf(hasCalendarPermission(context)) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var lastResult by remember { mutableStateOf(settings.lastResult) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionGranted = result.values.all { it }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            calendars = withContext(Dispatchers.IO) {
                CalendarRepository(context.contentResolver).loadCalendars()
            }
        } else {
            permissionLauncher.launch(calendarPermissions)
        }
    }

    // The worker reports into SharedPreferences; poll so the status stays current.
    LaunchedEffect(Unit) {
        while (true) {
            lastResult = settings.lastResult
            delay(2_000)
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("MinCalSync", style = MaterialTheme.typography.headlineMedium)

            if (permissionGranted) {
                SyncControls(calendars = calendars, settings = settings, lastResult = lastResult)
            } else {
                Text("Ohne Kalenderberechtigung kann nichts synchronisiert werden.")
                Button(onClick = { permissionLauncher.launch(calendarPermissions) }) {
                    Text("Berechtigung erteilen")
                }
            }
        }
    }
}

@Composable
private fun SyncControls(
    calendars: List<CalendarInfo>,
    settings: SyncSettings,
    lastResult: String?,
) {
    val context = LocalContext.current
    var sourceId by remember { mutableStateOf(settings.sourceCalendarId) }
    var targetId by remember { mutableStateOf(settings.targetCalendarId) }
    var intervalText by remember { mutableStateOf(settings.intervalHours.toString()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CalendarPicker(
            title = "Quellkalender (z. B. Exchange)",
            calendars = calendars,
            selectedId = sourceId,
            onSelect = { id ->
                sourceId = id
                settings.sourceCalendarId = id
                if (targetId == id) {
                    targetId = null
                    settings.targetCalendarId = null
                }
            },
        )

        CalendarPicker(
            title = "Zielkalender (z. B. Google)",
            calendars = calendars.filter { it.writable && it.id != sourceId },
            selectedId = targetId,
            onSelect = { id ->
                targetId = id
                settings.targetCalendarId = id
            },
        )

        OutlinedTextField(
            value = intervalText,
            onValueChange = { text -> intervalText = text.filter { it.isDigit() }.take(3) },
            label = { Text("Intervall in Stunden") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val ready = sourceId != null && targetId != null && sourceId != targetId

        Button(
            onClick = {
                val hours = intervalText.toLongOrNull()?.coerceIn(1L, 168L)
                    ?: SyncSettings.DEFAULT_INTERVAL_HOURS
                intervalText = hours.toString()
                settings.intervalHours = hours
                SyncScheduler.schedulePeriodic(context, hours)
                SyncScheduler.runNow(context)
                message = "Automatischer Sync alle $hours h aktiv, erster Lauf gestartet."
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Speichern & synchronisieren")
        }

        OutlinedButton(
            onClick = {
                SyncScheduler.cancel(context)
                message = "Automatischer Sync gestoppt. Bereits kopierte Termine bleiben."
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Automatischen Sync stoppen")
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Letzter Sync", style = MaterialTheme.typography.titleSmall)
                Text(lastResult ?: "Noch kein Lauf", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CalendarPicker(
    title: String,
    calendars: List<CalendarInfo>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = calendars.firstOrNull { it.id == selectedId }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.label ?: "Auswählen …")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (calendars.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Keine passenden Kalender") },
                        onClick = { expanded = false },
                    )
                }
                calendars.forEach { calendar ->
                    DropdownMenuItem(
                        text = { Text(calendar.label) },
                        onClick = {
                            onSelect(calendar.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
