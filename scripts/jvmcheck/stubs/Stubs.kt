// The smallest set of declarations that lets the non-UI sources type-check
// off-device. Everything real comes from Robolectric's `android-all` jar; these
// are only the pieces that live in libraries hosted on dl.google.com, which the
// network policy refuses.
//
// Deliberately minimal and deliberately dumb. They are never executed — the
// tests that run here are pure Kotlin — so a stub only has to have the right
// SHAPE. If one ever needs real behaviour, that is the signal the thing under
// test belongs in CI instead.

package androidx.compose.runtime

interface State<out T> {
    val value: T
}

interface MutableState<T> : State<T> {
    override var value: T
}

fun <T> mutableStateOf(initial: T): MutableState<T> = object : MutableState<T> {
    override var value: T = initial
}

/**
 * The unboxed float state, and the reason it is here.
 *
 * `AssistantEngine` holds the microphone level in one of these rather than in
 * `VoiceUiState`. It has to be a `FloatState` and not a `MutableState<Float>`
 * because the level changes many times a second and boxing a Float on every
 * change is exactly the kind of per-sensor-tick allocation the split was made to
 * remove.
 *
 * `FloatState : State<Float>` in the real runtime, which is what lets the engine
 * expose it as `State<Float>` without the caller knowing or caring.
 */
interface FloatState : State<Float> {
    val floatValue: Float
    override val value: Float get() = floatValue
}

interface MutableFloatState : FloatState, MutableState<Float> {
    override var floatValue: Float
    override var value: Float
        get() = floatValue
        set(v) { floatValue = v }
}

fun mutableFloatStateOf(initial: Float): MutableFloatState = object : MutableFloatState {
    override var floatValue: Float = initial
}
