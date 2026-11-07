package dev.unifieddesktop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Phase-6 desktop shell, shown inside the Phase-5 `Presentation` on our own private
 * display. Everything here is our own UI: a wallpaper, movable/resizable windows, a taskbar
 * with a start button and one button per window, a start menu, and our own pointer cursor.
 *
 * Input comes from the PC as REAL mouse MotionEvents (SOURCE_MOUSE) dispatched to this root
 * view, so normal Android hit-testing routes clicks/typing to the views that want them; the
 * frame chrome (title bar, resize handles) is handled by [ShellWindowView] itself. The
 * desktop-sized cursor is drawn by [CursorView] on top of everything.
 *
 * Windows live in [model] (`ShellModel`, pure logic, unit-tested); this class only mirrors
 * the model into child views.
 */
class ShellRoot(
    context: Context,
    /** D8: the desktop the PC asked for; the shell lays out at exactly this size. */
    val desktopW: Int = Phase5Desktop.W,
    val desktopH: Int = Phase5Desktop.H,
) : FrameLayout(context) {

    companion object {
        const val TAG = "Phase6"
        const val TASKBAR_H = Theme.TASKBAR_H
        const val TITLE_H = Theme.TITLE_H
        /** Size of the corner square [flashFrame] dirties: enough to ask for a frame, no more. */
        const val FLASH_PROBE_PX = 8
    }

    val model = ShellModel(desktopW, desktopH, TASKBAR_H)

    /** Fired whenever the shell's state changes, for the link's stats line. */
    var onChanged: (() -> Unit)? = null

    private val wallpaper = WallpaperView(context)
    private val cursorView = CursorView(context)
    private val taskbar = TaskbarView(context)
    private val startMenu = StartMenuView(context)
    private val contextMenu = DesktopContextMenu(context)
    private val frames = HashMap<Int, ShellWindowView>()

    /** D12: one toast view above every window; 2 s auto-hide, latest message wins. */
    private val toastView = TextView(context).apply {
        visibility = GONE
        setTextColor(Theme.TEXT)
        Theme.px(this, Theme.SMALL)
        setPadding(18, 8, 18, 8)
        background = shellRounded(Theme.alpha(Theme.SURFACE_RAISED, 245), Theme.BORDER, 8f)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private var toastHide: Runnable? = null
    private var lastBatteryPct = 100
    private var batteryLowShown = false

    /** D14: the one-time welcome card with three removable shortcuts. */
    private val welcomeCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14, 10, 14, 12)
        background = shellRounded(Theme.alpha(Theme.SURFACE_RAISED, 245), Theme.BORDER, 10f)
        visibility = GONE
    }
    private val welcomeRows = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * T-005/T-006: a confirmation/name modal drawn by the desktop itself. The Files delete
     * confirmation used `AlertDialog` with the private display's view context, which threw
     * `BadTokenException: token null is not valid`, killed the UI thread and froze the whole
     * desktop (tester T-005). A system dialog also lands on the phone screen, not the PC
     * desktop. This panel is an ordinary child of the shell, so it needs no window token.
     */
    private val modalPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 14, 18, 14)
        background = shellRounded(Theme.alpha(Theme.SURFACE_RAISED, 250), Theme.BORDER, 10f)
        visibility = GONE
        isClickable = true
        elevation = 12f
    }
    private val modalTitle = TextView(context).apply {
        setTextColor(Theme.TEXT)
        Theme.px(this, Theme.HEADING)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val modalMessage = TextView(context).apply {
        setTextColor(Theme.TEXT_SECONDARY)
        Theme.px(this, Theme.SMALL)
        setPadding(0, 6, 0, 10)
    }
    private val modalField = EditText(context).apply {
        setTextColor(Theme.TEXT)
        setHintTextColor(Theme.TEXT_DIM)
        Theme.px(this, Theme.BODY)
        setBackground(shellRounded(Theme.SURFACE_SUNKEN, Theme.BORDER, 6f))
        setPadding(10, 6, 10, 6)
        visibility = GONE
        isSingleLine = true
        // The PC keyboard is the only input here; never raise the phone's soft keyboard.
        showSoftInputOnFocus = false
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }
    private val modalButtons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    private val modalCancelBtn = TextView(context).apply {
        text = "Cancel"
        setTextColor(Theme.TEXT_SECONDARY)
        Theme.px(this, Theme.SMALL)
        setPadding(14, 7, 14, 7)
        isClickable = true
        background = shellRounded(Theme.SURFACE_SUNKEN, Theme.BORDER, 6f)
        setOnClickListener { dismissModal() }
    }
    private val modalOkBtn = TextView(context).apply {
        setTextColor(Color.WHITE)
        Theme.px(this, Theme.SMALL)
        setPadding(16, 7, 16, 7)
        isClickable = true
        background = shellRounded(Theme.ACCENT, Theme.BORDER, 6f)
        setOnClickListener { submitModal() }
    }
    private var modalYes: (() -> Unit)? = null
    private var modalCancelled: (() -> Unit)? = null

    /** D12: phone battery low, once per desktop session, only while discharging. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in listOf(
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
            )
            if (level < 0 || scale <= 0) return
            val pct = (level * 100 / scale).coerceIn(0, 100)
            lastBatteryPct = pct
            if (!charging && pct <= 20 && !batteryLowShown) {
                batteryLowShown = true
                toast(Toasts.batteryLow(pct))
            }
        }
    }

    /** Remembered window rectangles, per app (UX_AUDIT D5), in the shell's own preferences. */
    private val prefs by lazy { context.getSharedPreferences("shell", Context.MODE_PRIVATE) }

    /**
     * Harness-only typing probe (Phase 12 round 2). The Phase-5 latency probe reports the
     * moment the key "flash" is really drawn; in shell mode only the Test window's own flash
     * area did that, so typing in the Browser, VS Code or Notepad++ produced no LAT line at
     * all. This 28x28 px square at the bottom-right of the desktop flashes white on every
     * key-down while it is armed (`p6:probe on`), which gives the probe the same draw
     * timestamp for a key typed into ANY focused window. It is GONE unless armed, so the
     * normal UI is unchanged.
     */
    private val probeChip = object : View(context) {
        var armed = false
        private var untilMs = 0L
        private var count = 0
        private var reported = 0
        var onDrawn: ((Int, Long) -> Unit)? = null

        fun arm(on: Boolean) {
            armed = on
            visibility = if (on) VISIBLE else GONE
            invalidate()
        }

        fun flash() {
            if (!armed) {
                P7Log.say(context, "P5PROBE chip flash SKIPPED (armed=false)")
                return
            }
            count++
            P7Log.say(context, "P5PROBE chip flash count=$count")
            untilMs = SystemClock.uptimeMillis() + 120
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val flashing = SystemClock.uptimeMillis() < untilMs
            c.drawColor(if (flashing) Color.WHITE else Color.rgb(45, 45, 60))
            if (flashing && reported != count) {
                reported = count
                P7Log.say(context, "P5PROBE chip drawn count=$count")
                onDrawn?.invoke(count, System.nanoTime())
            }
        }
    }.apply {
        visibility = GONE
        layoutParams = FrameLayout.LayoutParams(28, 28).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = 18
            bottomMargin = TASKBAR_H + 18
        }
    }

    private var cursorX = desktopW / 2
    private var cursorY = desktopH / 2

    private val heldKeys = HashSet<Int>()
    private val heldButtons = HashSet<Int>()
    private var pcPointerVisible = true
    private var keysAllTime = 0
    private var keyFlashes = 0
    private var buttonsAllTime = 0
    private var keyAtFocused = 0

    init {
        // Layer order, bottom to top: wallpaper, windows, taskbar, start menu, cursor. The
        // taskbar is re-raised after every window change, so it is always above windows
        // (UX_AUDIT D4/A1: a maximized window used to cover the clock).
        addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        taskbar.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, TASKBAR_H).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        addView(taskbar)
        addView(startMenu)
        addView(contextMenu)
        buildWelcomeCard()
        addView(welcomeCard, LayoutParams(470, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 24
            topMargin = 24
        })
        toastView.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 16
        }
        addView(toastView)
        modalPanel.addView(modalTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        modalPanel.addView(modalMessage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        modalPanel.addView(modalField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 2 })
        modalButtons.addView(modalCancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = 8 })
        modalButtons.addView(modalOkBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        modalPanel.addView(modalButtons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 8 })
        addView(modalPanel, LayoutParams(420, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        addView(probeChip)
        addView(cursorView)
        startMenu.visibility = GONE
        contextMenu.visibility = GONE
        runCatching {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.onFailure { P7Log.say(context, "D12 battery receiver failed: ${it.javaClass.simpleName}") }
        contextMenu.onPick = { id ->
            when (id) {
                "appcenter" -> openApp(App.APPCENTER)
                "files" -> openApp(App.FILES)
                "wallpaper", "settings" -> openApp(App.SETTINGS)
            }
        }
        taskbar.onStart = { toggleStartMenu() }
        taskbar.onWindowButton = { id -> onTaskbarWindowClick(id) }
        taskbar.iconFor = { id ->
            frames[id]?.let { it.app.iconKind() to it.app.iconColor() }
                ?: (DeskIcon.WINDOW to Theme.ACCENT)
        }
        startMenu.onLaunch = { app -> openApp(app); closeStartMenu() }
        startMenu.onLaunchInstalled = { id -> openCatalogApp(id); closeStartMenu() }
        SettingsDispatch.onWallpaperPick = { i -> setWallpaper(i) }
    }

    /**
     * T-005/T-006: ask the user to confirm an action. The callback runs only on "yes";
     * Cancel, Esc, a click outside or a right-click closes it with nothing done.
     */
    fun confirm(title: String, message: String, yesLabel: String, onYes: () -> Unit) {
        openModal(title, message, "", yesLabel, false, onYes, null)
    }

    /** T-006: ask for a name (folder create / rename) with the PC keyboard. */
    fun prompt(
        title: String,
        initial: String,
        yesLabel: String,
        onCancel: (() -> Unit)? = null,
        onYes: (String) -> Unit,
    ) {
        openModal(title, null, initial, yesLabel, true, { onYes(modalField.text.toString()) }, onCancel)
    }

    fun modalOpen(): Boolean = modalPanel.visibility == View.VISIBLE

    fun dismissModal() {
        if (!modalOpen()) return
        modalPanel.visibility = View.GONE
        modalField.visibility = View.GONE
        modalYes = null
        val cancel = modalCancelled
        modalCancelled = null
        invalidate()
        cancel?.invoke()
    }

    private fun submitModal() {
        val yes = modalYes ?: return
        modalYes = null
        modalCancelled = null
        modalPanel.visibility = View.GONE
        modalField.visibility = View.GONE
        invalidate()
        yes()
    }

    private fun openModal(
        title: String,
        message: String?,
        initial: String,
        yesLabel: String,
        withField: Boolean,
        onYes: () -> Unit,
        onCancel: (() -> Unit)?,
    ) {
        hideContextMenu()
        if (startMenuOpen()) closeStartMenu()
        modalTitle.text = title
        modalMessage.text = message ?: ""
        modalMessage.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        modalField.visibility = if (withField) View.VISIBLE else View.GONE
        modalField.setText(initial)
        modalOkBtn.text = yesLabel
        modalYes = onYes
        modalCancelled = onCancel
        modalPanel.visibility = View.VISIBLE
        modalPanel.bringToFront()
        probeChip.bringToFront()
        cursorView.bringToFront()
        if (withField) {
            modalField.requestFocus()
            modalField.setSelection(modalField.text.length)
        }
        requestLayout()
        invalidate()
        P7Log.say(context, "P7SHELL modal \"$title\" field=$withField")
    }

    /** D12: show one line above every window for [ms], replacing the previous toast. */
    fun toast(text: String, ms: Long = 2000) {
        post {
            toastView.text = text
            toastView.visibility = VISIBLE
            toastView.bringToFront()
            cursorView.bringToFront()
            toastHide?.let { removeCallbacks(it) }
            val hide = Runnable { toastView.visibility = GONE }
            toastHide = hide
            postDelayed(hide, ms)
            P7Log.say(context, "D12 toast \"$text\"")
        }
    }

    /** D14: build the one-time card; shortcuts are removable one by one. */
    private fun buildWelcomeCard() {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Welcome"
            setTextColor(Theme.TEXT)
            Theme.px(this, Theme.HEADING)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "×"
            setTextColor(Theme.TEXT_DIM)
            Theme.px(this, Theme.HEADING)
            gravity = Gravity.CENTER
            setPadding(12, 0, 4, 4)
            setOnClickListener { dismissWelcome() }
        }, LinearLayout.LayoutParams(36, 36))
        welcomeCard.addView(header)
        welcomeCard.addView(TextView(context).apply {
            text = "Open apps from Start. Press Exit to get your PC back."
            setTextColor(Theme.TEXT_SECONDARY)
            Theme.px(this, Theme.SMALL)
            setPadding(0, 2, 0, 8)
        })
        welcomeCard.addView(welcomeRows)
        welcomeCard.addView(TextView(context).apply {
            text = "Long-press a shortcut to remove it."
            setTextColor(Theme.TEXT_DIM)
            Theme.px(this, Theme.CAPTION)
            setPadding(0, 8, 0, 0)
        })
        refreshWelcomeShortcuts()
        welcomeCard.visibility = if (prefs.getBoolean("welcome_done", false)) GONE else VISIBLE
    }

    private fun refreshWelcomeShortcuts() {
        welcomeRows.removeAllViews()
        val removed = prefs.getStringSet("welcome_removed", emptySet())?.toSet() ?: emptySet()
        for (sc in DesktopShortcuts.visible(removed)) {
            welcomeRows.addView(TextView(context).apply {
                text = sc.label
                setTextColor(Theme.TEXT)
                Theme.px(this, Theme.SMALL)
                gravity = Gravity.CENTER
                background = shellRounded(Theme.SURFACE_SUNKEN, Theme.BORDER, 8f)
                setOnClickListener {
                    runCatching { openApp(App.valueOf(sc.app)) }
                }
                setOnLongClickListener {
                    val next = DesktopShortcuts.remove(removed, sc.id)
                    prefs.edit().putStringSet("welcome_removed", next).apply()
                    refreshWelcomeShortcuts()
                    toast(Toasts.SHORTCUT_REMOVED)
                    true
                }
            }, LinearLayout.LayoutParams(104, 34).apply { marginEnd = 6 })
        }
        if (welcomeRows.childCount == 0) {
            welcomeRows.addView(TextView(context).apply {
                text = "All shortcuts removed"
                setTextColor(Theme.TEXT_DIM)
                Theme.px(this, Theme.SMALL)
            })
        }
    }

    private fun dismissWelcome() {
        prefs.edit().putBoolean("welcome_done", true).apply()
        welcomeCard.visibility = GONE
    }

    /** Change the desktop wallpaper (Settings or the desktop menu) and remember it. */
    fun setWallpaper(index: Int) {
        val i = index.coerceIn(0, Wallpaper.styles.size - 1)
        wallpaper.index = i
        Wallpaper.save(context, i)
        runCatching { SettingsDispatch.wallpaperChanged(i) }
    }

    fun wallpaperIndex(): Int = wallpaper.index

    // ------------------------------------------------------------------ windows

    enum class App { APPCENTER, FILES, BROWSER, SETTINGS, TEST, X11, VSCODE, ANDROID;
        fun title(): String = when (this) {
            APPCENTER -> "App Center"
            FILES -> "Files"; BROWSER -> "Browser"; SETTINGS -> "Settings"; TEST -> "Test"
            X11 -> X11Title.BASE
            VSCODE -> "VS Code"
            ANDROID -> "Android app"
        }
    }

    /** The live App Center content while its window exists (the Phase-8 harness drives it). */
    var appCenter: AppCenterView? = null
        private set

    /**
     * Open (or re-focus) an app window. [guest] names the hidden Android app for ANDROID;
     * [title] names the app inside an X11 window (B-087), reusing the one X11 window.
     */
    fun openApp(app: App, guest: String? = null, title: String? = null): ShellWindowView {
        // B-090: the Android window hosts exactly one guest. Opening a different package
        // must not reuse the old TextureView/display, and an uninstall must close it so the
        // next import launches against a fresh display instead of a black stale surface.
        if (app == App.ANDROID) {
            frames.values.firstOrNull {
                it.app == App.ANDROID && !it.guest.equals(guest, ignoreCase = true)
            }?.let { closeWindow(it.win.id) }
        }
        val existing = frames.values.firstOrNull { it.app == app }
        if (existing != null) {
            if (app == App.X11) {
                val t = title?.takeIf { it.isNotBlank() } ?: X11Title.BASE
                model.rename(existing.win.id, t)
            }
            model.focus(existing.win.id)
            syncViews()
            return existing
        }
        val initialTitle = if (app == App.X11) {
            title?.takeIf { it.isNotBlank() } ?: X11Title.BASE
        } else {
            app.title()
        }
        val workH = desktopH - TASKBAR_H
        val defW = (desktopW * 0.70f).toInt()
        val defH = (workH * 0.70f).toInt()
        val saved = rememberedRect(app)
        val win = if (saved != null) {
            model.open(initialTitle, saved[2], saved[3], saved[0], saved[1])
        } else {
            // D5: centred in the work area at about 70 %; a small cascade keeps a second
            // window from covering the first exactly.
            val n = model.size()
            val cx = ((desktopW - defW) / 2 + n * 24).coerceIn(0, (desktopW - defW).coerceAtLeast(0))
            val cy = ((workH - defH) / 2 + n * 20).coerceIn(0, (workH - defH).coerceAtLeast(0))
            model.open(initialTitle, defW, defH, cx, cy)
        }
        val frame = ShellWindowView(context, win, app, guest, desktopW, desktopH)
        frame.onDragBy = { id, dx, dy, edges -> frameDrag(id, dx, dy, edges) }
        frame.onDragEnd = { id, x, y -> frameDragEnd(id, x, y) }
        frame.onClose = { closeWindow(it) }
        frame.onMinimize = { minimizeWindow(it) }
        frame.onMaximize = { id -> model.toggleMaximize(id); syncViews() }
        if (frame.content is TestWindowContent) {
            testContent = frame.content as TestWindowContent
            flashListener?.let { testContent?.setFlashListener(it) }
        }
        if (frame.content is AppCenterView) appCenter = frame.content as AppCenterView
        frames[win.id] = frame
        addView(frame, indexOfChild(startMenu)) // under the start menu and the cursor
        syncViews()
        return frame
    }

    private var testContent: TestWindowContent? = null

    /** The live X11 window content, if that window is open (B-077 reconnect proof). */
    fun x11Content(): X11WindowContent? =
        frames.values.firstNotNullOfOrNull { it.content as? X11WindowContent }

    /** Phase 11 D-e: the live Browser WebView. The injected shim checks for itself, so
     *  pushing into a page without it is a no-op (`window.udCam && ...`). */
    fun cameraWeb(): android.webkit.WebView? =
        frames.values.firstNotNullOfOrNull { (it.content as? BrowserView)?.web }

    /** The catalog, read once; the start menu and the App Center share these entries. */
    private val catalog: List<CatalogApp> by lazy {
        try {
            CatalogLoader.load(context)
        } catch (t: Throwable) {
            P7Log.say(context, "APPCTR catalog fail ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    /** B-083: the one launch path for an installed/catalog app (start menu and App Center). */
    private fun packageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun openCatalogApp(id: String): String {
        val app = catalog.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: return "no catalog app [$id]"
        val v = Catalog.pick(app) ?: return "no version for ${app.name}"
        if (!Catalog.isUsable(v)) return "Not yet: ${v.note.ifEmpty { v.rating }}"
        // B-109: a Windows app with no record and no executable must not open an empty X11
        // frame. The App Center then shows "Not installed - Install?" and the Install button
        // is the action. The shipped Notepad++ portable counts as installed data.
        if (v.kind == "windows") {
            val portable = app.id == "notepadpp" && Phase2Wine.nppExe(context).isFile
            val exe = Phase2Wine.installedExe(context, app.id) != null
            if (AppLaunch.needsInstall(app.id, Installed.version(context, app.id), exe, portable)) {
                P7Log.say(context, "APPCTR open ${app.id}: not installed")
                return AppLaunch.NOT_INSTALLED
            }
        }
        return applyTarget(OpenRoute.of(app, v))
    }

    private fun applyTarget(t: OpenTarget): String = when (t.action) {
        "files" -> { openApp(App.FILES); "opened Files" }
        "browser" -> {
            val w = openApp(App.BROWSER)
            (w.content as? BrowserView)?.openUrl(t.url)
            "opened Browser ${t.url}"
        }
        "settings" -> { openApp(App.SETTINGS); "opened Settings" }
        "vscode" -> { openApp(App.VSCODE); "opened VS Code" }
        "x11" -> {
            openApp(App.X11, title = t.title)
            val name = AppLaunch.appName(t.title)
            // B-109: a cold Wine boot takes seconds; report "Starting ..." until the X11
            // window really maps, instead of claiming the app opened immediately.
            Thread {
                Phase2Wine.openCatalogApp(context, t.catalogId)
                val mapped = Phase2X11.waitForCatalogWindow(context, t.catalogId, 45_000)
                P7Log.say(context, "APPCTR map id=${t.catalogId} mapped=$mapped")
                appCenter?.post {
                    appCenter?.setStatus(
                        if (mapped) AppLaunch.ready(name) else "Still starting $name... (Wine boot)",
                    )
                }
            }.start()
            AppLaunch.starting(name)
        }
        "android" -> {
            if (t.guest.isEmpty()) "no package name"
            else if (!packageInstalled(t.guest)) {
                // B-092: no guest window for a package that is not on the phone. The Browser
                // Play page is the only truthful target; Install/Open share this policy.
                val fallback = AndroidOpen.browserFallback(t)
                val w = openApp(App.BROWSER)
                (w.content as? BrowserView)?.openUrl(fallback.url)
                val name = catalog.firstOrNull {
                    it.id.equals(t.catalogId, ignoreCase = true)
                }?.name ?: t.guest
                "$name is not installed; opened its Google Play page."
            } else {
                openApp(App.ANDROID, t.guest)
                "opening Android ${t.guest}"
            }
        }
        else -> "no window for [${t.title}]"
    }

    /** Latency-probe hook: the Test window's flash reports its draw time (Phase-5 method). */
    private var flashListener: ((Int, Long) -> Unit)? = null

    /**
     * B-122: the flash time of the desktop frame carrying a key-down. In shell mode the only
     * draw source used to be the harness probe chip (armed by hand), so a key typed into a
     * real window (Browser, VS Code, Notepad++) never reported a draw time and the latency
     * probe never fired. This asks for one shell frame on key-down and reports when that
     * frame is composed; it draws no extra pixels, so the UI is unchanged.
     *
     * The frame is asked for with an 8x8 corner invalidate, not `invalidate()`: the whole
     * surface used to be re-composed for every key, which is paint work no key needs
     * (Phase 12 round 4, Test-shell gate). A view invalidated on itself still re-records and
     * still runs `dispatchDraw`, so the probe timestamp is unaffected.
     */
    private var flashPending = false
    private var flashSeq = 0

    fun flashFrame() {
        flashSeq++
        flashPending = true
        invalidate(0, 0, FLASH_PROBE_PX, FLASH_PROBE_PX)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (flashPending) {
            flashPending = false
            flashListener?.invoke(flashSeq, System.nanoTime())
        }
    }

    fun setFlashListener(l: ((Int, Long) -> Unit)?) {
        flashListener = l
        testContent?.setFlashListener(l)
        probeChip.onDrawn = l
        P7Log.say(context, "P5PROBE listener wired test=${testContent != null} chip=${probeChip.armed} shell=true")
    }

    /** Harness: arm/disarm the typing-probe square (see [probeChip]). */
    fun probeArm(on: Boolean): String {
        probeChip.arm(on)
        P7Log.say(context, "P5PROBE chip arm on=$on")
        return "probe=${if (on) "on" else "off"} chip=bottom-right-28px"
    }

    fun closeWindow(id: Int) {
        val f = frames.remove(id) ?: return
        saveWindowRect(f)
        if (f.content is AppCenterView) appCenter = null
        removeView(f)
        model.close(id)
        syncViews()
    }

    // ------------------------------------------------------------- window memory

    private fun rectKey(app: App): String = "win_" + app.name

    private fun rememberedRect(app: App): IntArray? {
        val s = prefs.getString(rectKey(app), null) ?: return null
        val parts = s.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 4 && parts[2] > 0 && parts[3] > 0) parts.toIntArray() else null
    }

    private fun saveWindowRect(f: ShellWindowView) {
        prefs.edit().putString(rectKey(f.app), f.win.rect().joinToString(",")).apply()
    }

    /** Persist every open window's rectangle (called when the desktop goes away). */
    fun persistWindows() {
        for (f in frames.values) saveWindowRect(f)
    }

    override fun onDetachedFromWindow() {
        persistWindows()
        runCatching { context.unregisterReceiver(batteryReceiver) }
        toastHide?.let { removeCallbacks(it) }
        super.onDetachedFromWindow()
    }

    /** Close the X11 frame (its content reconnects when the next X11 app opens). */
    fun closeX11Window(): Boolean {
        val f = frames.values.firstOrNull { it.app == App.X11 } ?: return false
        closeWindow(f.win.id)
        return true
    }

    /** B-090: close the Android window showing [pkg] (after its import is removed/replaced). */
    fun closeGuestWindow(pkg: String): Boolean {
        val f = frames.values.firstOrNull {
            it.app == App.ANDROID && it.guest.equals(pkg, ignoreCase = true)
        } ?: return false
        closeWindow(f.win.id)
        return true
    }

    fun minimizeWindow(id: Int) {
        model.minimize(id)
        syncViews()
    }

    private fun onTaskbarWindowClick(id: Int) {
        val win = model.byId(id) ?: return
        if (win.minimized) model.restore(id)
        else if (model.focusedId == id) model.minimize(id) // like a real taskbar: click again to hide
        else model.focus(id)
        syncViews()
    }

    /** Push model state into the views (visibility, rects, taskbar, z-order). */
    fun syncViews() {
        for (win in model.all()) {
            val f = frames[win.id] ?: continue
            f.visibility = if (win.minimized) View.GONE else View.VISIBLE
            f.layoutParams = LayoutParams(win.w, win.h).apply { leftMargin = win.x; topMargin = win.y }
            f.isFocusedWindow = model.focusedId == win.id
            f.updateTitleBar()
        }
        // z-order: model order is bottom-to-top. bringChildToFront keeps Android's touch
        // targets alive (remove+add would cancel a drag in progress), so raise in that order.
        for (win in model.all()) frames[win.id]?.let { bringChildToFront(it) }
        // ... then the chrome above every window: taskbar, menu, probe, cursor.
        taskbar.bringToFront()
        if (startMenu.visibility == View.VISIBLE) startMenu.bringToFront()
        if (contextMenu.visibility == View.VISIBLE) contextMenu.bringToFront()
        if (welcomeCard.visibility == View.VISIBLE) welcomeCard.bringToFront()
        if (toastView.visibility == View.VISIBLE) toastView.bringToFront()
        if (modalPanel.visibility == View.VISIBLE) modalPanel.bringToFront()
        probeChip.bringToFront()
        cursorView.bringToFront()
        taskbar.buttons = model.taskbar()
        taskbar.invalidate()
        requestLayout()
        invalidate()
        onChanged?.invoke()
    }

    // ------------------------------------------------------------------ start menu

    fun toggleStartMenu() {
        if (startMenu.visibility == View.VISIBLE) closeStartMenu() else openStartMenu()
    }

    // ------------------------------------------------------------------ desktop menu

    private val menuW = 200
    private val menuH = 4 * 32 + 8

    fun contextMenuOpen(): Boolean = contextMenu.visibility == View.VISIBLE

    /** Show at the click, clamped so the whole panel stays above the taskbar. */
    private fun showContextMenu(x: Int, y: Int) {
        val left = x.coerceIn(4, (desktopW - menuW - 4).coerceAtLeast(4))
        val top = y.coerceIn(4, (desktopH - TASKBAR_H - menuH - 4).coerceAtLeast(4))
        contextMenu.layoutParams = LayoutParams(menuW, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = left
            topMargin = top
        }
        contextMenu.visibility = View.VISIBLE
        contextMenu.bringToFront()
        probeChip.bringToFront()
        cursorView.bringToFront()
        requestLayout()
        invalidate()
        P7Log.say(context, "P7SHELL context menu at=$left,$top")
    }

    private fun hideContextMenu() {
        if (contextMenu.visibility != View.GONE) {
            contextMenu.visibility = View.GONE
            invalidate()
        }
    }

    /** Rebuild the installed section from the records (after an install or uninstall). */
    fun refreshInstalledRows() {
        startMenu.setInstalledRows(InstalledMenu.rows(Installed.read(context), catalog))
    }

    /** Harness: every start-menu row, fixed entries first. */
    fun menuRows(): String {
        refreshInstalledRows()
        return startMenu.rowsText()
    }

    fun openStartMenu() {
        refreshInstalledRows()
        startMenu.visibility = View.VISIBLE
        // D8/manager 03:56: 560 wide (was 440) and sized to its content, at most 70 % of the
        // work area tall, so the search box and the top of the pinned row are always on
        // screen. The list inside scrolls when it hits the cap; the panel sits just above
        // the taskbar.
        val workH = desktopH - TASKBAR_H
        val menuH = minOf(startMenu.preferredHeight(), (workH * 0.70f).toInt())
        startMenu.layoutParams = LayoutParams(560, menuH).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = 8
            bottomMargin = TASKBAR_H + 8
        }
        startMenu.bringToFront()
        cursorView.bringToFront()
        hideContextMenu()
        startMenu.focusSearch()
        startMenu.invalidate()
        onChanged?.invoke()
    }

    fun closeStartMenu() {
        startMenu.visibility = View.GONE
        startMenu.clearSearch()
        onChanged?.invoke()
    }

    /** Governor step one: ensure minimized windows are detached from the render path. */
    fun stopRenderingHiddenWindows(): Int {
        val hidden = model.all().count { it.minimized }
        if (hidden > 0) syncViews()
        return hidden
    }

    fun startMenuOpen(): Boolean = startMenu.visibility == View.VISIBLE

    // ------------------------------------------------------------------ pointer

    /**
     * B-095: while a PC session is connected, the PC draws the one real arrow over the video.
     * Keep tracking the pointer for hover and clicks, but do not paint a second one on the
     * phone's own desktop surface.
     */
    fun setPcPointerVisible(visible: Boolean) {
        pcPointerVisible = visible
        cursorView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun pcPointerIsVisible(): Boolean = pcPointerVisible

    /**
     * Move the pointer without a button held (hover). Nothing is dispatched as a touch: a
     * hover only moves our cursor and updates the resize-handle feedback.
     */
    fun moveTo(x: Int, y: Int) {
        cursorX = x.coerceIn(0, desktopW - 1)
        cursorY = y.coerceIn(0, desktopH - 1)
        cursorView.setCursor(cursorX, cursorY)
        val frame = frameUnder(cursorX, cursorY)
        hoveredEdges = frame?.let { model.edgesAt(cursorX, cursorY, it.win) } ?: 0
        for (f in frames.values) {
            if (f === frame) f.setHover(true, cursorX - f.win.x, cursorY - f.win.y) else f.setHover(false)
        }
        if (cursorY >= desktopH - TASKBAR_H) {
            taskbar.hover(cursorX, cursorY - (desktopH - TASKBAR_H))
        } else {
            taskbar.hover(-1, -1)
        }
        cursorView.hoverEdges = hoveredEdges
        cursorView.invalidate()
        // With a button held a "move" is a drag: the PC sends moves, not separate drag
        // events, so the shell decides here (Phase-5 wire format unchanged).
        if (heldButtons.isNotEmpty()) dispatchPointer(MotionEvent.ACTION_MOVE, cursorX, cursorY, 1)
    }

    private fun dispatchPointer(action: Int, x: Int, y: Int, buttons: Int): Boolean {
        val ev = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0
        )
        ev.setSource(android.view.InputDevice.SOURCE_MOUSE)
        return try { dispatchTouchEvent(ev) } finally { ev.recycle() }
    }
    private var hoveredEdges = 0

    private fun frameUnder(x: Int, y: Int): ShellWindowView? {
        val win = model.windowAt(x, y) ?: return null
        return frames[win.id]
    }

    /**
     * A mouse button went down/up at (x, y). The event is a REAL MotionEvent with
     * SOURCE_MOUSE dispatched to the root, so Android's own hit-testing decides which view
     * (taskbar, start menu, window frame, window content) gets it.
     */
    fun mouseButton(button: Int, down: Boolean, x: Int, y: Int) {
        // T-005: a modal above every window swallows all clicks. A press outside it is
        // "cancel"; presses inside reach the buttons through normal hit-testing.
        if (modalOpen()) {
            if (down && button != Phase5Protocol.BTN_LEFT) {
                dismissModal()
                return
            }
            if (down && button == Phase5Protocol.BTN_LEFT) {
                val inside = x >= modalPanel.left && x < modalPanel.right &&
                    y >= modalPanel.top && y < modalPanel.bottom
                if (!inside) {
                    dismissModal()
                    return
                }
            }
        }
        if (down) {
            heldButtons.add(button)
            buttonsAllTime++
        } else heldButtons.remove(button)
        if (buttonLogs++ < 30) {
            P7Log.say(context, "P7SHELL button=$button down=$down at=$x,$y " +
                "under=${model.windowAt(x, y)?.title ?: "desktop"} focus=${model.focusedId ?: -1}")
        }
        moveTo(x, y)
        if (button == Phase5Protocol.BTN_RIGHT) {
            // Phase 13: a right-click on the wallpaper opens the desktop menu. A right-click
            // anywhere else closes it (window/taskbar context menus are not part of this run).
            if (down) {
                hideContextMenu()
                if (startMenuOpen()) closeStartMenu()
                if (y < desktopH - TASKBAR_H && model.windowAt(x, y) == null) showContextMenu(x, y)
            }
            invalidate()
            return
        }
        if (button != Phase5Protocol.BTN_LEFT) { invalidate(); return }
        // A left click outside an open desktop menu dismisses it before it reaches anything.
        if (down && contextMenuOpen()) {
            val inside = x >= contextMenu.left && x < contextMenu.right &&
                y >= contextMenu.top && y < contextMenu.bottom
            if (!inside) hideContextMenu()
        }
        // click-anywhere-focuses: the window under the pointer is raised before its content
        // sees the press (the design rule), except for clicks on the desktop itself.
        if (down) {
            val win = model.windowAt(x, y)
            if (win != null && model.focusedId != win.id) {
                model.focus(win.id)
                syncViews()
            }
        }
        val ev = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )
        val handled = try { dispatchTouchEvent(ev) } finally { ev.recycle() }
        if (!handled && startMenuOpen()) closeStartMenu()
        invalidate()
    }

    /** Pointer moved while a button is held: a real ACTION_MOVE to the same target. */
    fun dragTo(x: Int, y: Int) {
        val cx = x.coerceIn(0, desktopW - 1)
        val cy = y.coerceIn(0, desktopH - 1)
        cursorX = cx; cursorY = cy
        cursorView.setCursor(cx, cy)
        dispatchPointer(MotionEvent.ACTION_MOVE, cx, cy, 1)
        invalidate()
    }

    /** Called by a frame while the user drags its title bar or a resize handle. */
    internal fun frameDrag(id: Int, dx: Int, dy: Int, edges: Int) {
        dragCount++
        if (edges == 0) model.moveBy(id, dx, dy) else model.resizeBy(id, edges, dx, dy)
        val win = model.byId(id) ?: return
        frames[id]?.layoutParams = LayoutParams(win.w, win.h).apply { leftMargin = win.x; topMargin = win.y }
        taskbar.invalidate()
        requestLayout()
        invalidate()
        onChanged?.invoke()
    }

    /**
     * D6: drop a dragged title bar at the top edge to maximise, or at the left/right edge to
     * snap to that half. The pointer position is the title bar's press point in desktop
     * coordinates.
     */
    internal fun frameDragEnd(id: Int, x: Int, y: Int) {
        val f = frames[id] ?: return
        val win = f.win
        if (!win.maximized) {
            when {
                y <= 3 -> model.toggleMaximize(id)
                x <= 3 -> model.snap(id, 0)
                x >= desktopW - 4 -> model.snap(id, 1)
            }
        }
        syncViews()
        saveWindowRect(f)
    }

    /** Wheel over the desktop: scroll whatever is under the pointer (the shell itself). */
    fun wheel(delta: Int, x: Int, y: Int) {
        moveTo(x, y)
        frameUnder(x, y)?.wheel(delta, x, y)
        invalidate()
    }

    // ------------------------------------------------------------------ keyboard

    /**
     * Key event from the PC. Alt+F4 closes the focused window; everything else goes to the
     * focused window's content view (a real KeyEvent, deviceId -1 + scan code like Phase 5).
     */
    fun key(down: Boolean, keyCode: Int, scan: Int, meta: Int): Boolean {
        if (keyCode == 0) return false
        // T-005/T-006: the modal owns the keyboard while it is up. Esc cancels, Enter
        // submits, and a name field receives the real key events (PC keyboard only).
        if (modalOpen()) {
            if (down && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissModal()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (down) submitModal()
                return true
            }
            if (modalField.visibility == View.VISIBLE) {
                val now = SystemClock.uptimeMillis()
                val ev = KeyEvent(
                    now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    keyCode, 0, meta, -1, scan, 0, android.view.InputDevice.SOURCE_KEYBOARD,
                )
                // B-135: same edittext-ime route as keyToContent (the framework's
                // KeyCharacterMap typed '=' as '+' here too).
                val ch = Phase5Protocol.keyCodeToChar(keyCode, meta)
                if (ShellKeyCommit.decide(down, keyCode, meta, ch)) {
                    if (ShellKeyCommit.commit(modalField, ch.toString())) return true
                }
                return modalField.dispatchKeyEvent(ev)
            }
            return true
        }
        // The Windows key opens the start menu from the PC keyboard (D3).
        if (down && (keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT)) {
            toggleStartMenu()
            return true
        }
        if (startMenuOpen()) {
            if (down && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closeStartMenu()
                return true
            }
            return startMenu.handleKey(down, keyCode, scan, meta)
        }
        if (down) { heldKeys.add(keyCode); keysAllTime++ } else heldKeys.remove(keyCode)
        if (down && keyCode == KeyEvent.KEYCODE_F4 && (meta and KeyEvent.META_ALT_ON) != 0) {
            model.focusedId?.let { closeWindow(it) }
            return true
        }
        val frame = model.focusedId?.let { frames[it] } ?: return false
        val browser = frame.content as? BrowserView
        if (browser != null && browser.handleShortcut(down, keyCode, meta)) return true
        keyAtFocused++
        if (down) {
            keyFlashes++
            probeChip.flash()
            flashFrame()
        }
        val handled = frame.keyToContent(down, keyCode, scan, meta)
        if (down) P7Log.say(context, "P6SHELL key code=$keyCode meta=$meta focused=${frame.win.title} handled=$handled")
        return handled
    }

    /** Releases everything the PC still holds; returns (keys, buttons) released. */
    fun releaseAll(): Pair<Int, Int> {
        val k = heldKeys.size
        val b = heldButtons.size
        val now = SystemClock.uptimeMillis()
        for (code in heldKeys.toList()) {
            model.focusedId?.let { id -> frames[id]?.keyToContent(false, code, 0, 0) }
        }
        heldKeys.clear()
        heldButtons.clear()
        for (f in frames.values) f.cancelDrag()
        invalidate()
        return Pair(k, b)
    }

    fun heldCounts(): Pair<Int, Int> = Pair(heldKeys.size, heldButtons.size)

    /** What the `p5text` harness reads: the focused window's content text. */
    fun focusedText(): String =
        model.focusedId?.let { frames[it]?.contentText() } ?: ""

    fun stats(): String =
        "windows=${model.size()} focused=${model.focusedId ?: -1} startMenu=${if (startMenuOpen()) 1 else 0} " +
            "context=${if (contextMenuOpen()) 1 else 0} " +
            "keysDown=${heldKeys.size} buttonsDown=${heldButtons.size} keysAllTime=$keysAllTime " +
            "buttonsAllTime=$buttonsAllTime keyAtFocused=$keyAtFocused keyFlashes=$keyFlashes " +
            "drags=${dragCount}"

    private var dragCount = 0
    private var buttonLogs = 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        cursorView.setCursor(cursorX, cursorY)
    }
}
