package com.regepower.mincalsync.sync

import android.content.ContentResolver
import android.provider.CalendarContract.Calendars

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val writable: Boolean,
) {
    val label: String get() = "$displayName ($accountName)"
}

/** Lists the calendars the device already knows about (Exchange, Google, local, ...). */
class CalendarRepository(private val resolver: ContentResolver) {

    fun loadCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val result = mutableListOf<CalendarInfo>()
        resolver.query(Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result += CalendarInfo(
                    id = c.getLong(0),
                    displayName = c.getString(1).orEmpty(),
                    accountName = c.getString(2).orEmpty(),
                    writable = c.getInt(3) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        return result.sortedWith(compareBy({ it.accountName.lowercase() }, { it.displayName.lowercase() }))
    }
}
