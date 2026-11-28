package com.locket.app.ui.screens

import com.locket.app.model.Moment
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryIndexTest {
    @Test
    fun indexesLocalLeapDayAndDstBoundariesInFeedOrder() {
        val zone = ZoneId.of("America/New_York")
        val feed = listOf(
            moment("afterDst", "2024-03-10T07:01:00Z"),
            moment("beforeDst", "2024-03-10T06:59:00Z"),
            moment("march", "2024-03-01T05:00:00Z"),
            moment("leap", "2024-03-01T04:59:59Z"),
            moment("newYear", "2024-01-01T04:59:59Z"),
        )
        val index = HistoryIndex.build(feed, "owner", zone)
        assertEquals("afterDst", index.moment(2024, 3, 10)?.id)
        assertEquals("march", index.moment(2024, 3, 1)?.id)
        assertEquals("leap", index.moment(2024, 2, 29)?.id)
        assertEquals("newYear", index.moment(2023, 12, 31)?.id)
        assertEquals(YearMonth.of(2023, 12), index.oldestMonth)
    }

    @Test
    fun indexesLargeHistoriesAndKeepsTheFirstDuplicateInDetails() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val now = Instant.parse("2026-01-31T18:00:00Z").epochSecond
        for (size in listOf(1000, 10000)) {
            val feed = List(size) { i -> Moment("id-$i", "owner", "", thumbnailUrl = "url",
                timestampSeconds = now - i * 3600L) }
            val index = HistoryIndex.build(feed, "owner", zone)
            assertEquals(feed, index.moments)
            assertEquals(feed.first(), index.moment(2026, 2, 1))
            assertEquals(feed, historyPhotos(feed, feed.first().copy(caption = "different")))
        }
    }

    private fun moment(id: String, time: String) =
        Moment(id, "owner", "", thumbnailUrl = "url", timestampSeconds = Instant.parse(time).epochSecond)
}
