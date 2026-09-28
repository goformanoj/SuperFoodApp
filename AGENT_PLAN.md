# JARVIS as an everyday agent — the plan

> **Why this exists (user, 2026-09-28):** *"right now the app has no practical function… plan to make
> the app a proper AI assistant… a proper agent for everyday work… plan more on organising the data,
> the chats and everything."* This document is the answer. It **re-sequences the 🧭 Roadmap** in
> `EXECUTION_PLAN.md` (Phases 3–5 are replaced by the agent phases below); the lettered Parts stay as
> detailed specs.

---

## 1. What "useful" means — the test we build against

JARVIS is useful when it does things a person otherwise does by hand, several times a day. The plan is
judged by **ten everyday scenarios**. Each phase must make some of them work end to end, on the real app,
with the user's real data. Until a scenario passes, it is not done — however much code exists.

| # | The user says… | What JARVIS must do |
|---|---|---|
| S1 | "Remind me at 5 to call the bank." | Create a reminder; a Windows notification at 5:00 (phone too, later). |
| S2 | "What's on my plate today?" | Tasks due, reminders, calendar events — one spoken/written brief. |
| S3 | "Add *send the deck to Priya* to my to-dos for tomorrow." | A task with a due date, visible in Tasks, reminded on the day. |
| S4 | "Summarise this PDF and turn the action points into tasks." | Read a local document, summarise with page citations, create tasks. |
| S5 | "Find the invoice from March." | Search the laptop's files by name and content; open or attach the right one. |
| S6 | "What's this error on my screen?" | Screenshot → vision model → an explanation of what's actually visible. |
| S7 | "What's the latest on the RBI rate decision?" | Live web search with sources — not the model's stale memory. |
| S8 | "Draft a reply to the landlord saying I'll pay Friday." | A draft in the user's voice; sending needs an explicit OK. |
| S9 | "Every weekday at 8, brief me." | A routine that runs by itself and speaks/notifies. |
| S10 | "What did we decide about the budget last week?" | Search past conversations, notes and memory; answer with the source. |

A scenario is **observable**: the user can run it and see the result. That is the definition of done.

---

## 2. What is missing today (honest)

JARVIS today can chat, remember short facts, talk and listen. It **cannot act**: the only "actions" are
phone-style markers the desktop strips out. It has **no organised data**: conversations are one flat list
in a JSON file, memory is a list of strings, nothing is searchable, nothing is linked. It has **no tools**:
no reminders, tasks, files, web, calendar or email. And it **isn't always there**: it's a window you open,
not something that runs in the background and comes to you.

Four gaps, so four foundations: **(A) an organised data model, (B) a real agent with tools, (C) knowledge
from the user's own files and the live web, (D) always-there presence.**

---

## 3. Foundation A — organising the data ("the JARVIS brain")

Everything JARVIS knows or makes lands in **one local database** with a clear shape, instead of loose JSON
files. **SQLite** (one file in `%APPDATA%\JarvisOS`, with FTS5 full-text search), local-first: it works
offline, it is fast, and the user owns it. Sync to the account comes later (Phase 6) through the Worker, so
the phone and laptop share one brain.

### The entities

| Entity | What it holds | Why it matters |
|---|---|---|
| **Project** | A named space: "Work", "House move", "Health". Colour, pinned. | Organises everything else; answers "show me everything about X". |
| **Conversation** | Title, auto-summary, project, pinned/archived, created/updated. | Chats stop being an endless flat list. |
| **Message** | Role, text, time, the tools it used, the items it created. | Every message links to what it produced — traceable. |
| **Memory** | A typed fact: *profile* (name, preferences), *person* (who, relationship, birthday), *place*, *project context*, *standing instruction*. Source conversation + date. | "Why do you know that?" has an answer; people and preferences are structured, not strings. |
| **Task** | Title, notes, due date/time, priority, status, project, source. | S3, S4, S2. The heart of daily usefulness. |
| **Reminder** | Text, when (one-off or recurrence), delivery (notify / speak / phone), linked task. | S1, S9. |
| **Note / Document** | Notes JARVIS writes, drafts, summaries, imported files (PDF, DOCX, TXT, MD) with their text extracted and chunked. | S4, S8, S10 — the user's own knowledge. |
| **File reference** | A path on the laptop JARVIS has seen or indexed, with its text if indexed. | S5 — find files without copying them. |
| **Activity** | Every action JARVIS took: tool, arguments, result, approved by whom, when. | Trust. The user can see and undo what the agent did. |

### Rules for the data

- **Search everything, one box.** A global search (Ctrl+K) over conversations, notes, tasks, memory and
  indexed files, with filters by project, type and date. This is also the agent's `search_my_stuff` tool (S10).
- **Auto-organise, never silently.** JARVIS titles and summarises conversations and *suggests* a project;
  the user can move anything. Nothing is deleted automatically.
- **Provenance on everything.** Every memory, task and note links back to the conversation that created it.
- **Migration is lossless.** Today's `chat.json` (conversations + facts) imports into the database on first
  run, then stays as a backup. Tested.
- **Privacy.** Local by default. The existing sensitive-data masking applies to memory; an **Export all**
  (JSON + files) and **Delete all** in Settings; the index never leaves the laptop unless sync is turned on.

### The screens this gives the app

- **Home = Today**: the brief (tasks due, reminders, calendar), then the reactor and composer. Real data only.
- **Chats**: grouped by project, searchable, pin/archive/move.
- **Tasks**: today / upcoming / by project; tick off; JARVIS-created tasks show where they came from.
- **Memory**: people, preferences and instructions as cards, each editable, each with its source.
- **Files**: documents JARVIS made and files the user added, with search and Q&A.
- **Activity**: what JARVIS did, when, with undo where undo is possible.

---

## 4. Foundation B — a real agent (tools, not markers)

### How it works

The desktop moves from phone-style `<<MARKERS>>` to **native tool calling** (OpenAI-style function calling,
which Groq's Llama 3.3 70B supports). The loop:

1. The laptop sends the conversation **plus the list of tools it can run**.
2. The model answers with text, or asks to call a tool (`add_task`, `search_files`…).
3. The **laptop runs the tool locally** (the data and files live there), shows the step in the UI, and
   sends the result back.
4. Repeat until the model answers — capped at a few steps per turn, each step visible.

The Worker stays the gatekeeper (key, metering, owner rules); it just passes the tool list through and
meters every step. The phone keeps its marker protocol until it adopts the same loop.

### The permission model (Rule 6, in code)

Every tool has a risk level, enforced in code — never by prompt wording:

| Level | Examples | Behaviour |
|---|---|---|
| **Read** | search, read a file, list tasks, web search | Runs immediately; logged. |
| **Write (undoable)** | add task, set reminder, save note, rename a file | Runs immediately; logged with **Undo**. |
| **Irreversible / external** | send an email, delete a file, pay, post, book | **Always** an approval card (Approve / Edit / Cancel). No approval, no action. Tested like `SendGuard`. |

### The first tool set (everyday, laptop-local, no new accounts needed)

`add_task`, `list_tasks`, `complete_task`, `set_reminder`, `list_reminders`, `save_note`, `search_my_stuff`
(conversations + notes + tasks + memory), `remember` / `forget` (typed memory), `open_app`, `open_url`,
`read_clipboard`, `get_time_and_date`. This alone makes **S1, S2, S3, S10** work.

---

## 5. Foundation C — knowledge: the user's files and the live web

- **Documents (S4):** drag a PDF/DOCX/TXT onto JARVIS, or say "this file". Text is extracted locally
  (Apache PDFBox for PDF, Apache POI for DOCX), chunked, and indexed in SQLite FTS. Answers cite the page.
  For long documents: retrieve the relevant chunks, don't paste the whole file into the prompt (that is what
  keeps it inside the allowance).
- **Laptop file search (S5):** the Windows Search index (already on every Windows PC) answers "find the
  invoice from March" by name and content, via its OLE DB/`search-ms` interface; JARVIS indexes nothing
  itself unless asked.
- **Screen questions (S6):** a hotkey takes a screenshot (Compose/AWT `Robot`), the user confirms it, and a
  vision-capable model on Groq reads it. Screenshots are never kept unless the user saves one.
- **Live web (S7):** Groq's *compound* models have **built-in web search**, so this needs no second API key:
  the Worker routes a `web_search` tool call to a compound model and returns the answer with its sources.
  (Fallback if that proves weak: a search API key on the Worker — Brave or Tavily — the user's call, costs.)

---

## 6. Foundation D — always there

A useful assistant comes to you. On the laptop that means:

- **System tray:** JARVIS keeps running when the window is closed; the tray icon shows its state.
- **Start with Windows** (a setting, off by default).
- **Windows notifications** for reminders and finished tasks (S1, S9).
- **Quick bar (Alt+Space):** a small floating box anywhere — ask, or act on the selected text/clipboard
  ("rewrite this more politely", "translate", "add this as a task"). The single biggest everyday habit-maker.
- **Wake word "Jarvis"** (in progress, Phase 2.2) and push-to-talk (done).
- **Installer:** `JARVIS.exe` works (Phase 1.3); an MSI with Start-menu entry next.

---

## 7. The phases, in order

Each phase ships on its own, in the working loop (tests → CI → `main` → docs), and ends with scenarios the
user runs. Estimates are in focused sessions, not calendar time.

### Phase 3 — The brain (organised data) · ~2–3 sessions
SQLite store + migration from `chat.json`; Projects; typed Memory; Tasks; Reminders (data only); Notes;
Activity log; global search (Ctrl+K). New screens: Chats by project, Tasks, Memory cards, Activity.
**Exit:** conversations are grouped and searchable; typed memory has sources; a task added by hand appears
in Today. (S10 by search.)

### Phase 4 — The agent core · ~2–3 sessions
Tool calling end to end (Worker passes tools through and meters each step; laptop runs them); the
permission levels in code, with approval cards and Undo; the first tool set; visible steps in chat.
Reminders fire as Windows notifications; system tray + background running.
**Exit:** S1, S2, S3, S10 pass by voice and by typing.

### Phase 5 — Knowledge · ~2–3 sessions
Documents in (PDF/DOCX/TXT) with cited Q&A; Windows file search; screenshot questions; live web search.
**Exit:** S4, S5, S6, S7 pass.

### Phase 6 — Everyday integrations + routines · ~3 sessions
Google sign-in on the desktop (needs the user's Desktop OAuth client) extended with **Calendar** and **Gmail**
scopes; calendar read/create; mail triage, summaries and drafts (send = approval); routines on a schedule
(morning brief, weekly review) with notifications and speech; the Quick bar.
**Exit:** S2 (with calendar), S8, S9 pass.

### Phase 7 — One brain on every device · ~3 sessions
Account sync of the data model through the Worker (D1 for rows, R2 for files); the phone reads and writes
the same tasks, reminders, notes and memory; cross-device commands ("on my phone, set an alarm"); phone moves
to the same tool loop.
**Exit:** a task added on the laptop shows on the phone; a phone reminder fires on both.

### Phase 8 — Hands on the computer · open-ended
Browser automation (Playwright/CDP) and app automation for multi-step errands, with a live view and the
approval gate. The phone's screen control continues as its own track.

### Phase 9 — Launch (unchanged: Part E)
Plus what this plan adds to the launch checklist: Google's **restricted-scope verification** for Gmail
(needed before strangers can use it; not needed while it is only the owner and test users), a privacy policy
covering local files and screenshots, and **a commercially licensed wake-word model** — openWakeWord's
pre-trained "hey jarvis" is **CC BY-NC-SA (non-commercial)**; fine for development, not for a paid app
(the phone has the same issue today).

---

## 8. Decisions only the user can make (asked as each phase starts)

1. **Web search:** Groq's built-in (no new key; start here) vs. a search API key (Brave/Tavily; small cost).
2. **Gmail & Calendar access:** yes/no, and which account(s). Needs the Desktop OAuth client anyway.
3. **Start with Windows** by default: on/off.
4. **Sync:** local-only until Phase 7, then opt-in per data type (e.g. sync tasks, keep files local).
5. **Model tier:** Llama 3.3 70B (current) vs. a stronger paid model for hard agent work — cost vs. quality.

---

## 9. What we will NOT do (to stay useful, not impressive)

- No feature that shows sample data. Real data or an honest empty state.
- No action without a log entry, and no irreversible action without an approval card.
- No phone gesture ported to the laptop without a laptop equivalent (lesson from the orb "universe").
- No feature ships because it looks good; each ships because a scenario passes.
