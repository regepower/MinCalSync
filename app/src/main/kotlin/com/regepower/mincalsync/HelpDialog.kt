package com.regepower.mincalsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val helpSections = listOf(
    "Was macht die App?" to
        "Sie kopiert Termine aus einem Quellkalender (z. B. Exchange) in einen Zielkalender " +
        "(z. B. Google). Nur in eine Richtung: Änderungen im Ziel werden nicht zurückgeschrieben.",
    "Einrichtung" to
        "1. Exchange- und Google-Konto müssen in Android eingerichtet sein und ihre Kalender " +
        "synchronisieren.\n" +
        "2. Tipp: In Google Kalender einen eigenen, leeren Kalender anlegen (z. B. \"Arbeit\") " +
        "und diesen als Ziel wählen. So bleiben Kopien und eigene Termine getrennt.\n" +
        "3. Quelle und Ziel wählen, Intervall eintragen, \"Speichern & synchronisieren\".",
    "Google Home" to
        "Damit Google Home den Zielkalender kennt: Google-Home-App → Profil → " +
        "Home-Einstellungen → Google Assistant → Kalender, dort den Kalender aktivieren. " +
        "\"Persönliche Ergebnisse\" müssen eingeschaltet sein.",
    "Was wird synchronisiert?" to
        "Termine von 30 Tagen zurück bis 365 Tage voraus. Serientermine werden als einzelne " +
        "Termine kopiert. Ältere Kopien bleiben erhalten, werden aber nicht mehr aktualisiert.",
    "Sicherheit" to
        "Die App ändert oder löscht nur Termine, die sie selbst angelegt hat. Eigene Termine " +
        "im Zielkalender werden nie angefasst. Löschst du eine Kopie von Hand, legt der " +
        "nächste Sync sie wieder an, solange sie in der Quelle existiert.",
    "Sync läuft nicht?" to
        "Xiaomi/HyperOS beendet Hintergrund-Apps gern. Einstellungen → Apps → MinCalSync:\n" +
        "• Autostart erlauben\n" +
        "• Akku-Sparmodus: \"Keine Einschränkungen\"\n" +
        "Den Stand zeigt \"Letzter Sync\" auf der Startseite.",
)

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Verstanden") }
        },
        title = { Text("Hilfe") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                helpSections.forEach { (heading, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(heading, style = MaterialTheme.typography.titleSmall)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}
