package com.regepower.mincalsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.*
import com.regepower.mincalsync.ui.theme.MinCalSyncTheme
import com.regepower.mincalsync.worker.CalendarSyncWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Timber.d("All permissions granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        requestPermissions()

        setContent {
            MinCalSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.SCHEDULE_EXACT_ALARM)
            }
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun MainScreen() {
    var selectedSourceCalendar by remember { mutableStateOf<String?>(null) }
    var selectedTargetCalendar by remember { mutableStateOf<String?>(null) }
    var syncInterval by remember { mutableStateOf("24") }
    var syncStatus by remember { mutableStateOf("Idle") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "MinCalSync",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Source Calendar", style = MaterialTheme.typography.titleMedium)
                CalendarDropdown(
                    label = selectedSourceCalendar ?: "Select source...",
                    onSelect = { selectedSourceCalendar = it }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Target Calendar", style = MaterialTheme.typography.titleMedium)
                CalendarDropdown(
                    label = selectedTargetCalendar ?: "Select target...",
                    onSelect = { selectedTargetCalendar = it }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sync Interval (hours)", style = MaterialTheme.typography.titleMedium)
                TextField(
                    value = syncInterval,
                    onValueChange = { syncInterval = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hours") },
                    singleLine = true
                )
            }
        }

        Button(
            onClick = {
                if (selectedSourceCalendar != null && selectedTargetCalendar != null) {
                    scheduleSyncWorker(syncInterval.toLongOrNull() ?: 24)
                    syncStatus = "Sync scheduled!"
                } else {
                    syncStatus = "Please select both calendars"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = selectedSourceCalendar != null && selectedTargetCalendar != null
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Start Sync")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                syncStatus,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun CalendarDropdown(label: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val calendars = listOf("Calendar 1", "Calendar 2", "Calendar 3")

    Box {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            calendars.forEach { calendar ->
                DropdownMenuItem(
                    text = { Text(calendar) },
                    onClick = {
                        onSelect(calendar)
                        expanded = false
                    }
                )
            }
        }
    }
}

fun MainActivity.scheduleSyncWorker(intervalHours: Long) {
    val syncRequest = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
        intervalHours,
        TimeUnit.HOURS
    ).build()

    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        "calendar_sync",
        ExistingPeriodicWorkPolicy.KEEP,
        syncRequest
    )

    Timber.d("Sync worker scheduled for every $intervalHours hours")
}
