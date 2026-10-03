package com.regepower.mincalsync.sync

import android.content.ContentResolver
import android.provider.CalendarContract.Calendars

/**
 * Device-independent identity of a calendar: the row ID changes when an account is
 * re-added or on another phone, account type + account name + calendar name do not.
 */
data class CalendarRef(val accountType: String, val accountName: String, val name: String) {
    fun encode(): String = listOf(accountType, accountName, name).joinToString(SEP)

    companion object {
        private const val SEP = "\u001F"

        fun decode(raw: String?): CalendarRef? {
            val parts = raw?.split(SEP) ?: return null
            return if (parts.size == 3) CalendarRef(parts[0], parts[1], parts[2]) else null
        }
    }
}

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val writable: Boolean,
) {
    val label: String get() = "$displayName ($accountName)"
    val ref: CalendarRef get() = CalendarRef(accountType, accountName, displayName)
}

/** Lists the calendars the device already knows about (Exchange, Google, local, ...). */
class CalendarRepository(private val resolver: ContentResolver) {

    fun loadCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val result = mutableListOf<CalendarInfo>()
        resolver.query(Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result += CalendarInfo(
                    id = c.getLong(0),
                    displayName = c.getString(1).orEmpty(),
                    accountName = c.getString(2).orEmpty(),
                    accountType = c.getString(3).orEmpty(),
                    writable = c.getInt(4) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        return result.sortedWith(compareBy({ it.accountName.lowercase() }, { it.displayName.lowercase() }))
    }

    companion object {
        /**
         * Finds the calendar for [ref]. [cachedId] only breaks ties between calendars with the
         * same identity, or serves settings saved before refs existed. Null: missing or ambiguous.
         */
        fun resolve(calendars: List<CalendarInfo>, ref: CalendarRef?, cachedId: Long?): CalendarInfo? {
            if (ref == null) return calendars.firstOrNull { it.id == cachedId }
            val matches = calendars.filter { it.ref == ref }
            return when (matches.size) {
                0 -> null
                1 -> matches[0]
                else -> matches.firstOrNull { it.id == cachedId }
            }
        }
    }
}
