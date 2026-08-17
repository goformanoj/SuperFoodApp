package com.jarvis.os.e2e;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A stand-in "third-party app" for the end-to-end tap tier.
 *
 * <p>It exists because of a hard constraint in {@code ScreenControlService}: {@code awaitApp}
 * and {@code userAppRoot} both refuse to act on JARVIS's own package, so a fixture rendered
 * inside the app under test can never be tapped. This Activity is declared in the
 * <b>androidTest</b> APK, whose package is {@code com.jarvis.os.test}, so to the accessibility
 * service it looks like somebody else's app — exactly the situation the executor is built for.
 *
 * <h2>Why this file is Java</h2>
 *
 * The Kotlin version of it could not work, and took two runs to understand. Its click handler
 * died instantly with:
 *
 * <pre>
 * Process: com.jarvis.os.test, PID: 2416
 * NoClassDefFoundError: Failed resolution of: Lkotlin/jvm/internal/Intrinsics;
 *   at FixtureActivity$Recorder.tapped
 * DexPathList[[zip file ".../com.jarvis.os.test-.../base.apk"]]
 * </pre>
 *
 * Kotlin emits calls into {@code kotlin.jvm.internal.Intrinsics} for its null checks, and this
 * Activity runs in a process whose dex path is <b>the test APK alone</b>. Adding
 * {@code androidTestImplementation("kotlin-stdlib")} did nothing, because AGP strips from the
 * test APK any dependency already present in the app APK — normally correct, since an
 * instrumentation test usually shares the app's classloader, but not here. Java emits no such
 * calls, so the dependency stops existing rather than needing to be packaged.
 *
 * <h2>Why the tap is recorded in the UI and not in a static field</h2>
 *
 * The Kotlin version kept a {@code Recorder} object with a {@code @Volatile} field. That could
 * never have worked either, for a reason the {@code Intrinsics} crash was hiding: <b>the fixture
 * and the test are in different processes.</b> The instrumentation runs in {@code com.jarvis.os}
 * — it reads {@code ScreenControlService.instance}, a static that only exists there — while the
 * crash above shows the fixture in {@code com.jarvis.os.test}. A static written here is
 * invisible over there, so fixing only the crash would have turned it into
 * {@code expected:<Add to cart> but was:<null>}: the same failure message, now with nothing
 * obviously wrong to find.
 *
 * <p>So the tap is recorded where the test can actually see it — in this Activity's own visible
 * text, read back through the accessibility tree. That crosses processes by design, and it is a
 * stronger claim than a shared field ever was: it proves the tap landed <i>and</i> that the
 * result is observable through the very mechanism JARVIS uses to see a screen.
 *
 * <h2>Deliberately plain views</h2>
 *
 * No Compose, no animation. The emulator tier has a demonstrated capacity ceiling —
 * {@code JarvisAppUiTest} was removed after its animated {@code HudOrb} reliably took the GitHub
 * VM down with "device offline" across three fresh emulators. A fixture whose whole job is to
 * expose a few labelled controls has no business rendering anything expensive.
 */
public class FixtureActivity extends Activity {

    /** A control whose visible text is exactly what a {@code <<TAP|…>>} would name. */
    public static final String TAP_TARGET = "Add to cart";

    /**
     * A decoy sharing a word with the target, so the probe also shows the scorer preferring an
     * exact match over a partial one — the "tapped the wrong thing" class that
     * {@code ScreenMatchTest} covers in pure form.
     */
    public static final String DECOY = "Add to wishlist";

    /** A real editable field, so a later test can drive {@code <<TYPE>>} against it. */
    public static final String FIELD_HINT = "Search for groceries";

    /** The package the fixture runs in — NOT {@code com.jarvis.os}. */
    public static final String FIXTURE_PACKAGE = "com.jarvis.os.test";

    /** Status text before anything has been tapped. */
    public static final String STATUS_IDLE = "fixture ready, nothing tapped";

    /**
     * Prefix of the status text once a control has been clicked. The test finds the node
     * carrying this and reads the label after it, which is how the result crosses the process
     * boundary.
     */
    public static final String TAPPED_PREFIX = "fixture tapped: ";

    private static final int PAD = 48;

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(PAD, PAD, PAD, PAD);

        status = new TextView(this);
        status.setText(STATUS_IDLE);
        // Set explicitly so the node carries the text even on a platform that would
        // otherwise leave a plain TextView without a content description.
        status.setContentDescription(STATUS_IDLE);
        root.addView(status);

        root.addView(button(TAP_TARGET));
        root.addView(button(DECOY));

        EditText field = new EditText(this);
        field.setHint(FIELD_HINT);
        field.setContentDescription(FIELD_HINT);
        root.addView(field);

        setContentView(root);
    }

    private Button button(final String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // String.concat rather than `+`: at source level 17 the compiler
                // lowers `+` to an invokedynamic against StringConcatFactory, which
                // D8 has to desugar for minSdk 26. It does — but this file exists
                // because a *packaging* assumption about the test APK turned out to
                // be wrong twice, and a plain method call needs no assumption at all.
                String line = TAPPED_PREFIX.concat(label);
                status.setText(line);
                // Updating the description too, so the value is on the node whichever of the
                // two the accessibility tree reports for this widget.
                status.setContentDescription(line);
            }
        });
        return button;
    }
}
