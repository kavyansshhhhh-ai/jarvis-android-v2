package app.shilex.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketAppMatcherTest {
    @Test
    fun exactTicketAppLabelWinsOverRelatedLauncherLabels() {
        val installed = listOf(
            InstalledApp("BookMyShow", "com.bookmyshow"),
            InstalledApp("BookMyShow Clone", "com.example.clone"),
        )

        val result = TicketAppMatcher.resolve(listOf("BookMyShow", "District"), installed)

        assertEquals(InstalledApp("BookMyShow", "com.bookmyshow"), (result as TicketAppMatcher.Resolution.Matched).app)
    }

    @Test
    fun duplicateExactTicketAppsAskInsteadOfChoosingOrSkippingToAnotherProvider() {
        val installed = listOf(
            InstalledApp("BookMyShow", "com.bookmyshow"),
            InstalledApp("BookMyShow", "com.bookmyshow.work"),
            InstalledApp("District", "com.district"),
        )

        val result = TicketAppMatcher.resolve(listOf("BookMyShow", "District"), installed)

        assertTrue(result is TicketAppMatcher.Resolution.Ambiguous)
        assertEquals(
            setOf("com.bookmyshow", "com.bookmyshow.work"),
            (result as TicketAppMatcher.Resolution.Ambiguous).apps.map { it.packageName }.toSet(),
        )
    }

    @Test
    fun uniquePreferredProviderCanBeSelectedWhenEarlierProviderIsAbsent() {
        val result = TicketAppMatcher.resolve(
            listOf("BookMyShow", "District"),
            listOf(InstalledApp("District", "com.district")),
        )

        assertEquals(InstalledApp("District", "com.district"), (result as TicketAppMatcher.Resolution.Matched).app)
    }
}
