package com.newax.aegis.ui.state

/**
 * Settings screen state — the plain-Kotlin half of the Settings surface (T3.1).
 * The model-readiness predicate and the ambient-mode toggle used to live inline
 * in the composable; they are here so the decisions are unit-testable and the
 * screen only renders.
 *
 * The holder is stateless: the settings values themselves live in the
 * automation toggles / policy store / voice service behind the ViewModel.
 */
class SettingsScreenState {

    /** The model card's green dot: a status string is "ready" when it contains the word. */
    fun isModelReady(status: String): Boolean = status.contains("ready", true)

    /** The ambient-mode chips, in display order. */
    val ambientModes = listOf("Meeting", "Lecture")

    /**
     * The ambient chip's click decision: selecting the ACTIVE mode ends ambient
     * mode (null); selecting another switches to it. Mirrors the two-branch
     * toggle the UI used to inline.
     */
    fun ambientToggle(current: String?, selected: String): String? =
        if (current == selected) null else selected
}
