package com.jarvis.os.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.calendar.CalendarReader
import com.jarvis.os.ui.calendar.CalendarScreen
import com.jarvis.os.ui.chat.ChatScreen
import com.jarvis.os.ui.components.HudOrb
import com.jarvis.os.ui.components.OrbUniverse
import com.jarvis.os.ui.components.ThemeBackdrop
import com.jarvis.os.ui.components.VoiceWave
import com.jarvis.os.ui.components.pinchToOpen
import com.jarvis.os.ui.debug.DiagnosticsScreen
import com.jarvis.os.ui.files.FilesScreen
import com.jarvis.os.ui.settings.InstructionsScreen
import com.jarvis.os.ui.settings.SettingsScreen
import com.jarvis.os.ui.speech.VOICE_SAMPLE
import com.jarvis.os.ui.theme.BackdropStyle
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.LocalAccent
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.OrbStyle
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.Surface
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.Speaker
import com.jarvis.os.voice.VoiceUiState
import java.util.Calendar
import kotlinx.coroutines.launch

/** Drawer destinations. Home is the live voice screen; Chat shows history; others are placeholders. */
private enum class Dest(val label: String, val icon: ImageVector, val blurb: String) {
    Home("Home", Icons.Filled.Home, ""),
    Chat("Chat & memory", Icons.Filled.Forum, "Your conversation and what JARVIS remembers."),
    Instructions("Custom instructions", Icons.Filled.EditNote, "What JARVIS always knows about you."),
    Calendar("Calendar", Icons.Filled.CalendarMonth, "Your schedule and events."),
    Files("Files", Icons.Filled.Folder, "Browse and act on your files."),
    Automation("Automation", Icons.Filled.Bolt, "Automate taps, typing, and actions."),
    Settings("Settings", Icons.Filled.Settings, "Voice and appearance."),
    Diagnostics("Diagnostics", Icons.Filled.BugReport, "Self-checks, a typed command box, and the shareable trace."),
}

/**
 * How far the top-of-screen pull must travel before it returns to Home.
 *
 * Generous on purpose: this gesture competes with the notification shade, so a
 * short flick is far more likely to be the user reaching for that than asking to
 * navigate. Better to need a deliberate pull than to bounce someone home when
 * they wanted their notifications.
 */
private const val PULL_HOME_PX = 140f

/** Task row accents, led by the theme so the card is not stuck on cyan. */
@Composable
private fun taskAccents(): List<Color> =
    listOf(LocalAccent.current, LocalPalette.current.highlight, SuccessGreen)

/**
 * App shell. Home shows the live voice screen, Chat the conversation history,
 * other destinations their own screens; Back returns to Home.
 *
 * Navigation depends on the theme. Five of the seven open a side drawer from a
 * top-left menu button. **Orbit** instead carries a dashboard bar across the
 * bottom that expands into a scrollable list — its design has no room for a menu
 * button, since the top of the screen is the greeting and the bottom already
 * carries the bar. That bar shows on every destination, not just Home, because
 * in that theme it is the only way to navigate.
 */
@Composable
fun JarvisApp(
    state: VoiceUiState,
    /**
     * Mic level, threaded as a lambda from the engine all the way to the two
     * Canvases that draw it.
     *
     * It used to be a field on [state], and since the whole app composes under a
     * single read of that state at the top of `setContent`, every RMS callback
     * from the microphone recomposed the entire tree — home, settings, chat, a
     * list mid-scroll. Passing a lambda means the value is read in the draw phase
     * and a level change costs one invalidated Canvas.
     */
    amplitude: () -> Float,
    onClearChat: () -> Unit,
    onWake: () -> Unit = {},
    onInterrupt: () -> Unit = {},
    onSubmitCommand: (String) -> Unit = {},
    voiceOptions: () -> List<Speaker.Option> = { emptyList() },
    currentVoiceId: () -> String? = { null },
    shouldOfferVoiceDownload: () -> Boolean = { false },
    onChooseVoice: (String) -> Unit = {},
    onPreviewVoice: (String) -> Unit = {},
    onVoiceDownloadOffered: () -> Unit = {},
    customInstructions: () -> String = { "" },
    learnedFacts: () -> List<String> = { emptyList() },
    onSaveInstructions: (String) -> Unit = {},
    onForgetFact: (String) -> Unit = {},
    backgroundWakeEnabled: () -> Boolean = { true },
    onSetBackgroundWake: (Boolean) -> Unit = {},
    floatingOrbEnabled: () -> Boolean = { true },
    onSetFloatingOrb: (Boolean) -> Unit = {},
    onOpenAssistantSettings: () -> Unit = {},
    palette: JarvisPalette = JarvisPalette.Default,
    onSelectPalette: (JarvisPalette) -> Unit = {},
    /** Empty means "follow the theme" — see [BackdropStyle.resolve]. */
    backdropId: String = "",
    onSelectBackdrop: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(Dest.Home) }

    // Orbit navigates from a bottom dashboard instead of the side drawer, because
    // its design has no room for a menu button: the top of the screen is the
    // greeting and the bottom already carries the "open dashboard" bar. The other
    // six themes keep the drawer, so this is a property of the look rather than a
    // change everyone has to accept.
    val bottomDashboard = palette.orbStyle == OrbStyle.Orbit
    var dashboardOpen by remember { mutableStateOf(false) }
    // The universe is hosted here rather than inside HomeContent so it covers
    // the dashboard bar and the drawer too. A full-screen dive with a nav bar
    // floating on top of it is not immersive, it is a screenshot with chrome.
    var universeOpen by remember { mutableStateOf(false) }

    // Where the orb actually is, and how big the host is, so the dive can be
    // anchored to the thing that was pinched rather than to the middle of the
    // screen. The orb sits a little above centre and the difference is visible:
    // pivoting on the screen centre makes the galaxy arrive from slightly below
    // the orb, which reads as two separate objects rather than one becoming the
    // other.
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    var orbCentre by remember { mutableStateOf(Offset.Unspecified) }

    // ONE number drives the whole transition, both directions.
    //
    // It was a cross-fade — the galaxy simply appeared over the home screen —
    // and a cross-fade says "different screen", not "you went inside that".
    // Here the same progress pushes the home screen up through the camera while
    // the universe grows out of the orb, so at every instant the two are parts
    // of one movement instead of two animations that happen to overlap.
    //
    // Slower in than out, deliberately: arriving somewhere should take a moment,
    // leaving should feel like surfacing.
    val dive by animateFloatAsState(
        targetValue = if (universeOpen) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (universeOpen) 620 else 380,
            easing = FastOutSlowInEasing,
        ),
        label = "dive",
    )
    val divePivot = remember(orbCentre, hostSize) {
        if (orbCentre.isSpecified && hostSize.width > 0 && hostSize.height > 0) {
            TransformOrigin(
                orbCentre.x / hostSize.width,
                orbCentre.y / hostSize.height,
            )
        } else {
            TransformOrigin.Center
        }
    }

    // Deliberately an if/else chain rather than two independent handlers: with
    // both registered, which one answers Back depends on composition order, which
    // is not something a reader should have to work out.
    if (dashboardOpen) {
        BackHandler { dashboardOpen = false }
    } else if (current != Dest.Home) {
        BackHandler { current = Dest.Home }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Orbit has no drawer, so its edge-swipe must not summon one. Leaving the
        // gesture live meant a right-swipe opened a side menu the theme had
        // deliberately replaced — the drawer was still there, just invisible.
        gesturesEnabled = !bottomDashboard,
        drawerContent = {
            JarvisDrawer(
                selected = current,
                onSelect = {
                    current = it
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(palette.background)
                .onSizeChanged { hostSize = it },
        ) {
            // Everything the app normally is, in a layer that can be flown into.
            //
            // Swelling and fading rather than just fading: a shrinking layer would
            // read as the screen retreating, and the gesture is the opposite — you
            // pulled the orb apart and went through it. Alpha runs out ahead of the
            // scale (x1.6 at the end) so the home screen is gone well before it has
            // grown enough to look pixellated.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (dive > 0f) {
                            val swell = 1f + dive * 0.60f
                            scaleX = swell
                            scaleY = swell
                            alpha = (1f - dive * 1.45f).coerceAtLeast(0f)
                            transformOrigin = divePivot
                        }
                    },
            ) {
                // Behind EVERY destination, not just Home.
                //
                // It used to live inside HomeContent, which meant the app had one
                // designed screen and six generic dark lists — you opened Settings
                // and the world you were just looking at vanished. That gap is most
                // of what "boring and basic" was describing: not that any single
                // screen was bad, but that six of them were somewhere else entirely.
                //
                // One instance here also costs less than one per screen: the starfield
                // stays put while destinations change over it, so switching tabs no
                // longer re-randomises the sky.
                // Resolved here, once, for every destination. `resolve` falls back
                // to the theme's own backdrop for an empty id AND for an id from a
                // build where that background existed and no longer does — an id
                // outlives an uninstall, so that second case is real.
                ThemeBackdrop(
                    palette = palette,
                    backdrop = BackdropStyle.resolve(backdropId, palette.orbStyle),
                    // Animated only where nothing scrolls. The sky's clocks run at
                    // 150 and 38 seconds, so its motion is imperceptible by design
                    // — but the invalidation it causes is not, and behind a
                    // scrolling list it was a full-screen redraw every frame
                    // competing with the scroll for the same budget. Home has no
                    // list; everywhere else gets the same picture, still.
                    live = current == Dest.Home,
                )

                when (current) {
                    Dest.Home -> HomeContent(
                        state = state,
                        amplitude = amplitude,
                        onWake = onWake,
                        onInterrupt = onInterrupt,
                        onExpand = { universeOpen = true },
                        onOrbPlaced = { orbCentre = it },
                    )
                    Dest.Chat -> ChatScreen(state.messages, onClearChat)
                    Dest.Diagnostics -> DiagnosticsScreen(onSubmitCommand = onSubmitCommand)
                    Dest.Settings -> SettingsScreen(
                        voices = voiceOptions(),
                        currentVoiceId = currentVoiceId(),
                        shouldOfferVoiceDownload = shouldOfferVoiceDownload(),
                        onChooseVoice = onChooseVoice,
                        onPreviewVoice = { onPreviewVoice(VOICE_SAMPLE) },
                        onVoiceDownloadOffered = onVoiceDownloadOffered,
                        palette = palette,
                        onSelectPalette = onSelectPalette,
                        backdropId = backdropId,
                        onSelectBackdrop = onSelectBackdrop,
                        backgroundWakeEnabled = backgroundWakeEnabled(),
                        onSetBackgroundWake = onSetBackgroundWake,
                        floatingOrbEnabled = floatingOrbEnabled(),
                        onSetFloatingOrb = onSetFloatingOrb,
                        onOpenAssistantSettings = onOpenAssistantSettings,
                    )
                    Dest.Instructions -> InstructionsScreen(
                        initial = customInstructions(),
                        learned = learnedFacts(),
                        onSave = onSaveInstructions,
                        onForget = onForgetFact,
                    )
                    Dest.Calendar -> CalendarScreen()
                    Dest.Files -> FilesScreen()
                    else -> PlaceholderScreen(current)
                }

                if (!bottomDashboard) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Open menu",
                        tint = TextPrimary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .systemBarsPadding()
                            .padding(12.dp)
                            .clip(CircleShape)
                            .clickable { scope.launch { drawerState.open() } }
                            .padding(6.dp)
                            .size(26.dp),
                    )
                } else {
                    // Home only. Drawn over Settings it landed on top of the theme
                    // picker and read as a rendering fault — every other destination
                    // has its own scrolling layout that owns the bottom of the screen,
                    // and a floating bar over it is not navigation, it is debris.
                    if (current == Dest.Home) {
                        DashboardBar(
                            onOpen = { dashboardOpen = true },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    } else {
                        // With the bar gone from other screens, the way back is a pull
                        // down from the top — the gesture that replaces it. Confined to
                        // a strip so it cannot fight the vertical scrolling below.
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(72.dp)
                                .pointerInput(current) {
                                    var travelled = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = { travelled = 0f },
                                        onDragEnd = {
                                            if (travelled > PULL_HOME_PX) current = Dest.Home
                                        },
                                    ) { change, dragAmount ->
                                        if (dragAmount > 0f) travelled += dragAmount
                                        change.consume()
                                    }
                                },
                        )
                    }

                    if (dashboardOpen) {
                        // Tapping away closes, which is what every sheet on the
                        // platform does and what the Back handler above mirrors.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { dashboardOpen = false },
                        )
                    }

                    AnimatedVisibility(
                        visible = dashboardOpen,
                        enter = fadeIn() + slideInVertically { it },
                        exit = slideOutVertically { it } + fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        DashboardPanel(
                            selected = current,
                            onSelect = {
                                current = it
                                dashboardOpen = false
                            },
                            onDismiss = { dashboardOpen = false },
                        )
                    }
                }
            }

            // Last in the host Box, so it covers every destination and the
            // navigation with it. The first draft of this sat inside the
            // `bottomDashboard` branch, which would have given the universe to
            // the Orbit theme alone — the orb is on the home screen of all four.
            //
            // Grown out of the orb rather than faded in over it, pivoting on the
            // orb's real position: at the first frame it is a third of its size
            // sitting exactly where the orb was, which is what makes it read as
            // the orb opening rather than as a new screen arriving. Kept in the
            // tree only while the transition is live so nothing animates behind
            // a dismissed dive.
            if (dive > 0.001f) {
                OrbUniverse(
                    onClose = { universeOpen = false },
                    palette = palette,
                    amplitude = amplitude,
                    entry = dive,
                    modifier = Modifier.graphicsLayer {
                        val grow = 0.32f + 0.68f * dive
                        scaleX = grow
                        scaleY = grow
                        alpha = dive
                        transformOrigin = divePivot
                    },
                )
            }
        }
    }
}

/**
 * The collapsed dashboard bar from the Orbit design: a chevron over two lines,
 * sitting across the bottom of the screen.
 *
 * It replaces the drawer's menu button entirely for that theme. The button had
 * nowhere to go in this layout — the top of the screen is the greeting, and a
 * floating icon over the planet horizon looked like a mistake.
 */
@Composable
private fun DashboardBar(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(30.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), shape)
            .clickable { onOpen() }
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardDoubleArrowUp,
            contentDescription = "Open dashboard",
            tint = accent,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = "Tap to open dashboard",
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "ALL FEATURES, ALL IN ONE PLACE",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The expanded dashboard: every destination, scrollable.
 *
 * Capped at 78% of the height rather than filling the screen, so the orb stays
 * visible behind it — the sheet reads as something drawn over the assistant
 * rather than as a different screen, which is the whole point of putting
 * navigation down here.
 */
@Composable
private fun DashboardPanel(
    selected: Dest,
    onSelect: (Dest) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f)
            .clip(shape)
            .background(JarvisTheme.surface)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), shape)
            .systemBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "DASHBOARD",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = "All features, all in one place",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close dashboard",
                tint = TextSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onDismiss() }
                    .padding(6.dp)
                    .size(22.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = JarvisTheme.glassBorder)
        Spacer(Modifier.height(6.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Dest.entries.forEach { dest ->
                DashboardRow(
                    dest = dest,
                    selected = dest == selected,
                    onClick = { onSelect(dest) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DashboardRow(dest: Dest, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = dest.icon,
            contentDescription = null,
            tint = if (selected) accent else TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.padding(start = 14.dp)) {
            Text(
                text = dest.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) accent else TextPrimary,
            )
            if (dest.blurb.isNotBlank()) {
                Text(
                    text = dest.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    state: VoiceUiState,
    amplitude: () -> Float = { 0f },
    onWake: () -> Unit = {},
    onInterrupt: () -> Unit = {},
    onExpand: () -> Unit = {},
    /** The orb's centre in root coordinates — the pivot the dive turns about. */
    onOrbPlaced: (Offset) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        // The backdrop is drawn once by the host, behind every destination — see
        // JarvisApp. Drawing it again here would stack two starfields.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            HeroSection(
                state = state,
                amplitude = amplitude,
                height = viewport,
                onWake = onWake,
                onInterrupt = onInterrupt,
                onExpand = onExpand,
                onOrbPlaced = onOrbPlaced,
            )
            ScheduleSection()
        }
    }
}

@Composable
private fun HeroSection(
    state: VoiceUiState,
    /** Mic level as a lambda — it reaches the orb and the wave, and is read in their draw. */
    amplitude: () -> Float = { 0f },
    height: Dp,
    onWake: () -> Unit = {},
    onInterrupt: () -> Unit = {},
    onExpand: () -> Unit = {},
    onOrbPlaced: (Offset) -> Unit = {},
) {
    val greeting = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    // Show live transcript/reply only during an active conversation; hide when asleep.
    val active = state.orb != OrbState.Idle && state.orb != OrbState.Offline
    // Tapping while JARVIS talks cuts him off, which is the one case where a tap
    // during an active conversation should do something rather than nothing.
    val speaking = state.orb == OrbState.Speaking

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(greeting, style = MaterialTheme.typography.displayMedium, color = TextPrimary)
        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(28.dp))
        // The orb answers a tap in two situations and stays inert in between.
        // Asleep, it wakes JARVIS without the wake word. Speaking, it stops him —
        // every other assistant lets you cut in, and until now this one made you
        // wait out a sentence you had already heard enough of. While listening or
        // thinking there is nothing useful a tap could mean, so it does nothing.
        //
        // Pinching it apart opens the universe. Two fingers, so it cannot be
        // reached by accident and cannot be confused with the tap above — and it
        // is the gesture the request named: "like how u do with images on a
        // screen".
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = !active || speaking) {
                    if (speaking) onInterrupt() else onWake()
                }
                .pinchToOpen(onExpand)
                // Reported upward so the dive can pivot on the orb itself. The
                // hero scrolls, so this is not a constant — reading it from the
                // layout is the only way it stays right.
                .onGloballyPositioned { onOrbPlaced(it.boundsInRoot().center) },
            contentAlignment = Alignment.Center,
        ) {
            HudOrb(orb = state.orb, amplitude = amplitude, size = 280.dp)
        }
        Spacer(Modifier.height(8.dp))

        // The waveform strip from the designs. Driven by the real mic level, so
        // it shows whether JARVIS can currently hear you rather than just moving.
        VoiceWave(amplitude = amplitude)
        Spacer(Modifier.height(12.dp))

        // Uppercase and widely tracked, as the status block reads in the designs.
        // The text is the app's REAL state, not the decorative three lines from
        // the artwork — a status that always said "COMMAND ACCEPTED" would be a lie.
        Text(
            text = state.status.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
            color = statusColor(state.orb),
            textAlign = TextAlign.Center,
        )

        if (active) {
            Spacer(Modifier.height(10.dp))
            // What he said, in a box that SCROLLS.
            //
            // These were bare Text in a Column pinned to the viewport height, so a
            // long answer simply ran off the bottom and was unreachable — the user
            // asked how backends work, got several paragraphs, and could read the
            // first third of it. Clipped text with no way to reach the rest is
            // worse than a short answer, because it looks like the app is broken
            // rather than brief.
            //
            // Bounded as a FRACTION of the hero rather than a fixed dp: the orb
            // above it has to stay on screen on a small phone, and a constant that
            // is right on one device is wrong on the next.
            Column(
                modifier = Modifier
                    .heightIn(max = height * 0.34f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.transcript.ifBlank { "Listening…" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.transcript.isBlank()) TextSecondary else TextPrimary,
                    textAlign = TextAlign.Center,
                )
                if (state.reply.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.reply,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.orb == OrbState.Error) ErrorRed else LocalAccent.current,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleSection() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            text = "Today's Tasks",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        TasksCard()
    }
}

@Composable
private fun PlaceholderScreen(dest: Dest) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(dest.icon, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text(dest.label, style = MaterialTheme.typography.displayMedium, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text("COMING SOON", style = MaterialTheme.typography.labelSmall, color = LocalAccent.current)
        Spacer(Modifier.height(16.dp))
        Text(
            text = dest.blurb,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun JarvisDrawer(selected: Dest, onSelect: (Dest) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = JarvisTheme.surface) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "J.A.R.V.I.S.",
            style = MaterialTheme.typography.headlineSmall,
            color = LocalAccent.current,
            modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
        )
        Text(
            text = "OPERATING SYSTEM",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(start = 24.dp, bottom = 16.dp),
        )
        HorizontalDivider(color = JarvisTheme.glassBorder)
        Spacer(Modifier.height(8.dp))

        Dest.entries.forEach { dest ->
            NavigationDrawerItem(
                label = { Text(dest.label, style = MaterialTheme.typography.titleMedium) },
                selected = dest == selected,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = JarvisTheme.glass,
                    unselectedContainerColor = Color.Transparent,
                    selectedIconColor = LocalAccent.current,
                    unselectedIconColor = LocalAccent.current,
                    selectedTextColor = TextPrimary,
                    unselectedTextColor = TextPrimary,
                ),
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

/**
 * The real device calendar, not an invented list.
 *
 * This card used to show three hardcoded fake events, which made the whole
 * screen decorative — it said "Team sync 10:00" on a phone with an empty
 * calendar. It now reads the same source the assistant answers from, so the
 * screen and JARVIS can never disagree.
 */
@Composable
private fun TasksCard() {
    val context = LocalContext.current
    // Re-read on every entry to Home: events change outside the app, and JARVIS
    // itself adds and deletes them.
    val agenda = remember { CalendarReader.agenda(context) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(JarvisTheme.glass)
            .border(BorderStroke(1.dp, JarvisTheme.glassBorder), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        when {
            // Null and empty mean different things, and the old fake list could
            // express neither.
            agenda == null -> EmptyNote("Grant calendar access and your real schedule appears here.")
            agenda.isEmpty() -> EmptyNote("Nothing scheduled in the next couple of days.")
            else -> Column {
                val accents = taskAccents()
                agenda.take(MAX_AGENDA_ROWS).forEachIndexed { index, event ->
                    EventRow(event, accents[index % accents.size])
                    if (index != agenda.take(MAX_AGENDA_ROWS).lastIndex) {
                        Spacer(Modifier.height(14.dp))
                    }
                }
                if (agenda.size > MAX_AGENDA_ROWS) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "+${agenda.size - MAX_AGENDA_ROWS} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
}

@Composable
private fun EventRow(event: CalendarReader.Event, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            event.title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        Text(event.timeLabel(), style = MaterialTheme.typography.labelLarge, color = accent)
    }
}

private const val MAX_AGENDA_ROWS = 4

@Composable
private fun statusColor(orb: OrbState): Color = when (orb) {
    OrbState.Listening -> LocalAccent.current
    OrbState.Thinking -> LocalPalette.current.secondary
    OrbState.Speaking -> SuccessGreen
    OrbState.Error -> ErrorRed
    else -> TextSecondary
}

private fun greetingForHour(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}

@Preview(showBackground = true, backgroundColor = 0xFF050B18)
@Composable
private fun JarvisAppPreview() {
    JarvisTheme {
        JarvisApp(
            state = VoiceUiState(
                orb = OrbState.Speaking,
                status = "Speaking…",
                transcript = "What's my schedule today",
                reply = "You have a team sync at 10:00 and a call with your mentor at 18:30.",
            ),
            amplitude = { 0.4f },
            onClearChat = {},
        )
    }
}
