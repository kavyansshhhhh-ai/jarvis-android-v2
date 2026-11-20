package app.shilex.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceModeTest {
    @Test
    fun restoresPersistedModesWithoutAcceptingUnknownValues() {
        assertEquals(AppearanceMode.Dark, AppearanceMode.from("Dark"))
        assertEquals(AppearanceMode.Light, AppearanceMode.from("LIGHT"))
        assertEquals(AppearanceMode.System, AppearanceMode.from("system"))
        assertEquals(AppearanceMode.Dark, AppearanceMode.from(null))
        assertEquals(AppearanceMode.Dark, AppearanceMode.from("unknown"))
    }
}
