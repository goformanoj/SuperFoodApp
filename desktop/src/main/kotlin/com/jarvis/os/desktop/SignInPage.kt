package com.jarvis.os.desktop

import java.util.Base64

/**
 * The page Google's redirect lands on after the browser sign-in — the last thing the person sees before going back
 * to the app, so it should look like part of JARVIS rather than a bare browser tab. It is served by the one-shot
 * server on 127.0.0.1, so it must be self-contained: no web fonts, no images from the internet; the logo travels
 * inside the page as a data URI.
 *
 * Pure (a string out), so the wording and the escaping are tested (SignInPageTest).
 */
object SignInPage {

    /** The JARVIS badge as a data URI, from the packaged artwork; null if it is not in this build. */
    fun logoDataUri(): String? = runCatching {
        Thread.currentThread().contextClassLoader.getResourceAsStream("logo/jarvis_logo.png")?.use {
            "data:image/png;base64," + Base64.getEncoder().encodeToString(it.readBytes())
        }
    }.getOrNull()

    fun ok(logo: String?): String = page(
        logo = logo,
        title = "You're signed in",
        body = "JARVIS is connected to your account. Your plan and usage now follow you across your devices.",
        hint = "You can close this tab and go back to JARVIS.",
        tone = "ok",
    )

    /** [message] is shown to the person, so it is escaped; it comes from Google's redirect and is not trusted. */
    fun failed(logo: String?, message: String? = null): String = page(
        logo = logo,
        title = "Sign-in didn't finish",
        body = message?.let { escape(it) } ?: "Google didn't confirm the sign-in.",
        hint = "Go back to JARVIS and press Sign in again.",
        tone = "fail",
    )

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun page(logo: String?, title: String, body: String, hint: String, tone: String): String {
        val accent = if (tone == "ok") "#3de8e0" else "#ff8a8a"
        val mark = if (logo != null) """<img class="logo" src="$logo" alt="JARVIS" width="104" height="104">""" else ""
        val icon = if (logo != null) """<link rel="icon" href="$logo">""" else ""
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>JARVIS · $title</title>$icon
<style>
  :root { --accent: $accent; }
  * { box-sizing: border-box; }
  html, body { height: 100%; margin: 0; }
  body {
    display: flex; align-items: center; justify-content: center; color: #e6f1ff;
    font: 16px/1.55 "Segoe UI", system-ui, -apple-system, sans-serif;
    background: radial-gradient(ellipse at 50% 38%, #0c2236 0%, #050c16 60%, #02060c 100%);
  }
  body::before { /* the instrument grid, as in the app */
    content: ""; position: fixed; inset: 0; pointer-events: none;
    background-image: linear-gradient(rgba(61,232,224,.05) 1px, transparent 1px), linear-gradient(90deg, rgba(61,232,224,.05) 1px, transparent 1px);
    background-size: 44px 44px;
    mask-image: radial-gradient(ellipse at center, #000 30%, transparent 75%);
  }
  .card {
    position: relative; width: min(460px, calc(100vw - 40px)); padding: 40px 36px 34px; text-align: center;
    background: rgba(8, 22, 36, .72); border: 1px solid rgba(61,232,224,.28);
    clip-path: polygon(16px 0, 100% 0, 100% calc(100% - 16px), calc(100% - 16px) 100%, 0 100%, 0 16px);
    box-shadow: 0 0 60px rgba(61,232,224,.10);
  }
  .logo { display: block; margin: 0 auto 18px; filter: drop-shadow(0 0 22px rgba(61,232,224,.45)); }
  .word { letter-spacing: .5em; font-size: 13px; color: #9fb3c8; margin: 0 0 22px .5em; }
  h1 { margin: 0 0 10px; font-size: 24px; font-weight: 600; color: var(--accent); letter-spacing: .02em; }
  p { margin: 0 0 8px; color: #c9d6e6; }
  .hint { margin-top: 22px; padding-top: 18px; border-top: 1px solid rgba(255,255,255,.08); color: #8a97ab; font-size: 14px; }
  .bar { height: 2px; margin: 0 auto 22px; width: 120px; background: linear-gradient(90deg, transparent, var(--accent), transparent); }
</style></head>
<body><main class="card">
  $mark
  <div class="word">JARVIS</div>
  <div class="bar"></div>
  <h1>$title</h1>
  <p>$body</p>
  <p class="hint">$hint</p>
</main></body></html>
"""
    }
}
