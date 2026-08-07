package com.newax.aegis.assistant

data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString()
)

sealed interface ProposedAction {
    val summary: String
    data class Tap(val label: String) : ProposedAction { override val summary = "Tap ‘$label’" }
    data class Type(val text: String) : ProposedAction { override val summary = "Type: $text" }
    data class Send(val text: String) : ProposedAction { override val summary = "Send message: $text" }
    data class OpenApp(val name: String) : ProposedAction { override val summary = "Open app ‘$name’" }
    data class Scroll(val forward: Boolean) : ProposedAction {
        override val summary = if (forward) "Scroll down" else "Scroll up"
    }
    data class SendImage(val description: String) : ProposedAction { override val summary = "Send image: $description" }
    data class UpdateMemory(val category: String, val info: String) : ProposedAction { override val summary = "Update memory [$category]: $info" }
    data class QueryCalendar(val timeframe: String) : ProposedAction { override val summary = "Query calendar: $timeframe" }
    data class CreateEvent(val title: String, val time: String) : ProposedAction { override val summary = "Create event: $title at $time" }
    data class TapPixels(val x: Float, val y: Float) : ProposedAction { override val summary = "Tap screen at ($x, $y)" }
    data class DeleteFile(val path: String) : ProposedAction { override val summary = "Delete file: $path" }
    data class DeleteContact(val id: String) : ProposedAction { override val summary = "Delete contact ID: $id" }
    data class RunScript(val code: String) : ProposedAction { override val summary = "Run JS code" }
    data class UpdateGraph(val from: String, val relation: String, val to: String) : ProposedAction { override val summary = "Update graph: $from -> $relation -> $to" }
    data class UpdateNode(val id: String, val key: String, val value: String) : ProposedAction { override val summary = "Update Node property: $id [$key = $value]" }
    data class LogCommunication(val contact: String, val summaryText: String) : ProposedAction { override val summary = "Log communication with $contact" }
    data class UpdateProject(val id: String, val status: String, val notes: String) : ProposedAction { override val summary = "Update project $id to $status" }
    data class PrefixSearch(val prefix: String) : ProposedAction { override val summary = "Instant Trie search for: $prefix" }
    data class SearchAll(val query: String) : ProposedAction { override val summary = "Cross-entity search: $query" }
    data class ForgetFact(val category: String, val fact: String) : ProposedAction { override val summary = "Forget [$category]: $fact" }
    data class DeleteProject(val id: String) : ProposedAction { override val summary = "Delete project: $id" }
    data class PostSocialMedia(val packageTarget: String, val caption: String, val imagePath: String, val altTag: String) : ProposedAction { override val summary = "Auto-post to $packageTarget" }
    data object AuditSecurity : ProposedAction { override val summary = "Audit app permissions" }
    data object TakeScreenshot : ProposedAction { override val summary = "Take and save a screenshot" }
    data object ToggleConnectivity : ProposedAction { override val summary = "Toggle Connectivity via Quick Settings" }
    data object Home : ProposedAction { override val summary = "Go to Home screen" }
    data object Recents : ProposedAction { override val summary = "Open recent apps" }
    data object Back : ProposedAction { override val summary = "Go back" }
    // Self-learning drafts
    data object ShowDrafts : ProposedAction { override val summary = "Show pending learning drafts" }
    data class ApproveDraft(val id: String) : ProposedAction { override val summary = "Approve draft $id" }
    data class RejectDraft(val id: String) : ProposedAction { override val summary = "Reject draft $id" }
    data object ApproveAllDrafts : ProposedAction { override val summary = "Approve all pending learning drafts" }
    data object RejectAllDrafts : ProposedAction { override val summary = "Reject all pending learning drafts" }
    data object StartLearning : ProposedAction { override val summary = "Start self-learning background scan" }
    data object StopLearning : ProposedAction { override val summary = "Stop self-learning background scan" }
    data object ScanNow : ProposedAction { override val summary = "Run one learning scan batch now" }
    // Contacts intelligence
    data class AnalyzeContacts(val scope: String = "all") : ProposedAction {
        override val summary = "Scan & normalize contacts (scope: $scope)"
    }
    data class ShowPersonProfile(val contactName: String) : ProposedAction {
        override val summary = "Show intelligence profile for $contactName"
    }
    data class MergeContacts(val contact1: String, val contact2: String) : ProposedAction {
        override val summary = "Merge contacts: $contact1 + $contact2"
    }
    data class BuildPersonProfile(val contactName: String) : ProposedAction {
        override val summary = "Build personality profile for $contactName"
    }
}

data class AssistantReply(val text: String, val proposedAction: ProposedAction? = null)

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

fun riskOf(action: ProposedAction): RiskLevel = when (action) {
    is ProposedAction.DeleteFile, is ProposedAction.DeleteContact,
    is ProposedAction.DeleteProject, is ProposedAction.ForgetFact,
    ProposedAction.RejectAllDrafts -> RiskLevel.CRITICAL

    is ProposedAction.Send, is ProposedAction.SendImage,
    is ProposedAction.PostSocialMedia, is ProposedAction.RunScript,
    is ProposedAction.CreateEvent -> RiskLevel.HIGH

    is ProposedAction.Tap, is ProposedAction.TapPixels, is ProposedAction.Type,
    is ProposedAction.UpdateMemory, is ProposedAction.UpdateGraph,
    is ProposedAction.UpdateNode, is ProposedAction.LogCommunication,
    is ProposedAction.UpdateProject, ProposedAction.ApproveAllDrafts,
    is ProposedAction.ApproveDraft, is ProposedAction.MergeContacts,
    is ProposedAction.AnalyzeContacts -> RiskLevel.MEDIUM

    else -> RiskLevel.LOW
}

val ProposedAction.riskLevel: RiskLevel get() = riskOf(this)

val ProposedAction.confirmationWarning: String? get() = when (riskOf(this)) {
    RiskLevel.CRITICAL -> "⚠ This action is irreversible."
    RiskLevel.HIGH     -> "This action will affect external systems or send data."
    else               -> null
}
