package com.regepower.mincalsync.sync

import android.content.Context
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** Shared by the background worker and the manual preview, so both use the same window. */
object SyncRunner {

    private const val PAST_WINDOW_DAYS = 30L
    private const val FUTURE_WINDOW_DAYS = 365L

    /** Throws [IllegalArgumentException] if source/target are not chosen. */
    fun execute(context: Context, settings: SyncSettings, dryRun: Boolean): CalendarMirror.Stats {
        val sourceId = settings.sourceCalendarId
        val targetId = settings.targetCalendarId
        require(sourceId != null && targetId != null && sourceId != targetId) {
            "Quell- und Zielkalender wählen"
        }
        val now = System.currentTimeMillis()
        return CalendarMirror(context.contentResolver, MirrorStore(context, targetId)).run(
            sourceId,
            targetId,
            now - TimeUnit.DAYS.toMillis(PAST_WINDOW_DAYS),
            now + TimeUnit.DAYS.toMillis(FUTURE_WINDOW_DAYS),
            dryRun,
        )
    }

    fun describe(stats: CalendarMirror.Stats, dryRun: Boolean): String {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        val prefix = if (dryRun) "Vorschau (nichts geändert) $time: " else "$time: "
        return prefix + "${stats.created} neu, ${stats.updated} geändert, ${stats.deleted} gelöscht, " +
            "${stats.unchanged} unverändert" +
            (if (stats.adopted > 0) ", ${stats.adopted} übernommen" else "") +
            (if (stats.failed > 0) ", ${stats.failed} Fehler" else "")
    }
}
