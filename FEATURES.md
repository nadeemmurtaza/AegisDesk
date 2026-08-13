# Newax Aegis — Command & Intent Catalog (FEATURES)

The complete, code-verified catalog of the assistant's natural-language command
vocabulary and deterministic primitives. Source of truth:
`apps/android/src/main/java/com/newax/aegis/engine/registry/IntentRegistry.kt`
(10 registered intents, 50 trigger patterns) plus the accessibility command
primitives in the deterministic engine.

---

## 1. Registered intents (IntentRegistry — 10)

Each intent matches trigger patterns in the user's phrase, extracts entities
(person / app / file_type), and resolves to a typed `actionId` that goes through
the authority spine (approval card → accessibility service). Nothing executes
directly from text.

| # | Intent ID | Name | Trigger patterns | Entities | Example phrases |
|---|---|---|---|---|---|
| 1 | `send_message` | Send Message | `send` · `message` · `text` · `msg` · `whatsapp` · `telegram` | person | "Send a message to Ali" · "WhatsApp John" |
| 2 | `open_app` | Open App | `open` · `launch` · `start` · `run` · `go to` | app | "Open WhatsApp" · "Launch Spotify" |
| 3 | `find_file` | Find File | `find` · `search` · `look for` · `where is` · `locate` | file_type, person | "Find the PDF I got yesterday" · "Where is the photo of Ali" |
| 4 | `call_person` | Call Person | `call` · `phone` · `ring` · `dial` | person | "Call Mom" · "Ring Ali" |
| 5 | `set_reminder` | Set Reminder | `remind` · `reminder` · `alert` · `notify me` | — | "Remind me at 5pm" · "Set a reminder for tomorrow" |
| 6 | `play_media` | Play Media | `play` · `listen` · `watch` · `music` · `song` · `video` | — | "Play my workout playlist" · "Watch a YouTube video" |
| 7 | `search_info` | Search Information | `what is` · `who is` · `tell me` · `explain` · `how to` · `define` | — | "What is machine learning" · "Tell me about Ali" |
| 8 | `navigate` | Navigate | `navigate` · `directions` · `how to get to` · `take me to` · `route` | — | "Navigate to home" · "Directions to airport" |
| 9 | `create_note` | Create Note | `note` · `write down` · `record` · `jot` · `remember` | — | "Note: meeting at 3pm" · "Write down buy milk" |
| 10 | `share_file` | Share File | `share` · `send file` · `send photo` · `forward` | person | "Share the document with Ali" |

**Pattern count:** 6 + 5 + 5 + 4 + 4 + 6 + 6 + 5 + 5 + 4 = **50 trigger patterns**
across the 10 intents.

## 2. Recognized entity vocabulary

- **App entities** (8): `whatsapp` · `instagram` · `twitter` · `facebook` ·
  `gmail` · `youtube` · `spotify` · `telegram`
- **File-type entities** (6): `pdf` · `image` · `video` · `audio` · `document` ·
  `photo`
- **Person entity**: any capitalized name(s) in the phrase (e.g. "Ali", "Mom")

Combined with the 50 patterns: the full recognition vocabulary is **~64
tokens** (the "~52" figure in the overview approximates the pattern set + entity
names).

## 3. Deterministic accessibility primitives

Executed by the accessibility service after approval, offline, no model needed:

- **Read screen** — dump the current accessibility tree (no screenshots stored)
- **Tap** — tap an element at its accessibility node
- **Type** — type text into the focused field
- **Reply** — reply to the current conversation (message-send primitive)
- **Send message** — compose + send via the app's own flow
- **Open app** — launch an app by name
- **Scroll** — scroll up/down in the current screen
- **Home** · **Recents** · **Back** — system navigation

## 4. Memory commands

- `remember that ...` — write to encrypted device memory
- `what do you remember` — read back stored memory
- `clear memory` — wipe stored memory

## 5. Multi-step plans

`then` chains steps: `open WhatsApp then tap Search then type Ali` — **every
step receives its own approval prompt** (PLAN is never EXECUTE; each step goes
through the policy spine).

---

## 6. Todo checklist

Everything below is **pending verification** (unchecked). Each item gets
checked only after it is confirmed working on a build; nothing is assumed.
Keep the tables above as the reference for exact patterns.

### Intents (10)

- [ ] `send_message` — Send Message (`send` · `message` · `text` · `msg` · `whatsapp` · `telegram`)
- [ ] `open_app` — Open App (`open` · `launch` · `start` · `run` · `go to`)
- [ ] `find_file` — Find File (`find` · `search` · `look for` · `where is` · `locate`)
- [ ] `call_person` — Call Person (`call` · `phone` · `ring` · `dial`)
- [ ] `set_reminder` — Set Reminder (`remind` · `reminder` · `alert` · `notify me`)
- [ ] `play_media` — Play Media (`play` · `listen` · `watch` · `music` · `song` · `video`)
- [ ] `search_info` — Search Information (`what is` · `who is` · `tell me` · `explain` · `how to` · `define`)
- [ ] `navigate` — Navigate (`navigate` · `directions` · `how to get to` · `take me to` · `route`)
- [ ] `create_note` — Create Note (`note` · `write down` · `record` · `jot` · `remember`)
- [ ] `share_file` — Share File (`share` · `send file` · `send photo` · `forward`)

### Entity vocabulary

- [ ] App entities: `whatsapp` · `instagram` · `twitter` · `facebook` · `gmail` · `youtube` · `spotify` · `telegram`
- [ ] File-type entities: `pdf` · `image` · `video` · `audio` · `document` · `photo`
- [ ] Person detection (capitalized names)

### Deterministic accessibility primitives

- [ ] Read screen (accessibility tree, no screenshots)
- [ ] Tap element
- [ ] Type text
- [ ] Reply to conversation
- [ ] Send message
- [ ] Open app
- [ ] Scroll
- [ ] Home · Recents · Back

### Memory commands

- [ ] `remember that ...`
- [ ] `what do you remember`
- [ ] `clear memory`

### Plans

- [ ] Multi-step `then` chains with per-step approval

---

See `docs/OVERVIEW.md` Part A for the full product feature list; this file is
the command-level detail for A2 (deterministic command & intent engine).
