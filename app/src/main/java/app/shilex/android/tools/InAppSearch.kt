package app.shilex.android.tools

import android.view.accessibility.AccessibilityNodeInfo
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import kotlinx.coroutines.delay

/**
 * Searching inside the app in front, done by the phone rather than step by step by the
 * model: tap the app's Search, type the words, open the result whose name matches. Only
 * safe taps (a search box and a result's name); anything risky is left to the model's
 * Allow-checked tools. Returns what happened, for the model.
 */
object InAppSearch {
    suspend fun open(control: ControlService, words: String): String {
        val wanted = words.trim().lowercase()
        if (wanted.isEmpty()) return ""
        val field = control.findFirst { node ->
            node.isEditable && !node.isPassword && isSearchField(control.labelOf(node), node.viewIdResourceName.orEmpty())
        } ?: run {
            val search = control.findFirst { !it.isEditable && control.labelOf(it).trim().lowercase().startsWith("search") }
                ?: return "Couldn't find the app's search; use ui_read and search for \"$words\" yourself."
            if (!control.click(search)) {
                return "Couldn't open the app's search safely; use ui_read and search for \"$words\" yourself."
            }
            delay(SETTLE_MS)
            control.focusedField() ?: control.findFirst { it.isEditable && !it.isPassword }
        } ?: return "The search box didn't open; use ui_read and search for \"$words\" yourself."
        val fieldLabel = control.labelOf(field)
        if (field.isPassword || ControlRules.isCredentialAction(fieldLabel, field.viewIdResourceName.orEmpty(), field.isPassword)) {
            control.releaseNode(field)
            return "The focused field is a protected credential field; JARVIS didn't enter the search text."
        }
        if (!control.type(field, words)) return "Typing in the search box didn't work; try ui_type."
        delay(RESULTS_MS)
        val hit = best(control, wanted) ?: return "Searched for \"$words\" but no result has that name; read the results to the user."
        val hitLabel = control.labelOf(hit)
        if (ControlRules.clickRisk(hitLabel, hit.viewIdResourceName.orEmpty()) != ControlRules.Risk.Safe) {
            control.releaseNode(hit)
            return "Searched for \"$words\"; open the right result with ui_click."
        }
        control.click(hit)
        delay(SETTLE_MS)
        return "Searched for \"$words\" and opened \"${hitLabel.take(60)}\"."
    }

    /** The result named most like [wanted]: exact name first, then the shortest name containing it. */
    private fun best(control: ControlService, wanted: String): AccessibilityNodeInfo? {
        val hits = control.findAll { node ->
            val label = control.labelOf(node).trim().lowercase()
            !node.isEditable && wanted in label
        }
        val best = hits.minByOrNull { node ->
            val label = control.labelOf(node).trim().lowercase()
            if (label == wanted) 0 else label.length
        }
        hits.filter { it !== best }.forEach(control::releaseNode)
        return best
    }

    private fun isSearchField(label: String, viewId: String): Boolean {
        val cue = "$label $viewId"
        return SEARCH_CUE.containsMatchIn(cue)
    }

    private const val SETTLE_MS = 1_200L
    private const val RESULTS_MS = 2_500L
    private val SEARCH_CUE = Regex("\\b(search|find|query)\\b", RegexOption.IGNORE_CASE)
}
