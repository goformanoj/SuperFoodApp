package com.jarvis.os.data

/** A single task/appointment. Kept UI-free so both the screen and the AI can use it. */
data class TaskItem(val title: String, val time: String)

/**
 * The user's tasks for today. Single source of truth: shown on Home and fed to
 * the AI as grounding so it answers schedule questions from real data instead
 * of inventing them. (A real calendar source can replace this later.)
 */
val todaysTasks = listOf(
    TaskItem("Team sync", "10:00"),
    TaskItem("Finish physics assignment", "14:00"),
    TaskItem("Call mentor", "18:30"),
)
