package com.locket.app.ui.screens

import com.locket.app.model.Moment
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** Feed order is already newest-first. Calendar cells use the first moment of each day. */
internal class HistoryIndex private constructor(
    val moments: List<Moment>,
    private val months: Map<Int, Array<Moment?>>,
    val oldestMonth: YearMonth?
) {
    fun moment(year: Int, month: Int, day: Int): Moment? =
        months[monthKey(year, month)]?.getOrNull(day - 1)

    companion object {
        private fun monthKey(year: Int, month: Int) = year * 12 + month - 1

        fun build(feed: List<Moment>, owner: String, zone: ZoneId): HistoryIndex {
            if (owner.isBlank()) return HistoryIndex(emptyList(), emptyMap(), null)
            val moments = ArrayList<Moment>()
            val months = HashMap<Int, Array<Moment?>>()
            val calendar = GregorianCalendar(TimeZone.getTimeZone(zone.id))
            var oldest: YearMonth? = null
            var oldestKey = Int.MAX_VALUE
            for (moment in feed) {
                if (moment.userUid != owner) continue
                moments.add(moment)
                calendar.timeInMillis = moment.timestampSeconds * 1000
                val year = calendar.get(Calendar.YEAR)
                val month = calendar.get(Calendar.MONTH) + 1
                val dayOffset = calendar.get(Calendar.DAY_OF_MONTH) - 1
                val key = monthKey(year, month)
                val days = months.getOrPut(key) { arrayOfNulls(31) }
                if (days[dayOffset] == null) days[dayOffset] = moment
                if (key < oldestKey) {
                    oldestKey = key
                    oldest = YearMonth.of(year, month)
                }
            }
            return HistoryIndex(moments, months, oldest)
        }
    }
}

internal fun historyPhotos(history: List<Moment>, selected: Moment): List<Moment> {
    val seen = HashSet<String>()
    val photos = ArrayList<Moment>(history.size + 1)
    var sorted = true
    for (photo in history) {
        if (!seen.add(photo.id)) continue
        if (photos.lastOrNull()?.let { it.timestampSeconds < photo.timestampSeconds } == true) sorted = false
        photos.add(photo)
    }
    if (seen.add(selected.id)) {
        if (photos.lastOrNull()?.let { it.timestampSeconds < selected.timestampSeconds } == true) sorted = false
        photos.add(selected)
    }
    if (!sorted) photos.sortByDescending { it.timestampSeconds }
    return photos
}
