package app.shilex.android.tools

import app.shilex.android.control.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStoreMatcherTest {

    @Test
    fun identifiesFreeInstallButtonOnDetailListing() {
        val elements = listOf(
            ScreenElement(1, "text", "LastWave Music", ""),
            ScreenElement(2, "text", "Shilex Audio Labs", ""),
            ScreenElement(3, "button", "Install", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("LastWave", elements)
        assertEquals(PlayStoreMatcher.ButtonState.Install, decision.buttonState)
        assertNotNull(decision.actionElement)
        assertEquals(3, decision.actionElement?.number)
    }

    @Test
    fun stopsOnPaidAppPurchaseDialog() {
        val elements = listOf(
            ScreenElement(1, "text", "Nova Launcher Prime", ""),
            ScreenElement(2, "button", "₹149.00 Buy", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("Nova Launcher Prime", elements)
        assertEquals(PlayStoreMatcher.ButtonState.PaidOrBlocked, decision.buttonState)
        assertTrue(decision.reason.contains("payment") || decision.reason.contains("purchase"))
    }

    @Test
    fun stopsOnAccountAgeOrAuthVerificationDialog() {
        val elements = listOf(
            ScreenElement(1, "text", "Complete account setup", ""),
            ScreenElement(2, "text", "Add a payment method to continue", ""),
            ScreenElement(3, "button", "Continue payment", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("WhatsApp", elements)
        assertEquals(PlayStoreMatcher.ButtonState.PaidOrBlocked, decision.buttonState)
        assertTrue(decision.reason.contains("payment") || decision.reason.contains("account"))
    }

    @Test
    fun detectsAlreadyInstalledOpenOrUpdate() {
        val elements = listOf(
            ScreenElement(1, "text", "Telegram", ""),
            ScreenElement(2, "button", "Open", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("Telegram", elements)
        assertEquals(PlayStoreMatcher.ButtonState.Open, decision.buttonState)
        assertEquals(2, decision.actionElement?.number)
    }

    @Test
    fun detectsOngoingDownloadOrInstallation() {
        val elements = listOf(
            ScreenElement(1, "text", "Spotify", ""),
            ScreenElement(2, "button", "Cancel", ""),
            ScreenElement(3, "text", "Downloading 45%", ""),
        )
        val decision = PlayStoreMatcher.analyzeScreen("Spotify", elements)
        assertEquals(PlayStoreMatcher.ButtonState.InstallingOrPending, decision.buttonState)
    }

    @Test
    fun handlesFreeInstallVariantPlayOpenStateAndWaitingPending() {
        val freeInstall = listOf(
            ScreenElement(1, "text", "WhatsApp Messenger", ""),
            ScreenElement(2, "button", "Install (free)", ""),
        )
        val d1 = PlayStoreMatcher.analyzeScreen("WhatsApp", freeInstall)
        assertEquals(PlayStoreMatcher.ButtonState.Install, d1.buttonState)
        assertEquals(2, d1.actionElement?.number)

        val playInstalled = listOf(
            ScreenElement(1, "text", "Subway Surfers", ""),
            ScreenElement(2, "button", "Uninstall", ""),
            ScreenElement(3, "button", "Play", ""),
        )
        val d2 = PlayStoreMatcher.analyzeScreen("Subway Surfers", playInstalled)
        assertEquals(PlayStoreMatcher.ButtonState.Open, d2.buttonState)
        assertTrue(d2.isVerifiedInstalledUi)

        val waitingWifi = listOf(
            ScreenElement(1, "text", "Spotify", ""),
            ScreenElement(2, "text", "Waiting for Wi-Fi", ""),
        )
        val d3 = PlayStoreMatcher.analyzeScreen("Spotify", waitingWifi)
        assertEquals(PlayStoreMatcher.ButtonState.InstallingOrPending, d3.buttonState)
    }

    @Test
    fun locatesSearchBarAndFlagsAmbiguousListings() {
        val searchElements = listOf(
            ScreenElement(1, "button", "Search apps & games", ""),
            ScreenElement(2, "button", "LastWave", ""),
            ScreenElement(3, "button", "LastWave", ""),
        )
        val searchBtn = PlayStoreMatcher.findSearchInput(searchElements)
        assertNotNull(searchBtn)
        assertEquals(1, searchBtn?.number)

        val ambiguousDecision = PlayStoreMatcher.analyzeScreen("LastWave", searchElements)
        assertEquals(PlayStoreMatcher.ButtonState.None, ambiguousDecision.buttonState)
        assertEquals(2, ambiguousDecision.ambiguousListings.size)
        assertEquals(
            StoreFlowState.RESULTS_VISIBLE,
            PlayStoreMatcher.resolveFlowState(ambiguousDecision),
        )
    }

    @Test
    fun storeFlowStateTransitionsCoverInstallVerificationAndBlockedCases() {
        val installDecision = PlayStoreMatcher.analyzeScreen(
            "Signal",
            listOf(
                ScreenElement(1, "text", "Signal Private Messenger", ""),
                ScreenElement(2, "button", "Install", ""),
            ),
        )
        assertEquals(
            StoreFlowState.APP_PAGE_VISIBLE,
            PlayStoreMatcher.resolveFlowState(installDecision),
        )
        assertEquals(
            StoreFlowState.INSTALLING,
            PlayStoreMatcher.resolveFlowState(installDecision, confirmedInstall = true),
        )
        assertEquals(
            StoreFlowState.COMPLETED,
            PlayStoreMatcher.resolveFlowState(installDecision, verifiedInstalledAfterAction = true),
        )

        val blockedDecision = PlayStoreMatcher.analyzeScreen(
            "Nova Launcher Prime",
            listOf(ScreenElement(1, "button", "₹149.00 Buy", "")),
        )
        assertEquals(
            StoreFlowState.FAILED,
            PlayStoreMatcher.resolveFlowState(blockedDecision),
        )
        assertEquals(
            StoreFlowState.COMPLETED,
            PlayStoreMatcher.resolveFlowState(blockedDecision, alreadyInstalled = true),
        )
    }

    @Test
    fun detectsScreenStatesAndPreventsRepeatedSearchWhenResultsVisible() {
        val searchResultsElements = listOf(
            ScreenElement(1, "button", "Search apps & games", ""),
            ScreenElement(2, "text", "BookMyShow: Movie Tickets", ""),
            ScreenElement(3, "text", "Bigtree Entertainment", ""),
            ScreenElement(4, "button", "Install", ""),
        )
        assertEquals(
            PlayStoreMatcher.PlayStoreScreenState.SearchResults,
            PlayStoreMatcher.detectScreenState(searchResultsElements),
        )
        assertTrue(PlayStoreMatcher.isResultsVisible(searchResultsElements))

        val appDetailsElements = listOf(
            ScreenElement(1, "text", "BookMyShow: Movie Tickets", ""),
            ScreenElement(2, "text", "About this app", ""),
            ScreenElement(3, "button", "Install", ""),
        )
        assertEquals(
            PlayStoreMatcher.PlayStoreScreenState.AppDetails,
            PlayStoreMatcher.detectScreenState(appDetailsElements),
        )
        assertTrue(PlayStoreMatcher.isResultsVisible(appDetailsElements))

        // Match base title "BookMyShow" when subtitle is present
        val decision = PlayStoreMatcher.analyzeScreen("BookMyShow", searchResultsElements)
        assertEquals(PlayStoreMatcher.ButtonState.Install, decision.buttonState)
        assertNotNull(decision.actionElement)
    }
}
