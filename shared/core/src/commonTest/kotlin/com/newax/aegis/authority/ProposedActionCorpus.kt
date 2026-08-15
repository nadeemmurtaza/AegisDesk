package com.newax.aegis.authority

import com.newax.aegis.assistant.ProposedAction

/**
 * Every [ProposedAction] variant with representative arguments — the enumeration
 * the exhaustive spine tests iterate.
 *
 * Shared between [PolicyEnginePropertyTest] and [AuthoritySpinePropertyTest]
 * because two copies of this list is two lists that fall out of date separately,
 * and the failure mode is silent: a new action variant added to only one copy is
 * a variant whose safety classification nobody checked.
 *
 * **When you add a `ProposedAction` variant, add it here.** [coversEveryVariant]
 * cannot enforce that automatically — Kotlin has no reflective enumeration of a
 * sealed interface's implementations on Kotlin/Native — so it enforces the next
 * best thing: the count is pinned, and adding a variant without extending this
 * list fails a test with a message saying exactly that.
 */
object ProposedActionCorpus {

    fun all(): List<ProposedAction> = listOf(
        ProposedAction.Tap("ok"),
        ProposedAction.Type("hello"),
        ProposedAction.Send("hi"),
        ProposedAction.OpenApp("WhatsApp"),
        ProposedAction.Scroll(true),
        ProposedAction.Scroll(false),
        ProposedAction.SendImage("a photo"),
        ProposedAction.UpdateMemory("personal", "fact"),
        ProposedAction.ReplyNotification("key", "sure"),
        ProposedAction.QueryCalendar("week"),
        ProposedAction.CreateEvent("Lunch", "13:00"),
        ProposedAction.TapPixels(1f, 2f),
        ProposedAction.DeleteFile("/x"),
        ProposedAction.DeleteContact("c1"),
        ProposedAction.RunScript("print(1)"),
        ProposedAction.UpdateGraph("a", "knows", "b"),
        ProposedAction.UpdateNode("n1", "key", "value"),
        ProposedAction.LogCommunication("Ayesha", "called"),
        ProposedAction.UpdateProject("p1", "done", "notes"),
        ProposedAction.PrefixSearch("ay"),
        ProposedAction.SearchAll("query"),
        ProposedAction.ForgetFact("personal", "f"),
        ProposedAction.DeleteProject("p1"),
        ProposedAction.PostSocialMedia("com.pkg", "caption", "img", "alt"),
        ProposedAction.AuditSecurity,
        ProposedAction.TakeScreenshot,
        ProposedAction.ToggleConnectivity,
        ProposedAction.Home,
        ProposedAction.Recents,
        ProposedAction.Back,
        ProposedAction.ShowDrafts,
        ProposedAction.ApproveDraft("d1"),
        ProposedAction.RejectDraft("d1"),
        ProposedAction.ApproveAllDrafts,
        ProposedAction.RejectAllDrafts,
        ProposedAction.StartLearning,
        ProposedAction.StopLearning,
        ProposedAction.ScanNow,
        ProposedAction.AnalyzeContacts(),
        ProposedAction.ShowPersonProfile("Ayesha"),
        ProposedAction.MergeContacts("a", "b"),
        ProposedAction.BuildPersonProfile("Ayesha"),
    )

    /** Distinct variant names in [all] — `Scroll` appears twice, both directions. */
    fun distinctClassNames(): Set<String> =
        all().mapNotNull { it::class.simpleName }.toSet()
}
