package com.jarvis.os.desktop

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * JARVIS draws its own window controls instead of Windows' blue title bar (user, 2026-10-02:
 * "see how Claude doesn't have the blue bar"). An undecorated Java window loses everything the
 * OS normally gives a frame — edge resizing, Aero Snap, a correct maximise — so this puts it
 * back, the way every frameless Windows app (Electron, Chromium) does:
 *
 *  1. Give the window the resize/min/max/sysmenu styles an undecorated AWT frame lacks.
 *  2. Subclass its window procedure: `WM_NCCALCSIZE` says "the whole window is client area"
 *     (no native frame is drawn); `WM_NCHITTEST` tells Windows which strip is the draggable
 *     caption and where the resize edges are, so dragging, double-click-to-maximise, snapping
 *     and edge resizing are the OS's own behaviour, not re-implemented; `WM_GETMINMAXINFO`
 *     keeps a maximised window inside the monitor's work area (a borderless window otherwise
 *     maximises over the taskbar, and Windows then hides the taskbar as if it were full-screen).
 *  3. Compose draws into a CHILD window (`SunAwtCanvas`) that covers the whole client area, and
 *     Windows asks the child, not the frame, what a click means — so the child is subclassed too
 *     and answers "transparent" wherever the frame should decide (caption, edges). Without this
 *     the strip can't drag and only a 1-pixel border can resize.
 *
 * Windows only; everywhere else [install] does nothing. The pure [Geometry] below is what the
 * unit tests cover — no window is needed to test where the edges are.
 */
object WindowChrome {

    /** The strip across the top, in dp: the caption area, and the height the app's content starts below. */
    const val TITLE_BAR_DP = 32

    /** Width of one window-control button (minimise / maximise / close), in dp. */
    const val BUTTON_DP = 46

    /** Controls take the right-hand end of the title strip; they must stay clickable, so they are NOT caption. */
    const val CONTROLS_DP = BUTTON_DP * 3

    private const val RESIZE_EDGE_DP = 6
    private const val RESIZE_CORNER_DP = 14

    /** Pure geometry — where the edges, the draggable caption and the controls are. Tested. */
    object Geometry {
        const val HTTRANSPARENT = -1
        const val HTCLIENT = 1
        const val HTCAPTION = 2
        const val HTLEFT = 10
        const val HTRIGHT = 11
        const val HTTOP = 12
        const val HTTOPLEFT = 13
        const val HTTOPRIGHT = 14
        const val HTBOTTOM = 15
        const val HTBOTTOMLEFT = 16
        const val HTBOTTOMRIGHT = 17

        /**
         * What Windows should treat the point ([x], [y]) — relative to the window's own top-left,
         * in physical pixels — as. [maximized] windows can't be resized by their edges.
         */
        fun hitTest(
            x: Int, y: Int, width: Int, height: Int,
            edge: Int, corner: Int, titleHeight: Int, controlsWidth: Int, maximized: Boolean,
        ): Int {
            if (x < 0 || y < 0 || x >= width || y >= height) return HTCLIENT
            if (!maximized) {
                val left = x < edge
                val right = x >= width - edge
                val top = y < edge
                val bottom = y >= height - edge
                val nearLeft = x < corner
                val nearRight = x >= width - corner
                val nearTop = y < corner
                val nearBottom = y >= height - corner
                when {
                    (top && nearLeft) || (left && nearTop) -> return HTTOPLEFT
                    (top && nearRight) || (right && nearTop) -> return HTTOPRIGHT
                    (bottom && nearLeft) || (left && nearBottom) -> return HTBOTTOMLEFT
                    (bottom && nearRight) || (right && nearBottom) -> return HTBOTTOMRIGHT
                    left -> return HTLEFT
                    right -> return HTRIGHT
                    top -> return HTTOP
                    bottom -> return HTBOTTOM
                }
            }
            return if (y < titleHeight && x < width - controlsWidth) HTCAPTION else HTCLIENT
        }
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    // ── Win32 bits JNA's own User32 binding doesn't cover ──────────────────────────────────────
    private interface UserExt : StdCallLibrary {
        fun GetDpiForWindow(hwnd: WinDef.HWND): Int
        fun IsZoomed(hwnd: WinDef.HWND): Boolean
    }

    @Structure.FieldOrder("left", "right", "top", "bottom")
    class Margins(@JvmField var left: Int = 0, @JvmField var right: Int = 0, @JvmField var top: Int = 0, @JvmField var bottom: Int = 0) : Structure()

    private interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: WinDef.HWND, margins: Margins): Int
    }

    private val ext: UserExt? by lazy { runCatching { Native.load("user32", UserExt::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull() }
    private val dwm: DwmApi? by lazy { runCatching { Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull() }

    private const val GWL_STYLE = -16
    private const val GWL_WNDPROC = -4
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WS_SYSMENU = 0x00080000
    private const val SWP_FLAGS = 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020 // NOSIZE|NOMOVE|NOZORDER|NOACTIVATE|FRAMECHANGED
    private const val WM_GETMINMAXINFO = 0x0024
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWCP_ROUND = 2

    /** Held so the JNA callbacks are never garbage-collected while Windows still points at them. */
    private val keepAlive = mutableListOf<Any>()
    @Volatile private var installedOn = 0L

    /** What the point in [lParam] (screen coordinates, as in WM_NCHITTEST) means for the top-level window [top]. */
    private fun hitFor(top: WinDef.HWND, lParam: WinDef.LPARAM): Int {
        val sx = (lParam.toLong() and 0xFFFF).toShort().toInt()
        val sy = ((lParam.toLong() shr 16) and 0xFFFF).toShort().toInt()
        val rect = WinDef.RECT()
        User32.INSTANCE.GetWindowRect(top, rect)
        val scale = (ext?.GetDpiForWindow(top) ?: 96) / 96.0
        return Geometry.hitTest(
            sx - rect.left, sy - rect.top, rect.right - rect.left, rect.bottom - rect.top,
            edge = (RESIZE_EDGE_DP * scale).toInt(),
            corner = (RESIZE_CORNER_DP * scale).toInt(),
            titleHeight = (TITLE_BAR_DP * scale).toInt(),
            controlsWidth = (CONTROLS_DP * scale).toInt(),
            maximized = ext?.IsZoomed(top) == true,
        )
    }

    private val hookedChildren = mutableSetOf<Long>()
    /** Hands a message to the window procedure we replaced (or to Windows' default if a message sneaks in before we know it). */
    private fun forward(previous: Pointer?, h: WinDef.HWND, msg: Int, w: WinDef.WPARAM, l: WinDef.LPARAM): WinDef.LRESULT =
        if (previous == null) User32.INSTANCE.DefWindowProc(h, msg, w, l) else User32.INSTANCE.CallWindowProc(previous, h, msg, w, l)

    /**
     * Windows asks the CHILD Compose draws into what a click means. Wherever the frame should decide (the
     * caption strip and the resize edges) the child says "transparent", and the question goes to the frame.
     * The child can be created after [install] ran, so this is safe to call repeatedly: it hooks any child
     * it has not hooked yet.
     */
    fun attachChildren(topHandle: Long) {
        if (!isWindows || topHandle == 0L) return
        runCatching {
            val u = User32.INSTANCE
            val top = WinDef.HWND(Pointer(topHandle))
            val children = mutableListOf<WinDef.HWND>()
            u.EnumChildWindows(top, WinUser.WNDENUMPROC { child, _ -> children += child; true }, null)
            for (child in children) {
                val id = Pointer.nativeValue(child.pointer)
                if (!hookedChildren.add(id)) continue
                var previousChild: Pointer? = null
                val childProc = object : WinUser.WindowProc {
                    override fun callback(h: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
                        try {
                            if (msg == WM_NCHITTEST && hitFor(top, lParam) != Geometry.HTCLIENT) return WinDef.LRESULT(Geometry.HTTRANSPARENT.toLong())
                        } catch (_: Throwable) {
                        }
                        return forward(previousChild, h, msg, wParam, lParam)
                    }
                }
                keepAlive += childProc
                previousChild = u.SetWindowLongPtr(child, GWL_WNDPROC, CallbackReference.getFunctionPointer(childProc))
            }
        }
    }

    /**
     * Turns the (already undecorated) window [handle] into a frameless window that still behaves like
     * a normal one. Safe to call twice; returns whether the hook is in place.
     */
    fun install(handle: Long): Boolean {
        if (!isWindows || handle == 0L) return false
        if (installedOn == handle) return true
        return runCatching {
            val u = User32.INSTANCE
            val top = WinDef.HWND(Pointer(handle))
            u.SetWindowLong(top, GWL_STYLE, u.GetWindowLong(top, GWL_STYLE) or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU)

            // ── the frame itself ──
            var previousTop: Pointer? = null
            val topProc = object : WinUser.WindowProc {
                override fun callback(h: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
                    try {
                        when (msg) {
                            // No native frame: the client area IS the window.
                            WM_NCCALCSIZE -> return WinDef.LRESULT(0)
                            WM_NCHITTEST -> return WinDef.LRESULT(hitFor(h, lParam).toLong())
                            WM_GETMINMAXINFO -> {
                                // Let AWT fill in its own minimum size first, then keep a maximised window
                                // inside the monitor's WORK area (not over the taskbar).
                                val result = forward(previousTop, h, msg, wParam, lParam)
                                val monitor = u.MonitorFromWindow(h, MONITOR_DEFAULTTONEAREST)
                                val info = WinUser.MONITORINFO()
                                if (monitor != null && u.GetMonitorInfo(monitor, info).booleanValue()) {
                                    val mmi = Pointer(lParam.toLong())
                                    mmi.setInt(8, info.rcWork.right - info.rcWork.left)    // ptMaxSize
                                    mmi.setInt(12, info.rcWork.bottom - info.rcWork.top)
                                    mmi.setInt(16, info.rcWork.left - info.rcMonitor.left) // ptMaxPosition (relative to the monitor)
                                    mmi.setInt(20, info.rcWork.top - info.rcMonitor.top)
                                }
                                return result
                            }
                        }
                    } catch (_: Throwable) {
                        // Never let an exception cross back into Windows; fall through to normal handling.
                    }
                    return forward(previousTop, h, msg, wParam, lParam)
                }
            }
            keepAlive += topProc
            previousTop = u.SetWindowLongPtr(top, GWL_WNDPROC, CallbackReference.getFunctionPointer(topProc))

            attachChildren(handle)

            // A hairline of DWM frame keeps the drop shadow a frameless window would otherwise lose, and
            // asks Windows 11 for its rounded corners. Both are cosmetic: a failure here changes nothing else.
            dwm?.DwmExtendFrameIntoClientArea(top, Margins(1, 1, 1, 1))
            dwm?.DwmSetWindowAttribute(top, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(DWMWCP_ROUND), 4)
            u.SetWindowPos(top, null, 0, 0, 0, 0, SWP_FLAGS)
            installedOn = handle
            true
        }.getOrDefault(false)
    }

    /** Tints the 1-px window border (Windows 11) — the theme's colour instead of the system accent. [rgb] = 0xRRGGBB. */
    fun setBorderColor(handle: Long, rgb: Int) {
        if (!isWindows || handle == 0L) return
        val colorRef = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)   // COLORREF is 0x00BBGGRR
        runCatching { dwm?.DwmSetWindowAttribute(WinDef.HWND(Pointer(handle)), DWMWA_BORDER_COLOR, IntByReference(colorRef), 4) }
    }
}
