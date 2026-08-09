package com.newax.aegis.assistant

/**
 * Offline command parser and safe fallback. Replace generateReply() with a llama.cpp,
 * MediaPipe LLM Inference, or LiteRT adapter while keeping the same contract.
 */
class LocalAssistantEngine {
    fun canHandle(input: String): Boolean {
        val lower = input.trim().lowercase()
        val norm = lower.trimEnd('?', '!', '.')
        val prefixes = listOf(
            "tap ", "type ", "send ", "send image ", "update memory ", "update graph ",
            "update node ", "log communication ", "update project ", "query calendar ",
            "create event ", "prefix search ", "post media ", "tap pixels ", "delete file ",
            "delete contact ", "delete project ", "forget ", "search ", "run code ",
            "open ", "remember that ", "take screenshot", "audit security",
            "reply notification ",
            "analyze contacts", "show profile for ", "merge contacts ", "build profile for ",
            "approve draft ", "reject draft ", "approve all", "reject all drafts",
            "start learning", "stop learning", "scan now", "show drafts", "learning drafts"
        )
        val exactNorms = setOf(
            "scroll", "scroll down", "scroll up", "swipe up", "swipe down",
            "home", "go home", "recents", "recent apps", "back", "go back",
            "what do you remember", "show memory", "recall", "forget everything", "clear memory",
            "analyze contacts", "show drafts", "approve all drafts", "reject all drafts",
            "start learning", "stop learning", "scan now", "approve all"
        )
        return prefixes.any { lower.startsWith(it) } ||
            norm in exactNorms ||
            lower.contains("read screen") || lower.contains("what is on") ||
            lower.contains("contact profile") || lower.contains("person profile") ||
            lower.contains("learning draft") || lower.contains("self learn")
    }

    fun generateReply(input: String, screen: String, memory: List<String> = emptyList()): AssistantReply {
        val clean = input.trim()
        val lower = clean.lowercase()
        val norm = lower.trimEnd('?', '!', '.')
        return when {
            // Must come before the generic "tap " check below, or "tap pixels ..." would
            // always match that branch first and TapPixels would be unreachable.
            lower.startsWith("tap pixels ") -> {
                val args = clean.substringAfter("tap pixels ").trim().split(",")
                if (args.size == 2) {
                    val x = args[0].trim().toFloatOrNull() ?: 0f
                    val y = args[1].trim().toFloatOrNull() ?: 0f
                    AssistantReply("This will physically tap coordinates ($x, $y)", ProposedAction.TapPixels(x, y))
                } else {
                    AssistantReply("Invalid tap pixels command", ProposedAction.TapPixels(0f, 0f))
                }
            }
            lower.startsWith("tap ") -> {
                val label = clean.substringAfter(' ').trim().trim('"')
                AssistantReply("I found a request to tap $label. Review it first.", ProposedAction.Tap(label))
            }
            lower.startsWith("type ") -> {
                val value = clean.substringAfter(' ').trim()
                AssistantReply("Text is ready to insert. Review it first.", ProposedAction.Type(value))
            }
            lower.startsWith("delete file ") -> {
                val path = clean.substringAfter("delete file ").trim()
                AssistantReply("I am queuing this file for permanent deletion: $path", ProposedAction.DeleteFile(path))
            }
            lower.startsWith("delete contact ") -> {
                val id = clean.substringAfter("delete contact ").trim()
                AssistantReply("I am queuing contact ID $id for deletion.", ProposedAction.DeleteContact(id))
            }
            lower.startsWith("run code ") -> {
                val code = clean.substringAfter("run code ").trim()
                AssistantReply("Executing code in sandbox...", ProposedAction.RunScript(code))
            }
            lower.startsWith("audit security") -> AssistantReply("Extracting package permissions...", ProposedAction.AuditSecurity)
            lower.startsWith("take screenshot") -> AssistantReply("I will take a screenshot and save it to your gallery.", ProposedAction.TakeScreenshot)
            lower.startsWith("send image ") -> {
                AssistantReply("SendImage is disabled: coordinate-based image selection cannot reliably target the correct photo. Use the app's share sheet directly.", null)
            }
            lower.startsWith("update memory ") -> {
                val category = clean.substringAfter("update memory ").substringBefore(' ').trim()
                val value = clean.substringAfter(category).trim()
                AssistantReply("I will commit this to long-term memory.", ProposedAction.UpdateMemory(category, value))
            }
            lower.startsWith("update graph ") -> {
                val args = clean.substringAfter("update graph ").split("->").map { it.trim() }
                if (args.size == 3) {
                    AssistantReply("Updating Knowledge Graph...", ProposedAction.UpdateGraph(args[0], args[1], args[2]))
                } else {
                    AssistantReply("Error: Format must be 'update graph From -> Relation -> To'", ProposedAction.Type("Error"))
                }
            }
            lower.startsWith("update node ") -> {
                val args = clean.substringAfter("update node ").split("|").map { it.trim() }
                if (args.size == 3) {
                    AssistantReply("Updating Entity Node...", ProposedAction.UpdateNode(args[0], args[1], args[2]))
                } else {
                    AssistantReply("Error: Format must be 'update node ID | Key | Value'", ProposedAction.Type("Error"))
                }
            }
            lower.startsWith("log communication ") -> {
                val args = clean.substringAfter("log communication ").split("|").map { it.trim() }
                if (args.size == 2) {
                    AssistantReply("Logging interaction...", ProposedAction.LogCommunication(args[0], args[1]))
                } else {
                    AssistantReply("Error: Format must be 'log communication Contact | Summary'", ProposedAction.Type("Error"))
                }
            }
            lower.startsWith("update project ") -> {
                val args = clean.substringAfter("update project ").split("|").map { it.trim() }
                if (args.size == 3) {
                    AssistantReply("Updating project status...", ProposedAction.UpdateProject(args[0], args[1], args[2]))
                } else {
                    AssistantReply("Error: Format must be 'update project ID | Status | Notes'", ProposedAction.Type("Error"))
                }
            }
            lower.startsWith("delete project ") -> {
                val id = clean.substringAfter("delete project ").trim()
                AssistantReply("This will permanently delete project '$id'.", ProposedAction.DeleteProject(id))
            }
            lower.startsWith("forget ") -> {
                val args = clean.substringAfter("forget ").split("|").map { it.trim() }
                if (args.size == 2) {
                    AssistantReply("Removing this fact from memory.", ProposedAction.ForgetFact(args[0], args[1]))
                } else {
                    AssistantReply("Error: Format must be 'forget category | fact'", ProposedAction.Type("Error"))
                }
            }
            lower.startsWith("search ") -> {
                val query = clean.substringAfter("search ").trim()
                AssistantReply("Searching all data sources for: $query", ProposedAction.SearchAll(query))
            }
            lower.startsWith("prefix search ") -> {
                val prefix = clean.substringAfter("prefix search ").trim()
                AssistantReply("Searching Trie...", ProposedAction.PrefixSearch(prefix))
            }
            lower.startsWith("post media ") -> {
                val args = clean.substringAfter("post media ").split("|").map { it.trim() }
                if (args.size >= 4) {
                    AssistantReply("Drafting social media post...", ProposedAction.PostSocialMedia(args[0], args[1], args[2], args[3]))
                } else {
                    AssistantReply("Error: Format must be 'post media package | caption | image | altTag'", ProposedAction.Type("Error"))
                }
            }
            lower.startsWith("query calendar ") -> {
                val timeframe = clean.substringAfter("query calendar ").trim()
                AssistantReply("Checking your calendar for: $timeframe.", ProposedAction.QueryCalendar(timeframe))
            }
            lower.startsWith("create event ") -> {
                val args = clean.substringAfter("create event ").trim()
                val title = args.substringBefore(" at ").trim()
                val time = args.substringAfter(" at ").trim()
                AssistantReply("This will create a calendar event: $title.", ProposedAction.CreateEvent(title, time))
            }
            lower.startsWith("reply notification ") -> {
                val args = clean.substringAfter("reply notification ").split("|").map { it.trim() }
                if (args.size >= 2) {
                    AssistantReply("I will reply to the notification.", ProposedAction.ReplyNotification(args[0], args[1]))
                } else {
                    AssistantReply("Error: Format must be 'reply notification key | text'", ProposedAction.Type("Error"))
                }
            }
            lower.startsWith("reply ") || lower.startsWith("send ") -> {
                val value = clean.substringAfter(' ').trim()
                AssistantReply("This will communicate as you, so it requires approval.", ProposedAction.Send(value))
            }
            lower.startsWith("open ") -> {
                val app = clean.substringAfter(' ').trim()
                AssistantReply("Ready to open $app.", ProposedAction.OpenApp(app))
            }
            norm in setOf("scroll", "scroll down", "swipe up") ->
                AssistantReply("Scroll down is ready.", ProposedAction.Scroll(true))
            norm in setOf("scroll up", "swipe down") ->
                AssistantReply("Scroll up is ready.", ProposedAction.Scroll(false))
            norm in setOf("home", "go home") -> AssistantReply("Home action is ready.", ProposedAction.Home)
            norm in setOf("recents", "recent apps") -> AssistantReply("Recent apps are ready.", ProposedAction.Recents)
            norm == "back" || norm == "go back" ->
                AssistantReply("Back action is ready.", ProposedAction.Back)
            norm in setOf("what do you remember", "show memory", "recall") ->
                AssistantReply(if (memory.isEmpty()) "I have no saved personal facts." else memory.joinToString("\n• ", "Saved memory:\n• "))
            lower.contains("what is on") || lower.contains("read screen") ->
                AssistantReply(if (screen.isBlank()) "No readable screen is available. Enable Accessibility access." else screen)

            // ── Self-learning drafts ─────────────────────────────────────────────
            norm == "show drafts" || lower.contains("learning draft") || lower.startsWith("learning drafts") -> {
                AssistantReply("Loading pending learning drafts for your review.", ProposedAction.ShowDrafts)
            }
            norm == "start learning" || lower.contains("self learn") && lower.contains("start") -> {
                AssistantReply(
                    "Starting background self-learning. Aegis will scan contacts, SMS, call logs, images, and files in small batches every 20 minutes. All extracted facts will appear here as drafts for your approval before being added to memory.",
                    ProposedAction.StartLearning
                )
            }
            norm == "stop learning" || lower.contains("self learn") && lower.contains("stop") -> {
                AssistantReply("Stopping background self-learning. Existing drafts are preserved.", ProposedAction.StopLearning)
            }
            norm == "scan now" -> {
                AssistantReply("Running one learning scan batch now.", ProposedAction.ScanNow)
            }
            lower.startsWith("approve draft ") -> {
                val id = clean.substringAfter("approve draft ").trim()
                AssistantReply("Approving draft and adding to memory.", ProposedAction.ApproveDraft(id))
            }
            lower.startsWith("reject draft ") -> {
                val id = clean.substringAfter("reject draft ").trim()
                AssistantReply("Rejecting draft — it will be discarded.", ProposedAction.RejectDraft(id))
            }
            norm == "approve all drafts" || norm == "approve all" -> {
                AssistantReply("Approving all pending drafts and adding them to memory.", ProposedAction.ApproveAllDrafts)
            }
            norm == "reject all drafts" || lower.startsWith("reject all") -> {
                AssistantReply("Rejecting all pending drafts. No data will be saved.", ProposedAction.RejectAllDrafts)
            }

            // ── Contacts intelligence ─────────────────────────────────────────────
            norm == "analyze contacts" || lower.startsWith("analyze contacts") -> {
                val scope = if (lower.contains("all")) "all" else
                    lower.substringAfter("analyze contacts").trim().takeIf { it.isNotBlank() } ?: "all"
                AssistantReply(
                    "Scanning all contacts: normalize names (transliterate non-Latin, fix spellings, expand abbreviations), remove duplicate phone numbers, detect and merge duplicate contacts. This may take a moment.",
                    ProposedAction.AnalyzeContacts(scope)
                )
            }
            lower.startsWith("show profile for ") || lower.contains("contact profile") || lower.contains("person profile") -> {
                val name = when {
                    lower.startsWith("show profile for ") -> clean.substringAfter("show profile for ").trim()
                    lower.contains("profile for ") -> clean.substringAfter("profile for ").trim()
                    else -> clean.substringAfterLast(' ').trim()
                }
                AssistantReply(
                    "Loading intelligence profile for $name — personality traits, relationship type, communication tone, writing style, and conversation history.",
                    ProposedAction.ShowPersonProfile(name)
                )
            }
            lower.startsWith("build profile for ") -> {
                val name = clean.substringAfter("build profile for ").trim()
                AssistantReply(
                    "Building full intelligence profile for $name — reading SMS history and communication logs to extract personality, relationship, tone, and writing patterns.",
                    ProposedAction.BuildPersonProfile(name)
                )
            }
            lower.startsWith("merge contacts ") -> {
                val args = clean.substringAfter("merge contacts ").split("|").map { it.trim() }
                if (args.size == 2) {
                    AssistantReply(
                        "Merging contacts '${args[0]}' and '${args[1]}' — they will be combined into one entry.",
                        ProposedAction.MergeContacts(args[0], args[1])
                    )
                } else {
                    AssistantReply("Format: merge contacts Name1 | Name2", ProposedAction.Type("Error"))
                }
            }

            else -> AssistantReply(
                "Offline basic mode is active. I can read, tap, type, send, open apps, scroll, navigate, remember facts, analyze contacts, show/build person profiles, and merge duplicate contacts. Install a local model pack for open-ended reasoning."
            )
        }
    }
}
