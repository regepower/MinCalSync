package com.regepower.mincalsync.sync

/**
 * Invisible ownership marker appended to the description of every copy.
 *
 * ExtendedProperties would be the clean place, but only sync adapters may write them and
 * Google's sync would not upload them, so the marker lives in the description, which Google
 * does sync. It consists only of zero-width characters (invisible, not read out by TTS) and
 * encodes a 16-bit tag of the source calendar, so copies made from another source into the
 * same target are never mistaken for ours.
 */
object CopyMarker {
    private const val FRAME = '⁣' // INVISIBLE SEPARATOR
    private const val ZERO = '​' // ZERO WIDTH SPACE
    private const val ONE = '‌' // ZERO WIDTH NON-JOINER
    private const val BITS = 16
    private val pattern = Regex("$FRAME([$ZERO$ONE]{$BITS})$FRAME")

    fun tagFor(source: CalendarRef): Int = source.encode().hashCode() and 0xFFFF

    fun encode(tag: Int): String = buildString {
        append(FRAME)
        for (bit in BITS - 1 downTo 0) append(if ((tag shr bit) and 1 == 1) ONE else ZERO)
        append(FRAME)
    }

    /** Tag of the last marker in [text], or null if there is none. */
    fun tagOf(text: String): Int? {
        val bits = pattern.findAll(text).lastOrNull()?.groupValues?.get(1) ?: return null
        return bits.fold(0) { acc, c -> (acc shl 1) or (if (c == ONE) 1 else 0) }
    }

    /** [text] without any markers (also foreign ones, e.g. when the source is itself a copy). */
    fun strip(text: String): String = pattern.replace(text, "")
}
