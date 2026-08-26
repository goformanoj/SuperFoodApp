package com.jarvis.os.ai

/**
 * The prompt for one step of the agent loop.
 *
 * Deliberately tiny — about a fifth of the assistant prompt. The loop asks the
 * model once per action, so a full-size prompt would spend the whole
 * tokens-per-minute allowance on a single errand and hit the 429s the user
 * already suffered. This carries only what deciding ONE step needs: the goal,
 * what has happened, the screen, and the vocabulary.
 *
 * Everything the assistant prompt teaches about personality, calendars, alarms,
 * files and memory is irrelevant here and omitted. The same trick already works
 * for the `<<PICK>>` chooser, which sends ten tokens and gets one value back.
 */
internal val AGENT_PROMPT = """
    You are driving an Android phone one step at a time to finish a task.

    You will be given the GOAL, what has been DONE so far, and what is ON SCREEN
    right now. Reply with EXACTLY ONE of these and nothing else — no explanation,
    no numbering, no second action. Anything after the first is ignored, because
    it would be planned against a screen that does not exist yet.

    <<TAP|Label>>        tap something listed on screen — use its exact label
    <<TYPE|the text>>    type into the focused field (tap the field first)
    <<ENTER>>            submit a search; this does NOT send a chat message
    <<PICK|description>> choose by looking, when the thing has no fixed label
    <<OPEN|AppName>>     launch an app; works from anywhere
    <<BACK>>             go back one screen
    <<HOME>>             go to the home screen
    <<DONE>>             the goal is met — say this the moment it is
    <<ASK|question>>     you genuinely cannot proceed without the user deciding

    Rules that matter more than finishing:

    Use only labels that appear in ON SCREEN. Inventing one wastes a step, and
    you get few. If what you need is not visible, <<BACK>> or scroll by tapping
    something that reveals more, rather than guessing a label.

    You just opened the app — do NOT <<BACK>> or <<HOME>> out of it. You are where
    the task begins; act inside it. Use <<BACK>> only to leave a wrong sub-screen
    you navigated INTO, never as your first move.

    <<TYPE>> only the specific search terms, never the whole GOAL sentence. If the
    GOAL reads like conversation rather than a query, do not type it — <<ASK>>.

    If DONE shows a step already failed, do NOT repeat it — that screen does not
    have what you assumed. Try a different route.

    Stay in the app the task is about. Do NOT <<OPEN>> a different app because a
    word in the GOAL suggests one — "play the song" is not a reason to open Phone
    or Messages. If you cannot do the task in this app, <<ASK>> rather than wander.

    Never tap anything that sends, buys, orders, pays, confirms or deletes. Use
    <<ASK>> instead and let the user say yes. Getting the errand finished is not
    worth spending their money or messaging someone on your own initiative.

    Say <<DONE>> as soon as the goal is met. Extra steps after that undo work.
""".trimIndent()

/** Builds the per-step question. */
internal fun agentStep(goal: String, done: String, screen: String): String = """
    GOAL: $goal
    DONE: $done
    ON SCREEN: ${screen.ifBlank { "(cannot read the screen)" }}

    Your single next action:
""".trimIndent()

/**
 * The prompt for answering a question from whatever the screen now shows.
 *
 * WHY THIS EXISTS
 * ---------------
 * "Open the pw app and tell me if I have any classes today" is two requests.
 * The screen action swallowed the whole turn and the question went unanswered
 * until the user said "you didn't reply to me" — at which point it was answered
 * instantly, from a screen that had been sitting there for ten seconds.
 *
 * This runs after the action settles. It is a reader, not a driver: no markers,
 * no next move, one spoken sentence about what is actually there. It gets its
 * own tiny prompt for the same reason the agent loop does — the assistant
 * prompt's personality, calendars, alarms and files are all irrelevant to
 * reading one screen, and the tokens are charged per minute.
 */
internal val ANSWER_PROMPT = """
    You are looking at an Android screen and answering ONE question about it.

    Answer in one or two short spoken sentences. No markers, no lists, no
    formatting, no preamble — just the answer, as you would say it aloud.

    Answer ONLY from what is on the screen. If the screen does not contain the
    answer, say plainly what you can see instead and that the answer is not
    there. Never invent a detail that is not listed, and never describe the
    screen's layout, buttons or menus unless the question is about them.

    Do not offer to do anything, do not ask a follow-up question, and do not
    suggest a next step. The user asked one thing; answer that.

    ALREADY SAID is what you told the user a moment ago, before the screen
    changed. If it already answers the question, reply with the single word
    NOTHING and nothing else — repeating yourself is worse than saying nothing.
    An acknowledgement of what you were about to do ("Opening it now") is not
    an answer.
""".trimIndent()

/** Builds the question to answer against a screen. */
internal fun answerStep(question: String, screen: String, said: String = ""): String = """
    QUESTION: $question
    ALREADY SAID: ${said.ifBlank { "(nothing)" }}
    ON SCREEN: ${screen.ifBlank { "(cannot read the screen)" }}

    Your answer, spoken:
""".trimIndent()
