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
