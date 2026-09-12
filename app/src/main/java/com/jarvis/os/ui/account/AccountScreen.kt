package com.jarvis.os.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ai.GoogleAuth
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.LocalAccent
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * The account / profile page — reached by tapping the profile row at the foot of the
 * drawer, and the one place sign-in, the plan, today's token usage and sign-out live.
 *
 * Self-contained: it reads and drives the [Identity] / [GoogleAuth] / [UsageStats]
 * singletons directly, the same way the drawer row used to. Sign-out is deliberately
 * a two-step action here — a red, icon-marked button behind a confirmation dialog —
 * because it is irreversible for the session and was previously one stray tap away.
 */
@Composable
fun AccountScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(Identity.account()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val configured = GoogleAuth.isConfigured()
    // Today's allowance as last reported by the Worker (UTC-daily). Null before the
    // first turn of the day, when we say so rather than showing a made-up zero.
    val usage = remember { UsageStats.today() }
    val accent = LocalAccent.current

    val signIn: () -> Unit = {
        if (configured && !busy) {
            busy = true
            error = null
            scope.launch {
                try {
                    account = GoogleAuth.signIn(context)
                } catch (e: Exception) {
                    error = e.message ?: "Sign-in failed"
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onBack() }
                    .padding(6.dp)
                    .size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Account", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        }
        Spacer(Modifier.height(20.dp))

        ProfileCard(account = account)

        Spacer(Modifier.height(22.dp))

        if (!account.isSignedIn) {
            SignInCard(
                configured = configured,
                busy = busy,
                error = error,
                onSignIn = signIn,
            )
            Spacer(Modifier.height(22.dp))
        }

        SectionLabel("PLAN & USAGE")
        Spacer(Modifier.height(10.dp))
        PlanUsageCard(isPro = account.isPro, usage = usage)

        if (account.isSignedIn) {
            Spacer(Modifier.height(28.dp))
            SignOutButton(onClick = { confirmSignOut = true })
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Your chats and memory are stored on this phone. Signing in keeps your plan " +
                "and lets JARVIS follow you to another device.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(24.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?", color = TextPrimary) },
            text = {
                Text(
                    "You'll return to a guest session on this phone. Your saved chats and " +
                        "memory stay on the device.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Identity.signOut()
                    account = Identity.account()
                    error = null
                    confirmSignOut = false
                    onBack()
                }) {
                    Text("Sign out", color = ErrorRed, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = JarvisTheme.surface,
        )
    }
}

@Composable
private fun ProfileCard(account: Identity.Account) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.card)
            .border(1.dp, JarvisTheme.cardBorder, shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                account.initial.toString(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (account.isSignedIn) account.displayLabel() else "Guest",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.isSignedIn && account.email != null && account.email != account.displayLabel()) {
                Text(
                    account.email!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (!account.isSignedIn) {
                Text(
                    "Not signed in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
        if (account.isSignedIn) {
            Spacer(Modifier.width(10.dp))
            PlanPill(pro = account.isPro)
        }
    }
}

@Composable
private fun SignInCard(configured: Boolean, busy: Boolean, error: String?, onSignIn: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.card)
            .border(1.dp, JarvisTheme.cardBorder, shape)
            .padding(16.dp),
    ) {
        Text(
            if (configured) "Sign in to keep JARVIS across devices" else "Sign-in not set up yet",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            error ?: if (configured) {
                "Link a Google account so your plan and settings follow you."
            } else {
                "Google sign-in isn't configured on this build."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) ErrorRed else TextSecondary,
        )
        if (configured) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (busy) "Signing in…" else "Sign in with Google",
                style = MaterialTheme.typography.labelLarge,
                color = Background,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent)
                    .clickable(enabled = !busy) { onSignIn() }
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun PlanUsageCard(isPro: Boolean, usage: UsageStats.Daily?) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.card)
            .border(1.dp, JarvisTheme.cardBorder, shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (isPro) "Pro" else "Free",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    if (isPro) "2,000,000 tokens a day" else "60,000 tokens a day",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            PlanPill(pro = isPro)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "TODAY",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (usage != null) {
            LinearProgressIndicator(
                progress = { usage.fraction },
                color = accent,
                trackColor = JarvisTheme.glassBorder,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${UsageStats.format(usage.used)} used",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
                Text(
                    "${UsageStats.format(usage.remaining)} left",
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "of ${UsageStats.format(usage.cap)} tokens · resets at midnight UTC",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            Text(
                "No activity yet today. Your usage appears here after the first request.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        if (!isPro) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.10f))
                    .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Upgrade to Pro",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        "2M tokens a day, no daily wall.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Text(
                    "Soon",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(JarvisTheme.glassBorder)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun SignOutButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ErrorRed.copy(alpha = 0.10f))
            .border(1.dp, ErrorRed.copy(alpha = 0.55f), shape)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Logout,
            contentDescription = null,
            tint = ErrorRed,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Sign out",
            style = MaterialTheme.typography.titleSmall,
            color = ErrorRed,
        )
    }
}

@Composable
private fun PlanPill(pro: Boolean) {
    val accent = LocalAccent.current
    Text(
        if (pro) "Pro" else "Free",
        style = MaterialTheme.typography.labelLarge,
        color = if (pro) Background else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (pro) accent else JarvisTheme.glassBorder)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        letterSpacing = 2.sp,
    )
}
