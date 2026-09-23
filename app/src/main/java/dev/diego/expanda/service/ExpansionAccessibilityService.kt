package dev.diego.expanda.service

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.view.ContextThemeWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.Spinner
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.diego.expanda.ExpandaApplication
import dev.diego.expanda.MainActivity
import dev.diego.expanda.R
import dev.diego.expanda.engine.ActionCategory
import dev.diego.expanda.engine.ActionContext
import dev.diego.expanda.engine.ActionDefinition
import dev.diego.expanda.engine.ActionEngine
import dev.diego.expanda.engine.ActionOutcome
import dev.diego.expanda.engine.ActionRequest
import dev.diego.expanda.engine.SelectedTextOutcome
import dev.diego.expanda.engine.SmartCursorCase
import dev.diego.expanda.engine.AppliedExpansion
import dev.diego.expanda.engine.ExpansionEngine
import dev.diego.expanda.engine.ExpansionMatch
import dev.diego.expanda.engine.TemplateRenderer
import dev.diego.expanda.engine.RenderedTemplate
import dev.diego.expanda.engine.TemplateFieldInputType
import dev.diego.expanda.engine.TemplateFieldRequest
import dev.diego.expanda.engine.TemplateSelector
import dev.diego.expanda.engine.TriggerMatcher
import dev.diego.expanda.data.AppSettings
import dev.diego.expanda.data.DisplayLanguage
import dev.diego.expanda.data.SettingsRepository
import dev.diego.expanda.data.TextMatch
import dev.diego.expanda.data.TemplateSelectionMode
import dev.diego.expanda.data.VaultCategory
import dev.diego.expanda.data.VaultEntry
import dev.diego.expanda.data.VaultField
import dev.diego.expanda.service.overlay.OverlayViews
import dev.diego.expanda.ui.theme.NativeThemeTokens
import dev.diego.expanda.ui.theme.resolveNativeTheme
import dev.diego.expanda.ui.suggestion.SuggestionOverlaySpec
import dev.diego.expanda.ui.suggestion.SuggestionResizePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.ArrayDeque
import java.util.Locale
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class ExpansionAccessibilityService : AccessibilityService() {
    private sealed interface VaultTriggerTarget {
        data class Entry(val entry: VaultEntry) : VaultTriggerTarget
        data class Category(val category: VaultCategory) : VaultTriggerTarget
    }

    private sealed interface PopupSuggestion {
        val shortcut: String
        val matchedText: String

        data class TextSnippet(
            val textMatch: TextMatch,
            val suggestionTrigger: String,
            override val matchedText: String,
        ) : PopupSuggestion {
            override val shortcut: String get() = suggestionTrigger
        }

        data class VaultEntryItem(
            val entry: VaultEntry,
            val suggestionTrigger: String,
            override val matchedText: String,
        ) : PopupSuggestion {
            override val shortcut: String get() = suggestionTrigger
        }

        data class VaultCategoryItem(
            val category: VaultCategory,
            val suggestionTrigger: String,
            override val matchedText: String,
        ) : PopupSuggestion {
            override val shortcut: String get() = suggestionTrigger
        }

        data class Action(val definition: ActionDefinition, override val matchedText: String) : PopupSuggestion {
            override val shortcut: String get() = definition.shortcut
        }
    }

    private enum class PrimarySwipeDirection { LEFT, RIGHT, UP }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = ExpansionEngine()
    private val templateSelector = TemplateSelector()
    private val actionEngine = ActionEngine()
    private val repository by lazy { (application as ExpandaApplication).matchRepository }
    private val vaultRepository by lazy { (application as ExpandaApplication).vaultRepository }
    private val settingsRepository by lazy { (application as ExpandaApplication).settingsRepository }
    private val actionSettingsStore by lazy { (application as ExpandaApplication).actionSettingsStore }
    private val clipboardMonitor by lazy { (application as ExpandaApplication).clipboardMonitor }

    private var lastAppliedText: String? = null
    private var lastAppliedAt = 0L
    private var reversibleExpansion: ReversibleExpansion? = null
    private var suppressedExpansion: ReversibleExpansion? = null
    private var suggestionOverlay: View? = null
    private var suggestionWindowParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var suggestionValidation: Runnable? = null
    private var suggestionAnchor: SuggestionAnchor? = null
    private var formOverlay: View? = null
    private var vaultOverlayActive = false
    private var vaultOverlayOriginPackage: String? = null
    private var vaultOverlayShownAt = 0L
    private var pendingFormNode: AccessibilityNodeInfo? = null
    private var pendingFormApply: Runnable? = null
    private var activeFieldDialog: Dialog? = null

    private var selectionToolbar: View? = null
    private var selectionToolbarWindowParams: WindowManager.LayoutParams? = null
    private var selectionToolbarState: SelectionToolbarState? = null
    private var selectionToolbarValidation: Runnable? = null
    private var pendingSelectionToolbar: PendingSelectionToolbar? = null
    private var selectionToolbarShowTask: Runnable? = null
    private var selectionGroupOverlay: View? = null
    private var suppressSelectionToolbarUntil = 0L
    private var programmaticSelectionUntil = 0L
    private val selectionUndoHistory = ArrayDeque<SelectionUndoEntry>()
    private var pendingSmartCursorCase: PendingSmartCursorCase? = null

    private data class PendingSmartCursorCase(
        val anchor: SuggestionAnchor,
        val baselineText: String,
        val cursor: Int,
        val createdAt: Long,
    )

    private data class SelectionToolbarState(
        val anchor: SuggestionAnchor,
        val start: Int,
        val end: Int,
        val selectedText: String,
    )

    private data class PendingSelectionToolbar(
        val anchor: SuggestionAnchor,
        val packageName: String,
        val start: Int,
        val end: Int,
        val selectedText: String,
    )

    private data class SelectionUndoEntry(
        val anchor: SuggestionAnchor,
        val beforeText: String,
        val beforeStart: Int,
        val beforeEnd: Int,
        val afterText: String,
        val afterStart: Int,
        val afterEnd: Int,
    )

    /** Context saved while clipboard overlay / capture reads the clipboard. */
    private var pendingClipboardRetry: PendingClipboardRetry? = null
    private var clipboardOverlay: View? = null
    private var clipboardOverlayTimeout: Runnable? = null

    private sealed interface PendingClipboardRetry {
        val anchor: SuggestionAnchor

        data class Expansion(
            override val anchor: SuggestionAnchor,
            val packageName: String,
            val settings: AppSettings,
        ) : PendingClipboardRetry

        data class Action(
            override val anchor: SuggestionAnchor,
            val settings: AppSettings,
            val actionId: String,
            val shortcut: String,
            val replaceStart: Int,
            val replaceEnd: Int,
        ) : PendingClipboardRetry
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
        clipboardMonitor.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            when (event?.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // Gboard's swipe-to-delete gesture briefly exposes the text it
                    // is about to erase as a selection. Only classify a deletion as
                    // that gesture when a selection toolbar is already pending/visible.
                    // Expanda's own select actions also remove their typed shortcut,
                    // so they get a short grace period and must not be suppressed.
                    val now = SystemClock.elapsedRealtime()
                    val programmaticSelection = now < programmaticSelectionUntil
                    if (!programmaticSelection &&
                        event.removedCount > event.addedCount &&
                        (pendingSelectionToolbar != null || selectionToolbar != null)
                    ) {
                        suppressSelectionToolbarUntil =
                            now + SELECTION_TOOLBAR_DELETE_SUPPRESSION_MS
                    }
                    if (!programmaticSelection) {
                        cancelPendingSelectionToolbar()
                        hideSelectionToolbar()
                    }
                    cancelSuggestionValidation()
                    handleTextChanged(event)
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    handleSelectionChanged(event)
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    scheduleSelectionToolbarValidation()
                    scheduleSuggestionValidation()
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                -> {
                    dismissVaultOverlayForSystemContext(event)
                    // Adding/removing our TYPE_ACCESSIBILITY_OVERLAY also emits a
                    // windows-changed event. Do not immediately tear down the
                    // selection toolbar we just created; validate its anchored
                    // editor and selection after Android's window state settles.
                    scheduleSelectionToolbarValidation()
                    scheduleSuggestionValidation()
                }
            }
        } catch (failure: RuntimeException) {
            recoverFromEventFailure(failure)
        } catch (failure: LinkageError) {
            recoverFromEventFailure(failure)
        }
    }

    private fun recoverFromEventFailure(failure: Throwable) {
        Log.e(TAG, "Accessibility event failed; keeping the service alive", failure)
        clearExpansionUndo()
        hideSuggestions()
        hideFormOverlay()
        hideSelectionToolbar()
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val node = event.source
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return
        try {
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) return
            val packageName = event.packageName?.toString()?.takeIf { it.isNotEmpty() }
                ?: node.packageName?.toString().orEmpty()
            if (packageName.isEmpty()) return
            if (packageName == applicationContext.packageName &&
                node.viewIdResourceName != "${applicationContext.packageName}:id/${resources.getResourceEntryName(R.id.expanda_test_input)}"
            ) return
            val settings = settingsRepository.settings.value
            if (!settings.expansionEnabled || settings.isPaused || packageName in settings.globallyExcludedPackages) return

            // Prefer the event's fresh text over node.text: WebView-based editors (Gmail, Chrome,
            // Obsidian) throttle content-changed events, so the AccessibilityService cache can
            // return stale text (e.g. ";t" when the user just typed ";tim") for hundreds of ms.
            val text = AccessibilityTextSnapshot.text(
                eventTexts = event.text.firstOrNull(),
                nodeText = node.text,
                showingHint = node.isShowingHintText,
            ) ?: return
            val cursor = AccessibilityTextSnapshot.cursor(
                text = text,
                eventFromIndex = event.fromIndex,
                eventAddedCount = event.addedCount,
                eventRemovedCount = event.removedCount,
                nodeSelectionEnd = node.textSelectionEnd,
            )
            val selectionStart = node.textSelectionStart.takeIf { it in 0..text.length } ?: cursor
            val activeAnchor = createSuggestionAnchor(node, packageName)
            if (applyPendingSmartCursorCase(
                    node = node,
                    activeAnchor = activeAnchor,
                    text = text,
                    settings = settings,
                )
            ) {
                return
            }
            selectionUndoHistory.peekLast()?.let { undo ->
                val sameField = SuggestionAnchorPolicy.shouldKeep(undo.anchor, activeAnchor)
                val internalState = text == undo.afterText ||
                    (text == lastAppliedText &&
                        SystemClock.elapsedRealtime() - lastAppliedAt < REENTRANCY_WINDOW_MS)
                if (!sameField || !internalState) selectionUndoHistory.clear()
            }
            when (reversibleExpansion?.let {
                ExpansionUndoPolicy.backspaceDecision(it, activeAnchor, text, cursor)
            }) {
                ExpansionUndoDecision.Restore -> {
                    val expansion = reversibleExpansion ?: return
                    reversibleExpansion = null
                    hideSuggestions()
                    if (setFieldText(
                            node = node,
                            originalText = text,
                            newText = expansion.restoredText,
                            selectionStart = expansion.restoredCursor,
                            selectionEnd = expansion.restoredCursor,
                            settings = settings,
                        )
                    ) {
                        suppressedExpansion = expansion
                        lastAppliedText = expansion.restoredText
                        lastAppliedAt = SystemClock.elapsedRealtime()
                    } else {
                        suppressedExpansion = null
                    }
                    return
                }
                ExpansionUndoDecision.Clear -> reversibleExpansion = null
                ExpansionUndoDecision.Keep -> return
                null -> Unit
            }
            suppressedExpansion?.let { suppressed ->
                if (ExpansionUndoPolicy.isRestoredText(suppressed, activeAnchor, text)) return
            }
            if (text == lastAppliedText && SystemClock.elapsedRealtime() - lastAppliedAt < REENTRANCY_WINDOW_MS) return
            findVaultTrigger(text, cursor)?.let { (target, trigger) ->
                val triggerStart = cursor - trigger.length
                val withoutTrigger = text.removeRange(triggerStart, cursor)
                suppressedExpansion = null
                hideSuggestions()
                if (setFieldText(
                        node = node,
                        originalText = text,
                        newText = withoutTrigger,
                        selectionStart = triggerStart,
                        selectionEnd = triggerStart,
                        settings = settings,
                    )
                ) {
                    lastAppliedText = withoutTrigger
                    lastAppliedAt = SystemClock.elapsedRealtime()
                    when (target) {
                        is VaultTriggerTarget.Entry -> showVaultOverlay(
                            entryId = target.entry.id,
                            anchor = activeAnchor,
                            insertionCursor = triggerStart,
                        )
                        is VaultTriggerTarget.Category -> showVaultOverlay(
                            categoryName = target.category.name,
                            anchor = activeAnchor,
                            insertionCursor = triggerStart,
                        )
                    }
                }
                return
            }

            val action = actionEngine.execute(
                ActionContext(
                    text = text,
                    cursor = cursor,
                    selectionStart = selectionStart.coerceAtMost(cursor),
                    selectionEnd = cursor,
                    // Per-keystroke: read from the cached snapshot only. Calling
                    // ClipboardManager.getPrimaryClip() from an unfocused
                    // accessibility service on Samsung One UI 8.5 / Android 16
                    // triggers a system-level clipboard-access event that
                    // dismisses the IME ~1.7 s after every character (issue #6).
                    // The OnPrimaryClipChangedListener keeps this cache warm for
                    // paste actions; the live read still happens when the user
                    // actually taps an action suggestion or an expansion needs
                    // {{clipboard}}, via readClipboardText()/readClipboardTextOrNull().
                    clipboard = readClipboardTextCached(),
                ),
                enabledActionIds = actionSettingsStore.enabledIds.value,
                shortcutOverrides = actionSettingsStore.shortcutOverrides.value,
                triggerOverrides = actionSettingsStore.triggerOverrides.value,
            )
            if (action != null) {
                suppressedExpansion = null
                hideSuggestions()
                if (isClipboardInsertAction(action.definition.id)) {
                    val replaceStart = (cursor - action.definition.shortcut.length).coerceAtLeast(0)
                    launchClipboardActionCapture(
                        node = node,
                        packageName = packageName,
                        settings = settings,
                        definition = action.definition,
                        replaceStart = replaceStart,
                        replaceEnd = cursor,
                    )
                    return
                }
                val selectionAction = action.definition.category == ActionCategory.SELECTION &&
                    action.selectionStart != action.selectionEnd
                if (selectionAction) {
                    programmaticSelectionUntil =
                        SystemClock.elapsedRealtime() + PROGRAMMATIC_SELECTION_GRACE_MS
                    hideSelectionToolbar()
                }
                if (applyAction(node, text, action, settings)) {
                    lastAppliedText = action.text
                    lastAppliedAt = SystemClock.elapsedRealtime()
                    handleActionRequest(action.request, action.text)
                    if (selectionAction) {
                        scheduleSelectionToolbarFromOutcome(
                            anchor = activeAnchor,
                            packageName = packageName,
                            outcome = action,
                            settings = settings,
                        )
                    }
                }
                return
            }
            val candidates = engine.findMatchesAtCursor(text, cursor, repository.matches.value, packageName)
            suppressedExpansion?.let { suppressed ->
                suppressedExpansion = null
                if (candidates.isNotEmpty() && ExpansionUndoPolicy.shouldSuppressDelimiterExpansion(
                        suppressed, activeAnchor, text, cursor, candidates.first(),
                    )
                ) {
                    hideSuggestions()
                    return
                }
            }
            if (candidates.isEmpty()) {
                if (settings.suggestionEnabled) showSuggestions(node, text, cursor, packageName, settings)
                else hideSuggestions()
                return
            }
            if (candidates.size > 1) {
                hideSuggestions()
                @Suppress("DEPRECATION")
                showMatchDisambiguation(
                    AccessibilityNodeInfo.obtain(node),
                    text,
                    candidates,
                    packageName,
                    settings,
                )
                return
            }
            val match = candidates.single()
            hideSuggestions()
            continueExpansion(node, text, match, packageName, settings)
        } finally {
            node.recycle()
        }
    }

    private fun applyPendingSmartCursorCase(
        node: AccessibilityNodeInfo,
        activeAnchor: SuggestionAnchor,
        text: String,
        settings: AppSettings,
    ): Boolean {
        val pending = pendingSmartCursorCase ?: return false
        if (!settings.smartCursorCaseEnabled ||
            SystemClock.elapsedRealtime() - pending.createdAt > SMART_CURSOR_CASE_TIMEOUT_MS ||
            !SuggestionAnchorPolicy.shouldKeep(pending.anchor, activeAnchor)
        ) {
            pendingSmartCursorCase = null
            return false
        }
        if (text == pending.baselineText) return false

        val prefix = pending.baselineText.substring(0, pending.cursor)
        val suffix = pending.baselineText.substring(pending.cursor)
        val validInsertion = text.length >= pending.baselineText.length &&
            text.startsWith(prefix) &&
            text.endsWith(suffix)
        if (!validInsertion) {
            pendingSmartCursorCase = null
            return false
        }

        val correction = SmartCursorCase.lowercaseFirstInsertedLetter(
            baselineText = pending.baselineText,
            cursor = pending.cursor,
            currentText = text,
        )
        if (correction == null) return false

        pendingSmartCursorCase = null
        if (correction.text == text) return false

        reversibleExpansion = null
        suppressedExpansion = null
        hideSuggestions()
        if (setFieldText(
                node = node,
                originalText = text,
                newText = correction.text,
                selectionStart = correction.cursor,
                selectionEnd = correction.cursor,
                settings = settings,
            )
        ) {
            lastAppliedText = correction.text
            lastAppliedAt = SystemClock.elapsedRealtime()
            return true
        }
        return false
    }

    override fun onInterrupt() {
        clearExpansionUndo()
        pendingSmartCursorCase = null
        hideSuggestions()
        hideFormOverlay()
        hideSelectionToolbar()
    }

    override fun onDestroy() {
        clearExpansionUndo()
        pendingSmartCursorCase = null
        hideSuggestions()
        hideFormOverlay()
        hideSelectionToolbar()
        removeClipboardOverlay()
        if (activeService?.get() === this) activeService = null
        scope.cancel()
        super.onDestroy()
    }

    private fun handleSelectionChanged(event: AccessibilityEvent) {
        val node = event.source
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: run {
                hideSelectionToolbar()
                return
            }
        try {
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) {
                hideSelectionToolbar()
                return
            }
            val packageName = event.packageName?.toString()?.takeIf { it.isNotEmpty() }
                ?: node.packageName?.toString().orEmpty()
            if (packageName.isEmpty()) {
                hideSelectionToolbar()
                return
            }
            if (packageName == applicationContext.packageName &&
                node.viewIdResourceName != "${applicationContext.packageName}:id/${resources.getResourceEntryName(R.id.expanda_test_input)}"
            ) {
                hideSelectionToolbar()
                return
            }

            val settings = settingsRepository.settings.value
            if (!settings.expansionEnabled ||
                !settings.selectionToolbarEnabled ||
                settings.isPaused ||
                packageName in settings.globallyExcludedPackages
            ) {
                hideSelectionToolbar()
                return
            }

            val text = editableText(node)
            val rawStart = node.textSelectionStart
            val rawEnd = node.textSelectionEnd
            if (rawStart !in 0..text.length || rawEnd !in 0..text.length || rawStart == rawEnd) {
                hideSelectionToolbar()
                return
            }
            val start = minOf(rawStart, rawEnd)
            val end = maxOf(rawStart, rawEnd)
            val selectedText = text.substring(start, end)
            if (!selectionHasUsefulAction(selectedText)) {
                hideSelectionToolbar()
                return
            }

            hideSuggestions()
            scheduleSelectionToolbar(node, packageName, start, end, selectedText)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun scheduleSelectionToolbarFromOutcome(
        anchor: SuggestionAnchor,
        packageName: String,
        outcome: ActionOutcome,
        settings: AppSettings,
    ) {
        if (!settings.expansionEnabled ||
            !settings.selectionToolbarEnabled ||
            settings.isPaused ||
            packageName in settings.globallyExcludedPackages
        ) {
            return
        }
        val start = minOf(outcome.selectionStart, outcome.selectionEnd)
        val end = maxOf(outcome.selectionStart, outcome.selectionEnd)
        if (start !in 0..outcome.text.length || end !in 0..outcome.text.length || start == end) return
        val selectedText = outcome.text.substring(start, end)
        if (!selectionHasUsefulAction(selectedText)) return

        cancelPendingSelectionToolbar()
        val pending = PendingSelectionToolbar(
            anchor = anchor,
            packageName = packageName,
            start = start,
            end = end,
            selectedText = selectedText,
        )
        pendingSelectionToolbar = pending
        val task = Runnable {
            selectionToolbarShowTask = null
            if (pendingSelectionToolbar !== pending) return@Runnable
            pendingSelectionToolbar = null
            showPendingSelectionToolbar(pending)
        }
        selectionToolbarShowTask = task
        mainHandler.postDelayed(task, SELECTION_TOOLBAR_STABILITY_DELAY_MS)
    }

    private fun scheduleSelectionToolbar(
        node: AccessibilityNodeInfo,
        packageName: String,
        start: Int,
        end: Int,
        selectedText: String,
    ) {
        if (SystemClock.elapsedRealtime() < suppressSelectionToolbarUntil) {
            cancelPendingSelectionToolbar()
            return
        }

        val anchor = createSuggestionAnchor(node, packageName)
        val current = selectionToolbarState
        if (selectionToolbar != null &&
            current != null &&
            current.start == start &&
            current.end == end &&
            current.selectedText == selectedText &&
            SuggestionAnchorPolicy.shouldKeep(current.anchor, anchor)
        ) {
            cancelPendingSelectionToolbar()
            return
        }

        if (selectionToolbar != null) hideSelectionToolbar()
        else cancelPendingSelectionToolbar()

        val pending = PendingSelectionToolbar(
            anchor = anchor,
            packageName = packageName,
            start = start,
            end = end,
            selectedText = selectedText,
        )
        pendingSelectionToolbar = pending
        val task = Runnable {
            selectionToolbarShowTask = null
            if (pendingSelectionToolbar !== pending) return@Runnable
            pendingSelectionToolbar = null
            showPendingSelectionToolbar(pending)
        }
        selectionToolbarShowTask = task
        mainHandler.postDelayed(task, SELECTION_TOOLBAR_STABILITY_DELAY_MS)
    }

    private fun showPendingSelectionToolbar(pending: PendingSelectionToolbar) {
        if (SystemClock.elapsedRealtime() < suppressSelectionToolbarUntil) return
        val settings = settingsRepository.settings.value
        if (!settings.expansionEnabled ||
            !settings.selectionToolbarEnabled ||
            settings.isPaused ||
            pending.packageName in settings.globallyExcludedPackages
        ) {
            return
        }

        val node = findAnchoredEditor(pending.anchor, requireActiveWindow = false) ?: return
        try {
            runCatching { node.refresh() }
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) return

            val text = editableText(node)
            val rawStart = node.textSelectionStart
            val rawEnd = node.textSelectionEnd
            if (rawStart !in 0..text.length || rawEnd !in 0..text.length || rawStart == rawEnd) return

            val start = minOf(rawStart, rawEnd)
            val end = maxOf(rawStart, rawEnd)
            if (start != pending.start ||
                end != pending.end ||
                text.substring(start, end) != pending.selectedText
            ) {
                return
            }

            showSelectionToolbar(
                node = node,
                packageName = pending.packageName,
                start = start,
                end = end,
                selectedText = pending.selectedText,
                settings = settings,
            )
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun cancelPendingSelectionToolbar() {
        selectionToolbarShowTask?.let(mainHandler::removeCallbacks)
        selectionToolbarShowTask = null
        pendingSelectionToolbar = null
    }

    private fun showSelectionToolbar(
        node: AccessibilityNodeInfo,
        packageName: String,
        start: Int,
        end: Int,
        selectedText: String,
        settings: AppSettings,
    ) {
        val anchor = createSuggestionAnchor(node, packageName)
        val current = selectionToolbarState
        if (selectionToolbar != null &&
            current != null &&
            current.start == start &&
            current.end == end &&
            current.selectedText == selectedText &&
            SuggestionAnchorPolicy.shouldKeep(current.anchor, anchor)
        ) {
            return
        }

        hideSelectionToolbar()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val screen = displayBounds(windowManager)
        val horizontalMargin = dp(8)
        val toolbarWidth = (screen.width() * settings.selectionToolbarWidthFraction)
            .roundToInt()
            .coerceIn(
                (screen.width() * SettingsRepository.MIN_SELECTION_TOOLBAR_WIDTH).roundToInt(),
                (screen.width() * SettingsRepository.MAX_SELECTION_TOOLBAR_WIDTH).roundToInt(),
            )
            .coerceAtMost((screen.width() - horizontalMargin * 2).coerceAtLeast(dp(120)))
        val toolbarHeight = dp(
            settings.selectionToolbarHeightDp.coerceIn(
                SettingsRepository.MIN_SELECTION_TOOLBAR_HEIGHT_DP,
                SettingsRepository.MAX_SELECTION_TOOLBAR_HEIGHT_DP,
            ),
        )
        val heightDp = settings.selectionToolbarHeightDp
        val actionTextSize = when {
            settings.selectionToolbarWidthFraction < 0.50f -> 11f
            settings.selectionToolbarWidthFraction < 0.66f -> 13f
            else -> 15f
        }.coerceAtMost((heightDp * 0.30f).coerceIn(11f, 18f))
        val handleWidth = minOf(dp(40), (toolbarHeight - dp(8)).coerceAtLeast(dp(30)))
        val containerPadding = dp(if (heightDp <= 50) 3 else 4)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(containerPadding, containerPadding, containerPadding, containerPadding)
            background = ui.panel(16)
            elevation = dp(8).toFloat()
        }

        val dragHandle = object : TextView(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            text = "⠿"
            gravity = Gravity.CENTER
            setTextColor(ui.theme.onSurfaceVariant)
            textSize = ui.scaled(actionTextSize + 2f)
            includeFontPadding = false
            background = ui.surface(10)
            isClickable = true
            isFocusable = true
            contentDescription = localizedSelectionUi(
                settings,
                "Move selection toolbar. Long press and drag to resize.",
                "Mover barra de selección. Mantén pulsado y arrastra para redimensionar.",
            )
        }
        container.addView(
            dragHandle,
            LinearLayout.LayoutParams(
                handleWidth,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val fieldText = editableText(node)
        val toolbarActions = buildSelectionToolbarActions(settings)
        toolbarActions.forEach { action ->
            val groupHasOptions = action.isGroup && action.groupActionIds.isNotEmpty()
            val groupHasUsefulAction = groupHasOptions && action.groupActionIds.any { actionId ->
                actionEngine.processSelectedText(actionId, selectedText)
                    ?.let { it != selectedText } == true
            }
            val enabled = when {
                action.id == SELECTION_UNDO_ID -> canUndoSelection(anchor, fieldText)
                action.id == SELECTION_TRANSFORMS_MENU_ID || action.id == SELECTION_MORE_MENU_ID -> true
                action.isGroup -> groupHasUsefulAction
                action.id in SELECTION_INTERACTIVE_ACTION_IDS -> selectedText.isNotEmpty()
                else -> actionEngine.processSelectedText(action.id, selectedText)
                    ?.let { it != selectedText } == true
            }
            val accessibleDescription = when {
                action.isGroup && !groupHasOptions -> action.description + ". " + localizedSelectionUi(
                    settings,
                    "No options enabled.",
                    "No hay opciones activas.",
                )
                action.isGroup && enabled -> action.description + ". " + localizedSelectionUi(
                    settings,
                    "Tap to open. Long press and slide for quick choice.",
                    "Toca para abrir. Mantén pulsado y desliza para elegir rápidamente.",
                )
                action.isGroup -> action.description + ". " + localizedSelectionUi(
                    settings,
                    "No option applies to this selection.",
                    "Ninguna opción aplica a esta selección.",
                )
                !enabled -> action.description + ". " + localizedSelectionUi(
                    settings,
                    "Unavailable for this selection.",
                    "No disponible para esta selección.",
                )
                else -> action.description
            }
            val button = ui.body(action.label, sizeSp = actionTextSize).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(2), 0)
                background = ui.surface(10)
                isEnabled = enabled
                isClickable = enabled
                isFocusable = enabled
                alpha = if (enabled) 1f else 0.36f
                maxLines = 1
                contentDescription = accessibleDescription
                if (enabled) {
                    setOnClickListener {
                        when {
                            action.id == SELECTION_UNDO_ID -> undoSelectionToolbarAction()
                            action.id == SELECTION_TRANSFORMS_MENU_ID -> showSelectionActionMenu(
                                title = selectionUiText(settings, "suggested"),
                                actionIds = SELECTION_CONTEXT_ACTION_IDS,
                                showAll = false,
                            )
                            action.id == SELECTION_MORE_MENU_ID -> showSelectionActionMenu(
                                title = selectionUiText(settings, "all_tools"),
                                actionIds = SELECTION_CATALOG_ACTION_IDS,
                                showAll = true,
                            )
                            action.isGroup -> showSelectionActionMenu(
                                title = action.description,
                                actionIds = action.groupActionIds,
                                showAll = false,
                                actionLabels = action.groupActionLabels,
                            )
                            action.id in SELECTION_INTERACTIVE_ACTION_IDS -> runSelectionTool(action.id)
                            else -> applySelectionToolbarAction(action.id)
                        }
                    }
                    if (action.isGroup) {
                        setOnTouchListener(
                            createSelectionToolbarGroupGestureListener(
                                button = this,
                                action = action,
                                settings = settings,
                            ),
                        )
                    }
                }
            }
            container.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f,
                ).apply {
                    marginStart = dp(3)
                },
            )
        }

        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)

        val top = safeTop()
        val bottom = safeBottom(screen)
        val centeredX = nodeBounds.centerX() - toolbarWidth / 2
        val aboveY = nodeBounds.top - toolbarHeight - dp(8)
        val belowY = nodeBounds.bottom + dp(8)
        val automaticY = if (aboveY >= top) aboveY else belowY

        val params = WindowManager.LayoutParams(
            toolbarWidth,
            toolbarHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (settings.selectionToolbarPositionX >= 0) {
                settings.selectionToolbarPositionX
            } else {
                centeredX
            }).coerceIn(
                horizontalMargin,
                (screen.width() - toolbarWidth - horizontalMargin).coerceAtLeast(horizontalMargin),
            )
            this.y = (if (settings.selectionToolbarPositionY >= 0) {
                settings.selectionToolbarPositionY
            } else {
                automaticY
            }).coerceIn(
                top,
                (bottom - toolbarHeight).coerceAtLeast(top),
            )
        }
        dragHandle.setOnTouchListener(
            createSelectionToolbarDragListener(
                container = container,
                handle = dragHandle,
                windowManager = windowManager,
                bounds = screen,
            ),
        )

        runCatching {
            windowManager.addView(container, params)
            selectionToolbar = container
            selectionToolbarWindowParams = params
            selectionToolbarState = SelectionToolbarState(anchor, start, end, selectedText)
        }.onFailure {
            Log.w(TAG, "Could not show selection toolbar", it)
            selectionToolbar = null
            selectionToolbarWindowParams = null
            selectionToolbarState = null
        }
    }

    private fun selectionHasUsefulAction(selectedText: String): Boolean = selectedText.isNotEmpty()

    private fun buildSelectionToolbarActions(settings: AppSettings): List<SelectionToolbarAction> {
        val quick = settings.selectionToolbarQuickActionIds.mapNotNull { id ->
            when {
                id in settings.selectionActionGroupConfigs -> {
                    val groupConfig = settings.selectionActionGroupConfigs.getValue(id)
                    SelectionToolbarAction(
                        id = id,
                        label = groupConfig.label,
                        description = selectionActionGroupDescription(id, settings, groupConfig.label),
                        isGroup = true,
                        groupActionIds = groupConfig.actionOrder.filter {
                            it in groupConfig.enabledActionIds
                        },
                        groupActionLabels = groupConfig.actionLabels,
                    )
                }
                id in SELECTION_INTERACTIVE_ACTION_IDS -> SelectionToolbarAction(
                    id = id,
                    label = selectionQuickLabel(id),
                    description = selectionActionTitle(id, settings, ""),
                )
                else -> ActionEngine.definitions.firstOrNull { it.id == id }?.let { definition ->
                    SelectionToolbarAction(
                        id = id,
                        label = selectionQuickLabel(id),
                        description = selectionActionDescription(id, settings, definition.description),
                    )
                }
            }
        }
        return listOf(
            SelectionToolbarAction(
                SELECTION_UNDO_ID,
                "↶",
                selectionUiText(settings, "undo"),
            ),
        ) + quick + listOf(
            SelectionToolbarAction(
                SELECTION_TRANSFORMS_MENU_ID,
                "↔",
                selectionUiText(settings, "suggested"),
            ),
            SelectionToolbarAction(
                SELECTION_MORE_MENU_ID,
                "⋯",
                selectionUiText(settings, "all_tools"),
            ),
        )
    }

    private fun selectionActionGroupDescription(
        id: String,
        settings: AppSettings,
        fallback: String,
    ): String = when (id) {
        SettingsRepository.SELECTION_CASE_GROUP_ID -> localizedSelectionUi(
            settings,
            "Letter case",
            "Mayúsculas/minúsculas",
        )
        SettingsRepository.SELECTION_WRAP_GROUP_ID -> localizedSelectionUi(
            settings,
            "Wrap",
            "Envolver",
        )
        else -> fallback
    }

    private fun selectionQuickLabel(id: String): String = when (id) {
        "uppercase" -> "ABC"
        "lowercase" -> "abc"
        "sentence_case" -> "Abc."
        "title_case" -> "AaA"
        "wrap_guillemets" -> "« »"
        "wrap_parentheses" -> "( )"
        "wrap_question" -> "¿ ?"
        "wrap_exclamation" -> "¡ !"
        "wrap_brackets" -> "[ ]"
        "wrap_double_asterisk" -> "** **"
        "wrap_double_underscore" -> "__ __"
        "sort_lines" -> "A↓"
        "remove_duplicate_lines" -> "≠"
        "remove_all_spaces" -> "␠×"
        "reverse_text" -> "↤"
        "number_lines" -> "1."
        SELECTION_FIND_REPLACE_ID -> "⌕"
        SELECTION_TEXT_COUNTER_ID -> "#"
        SELECTION_REPEAT_TEXT_ID -> "×"
        SELECTION_PREFIX_SUFFIX_ID -> "P/S"
        "delete_blank_lines" -> "∅"
        "remove_duplicate_words" -> "W≠"
        "remove_line_breaks" -> "↵×"
        "reverse_lines" -> "L↕"
        "reverse_words" -> "W↔"
        "remove_diacritics" -> "á→a"
        "trim_spaces" -> "⇥"
        "space_underscore" -> "_"
        "space_dash" -> "-"
        "underscore_space" -> "_→␠"
        "dash_space" -> "-→␠"
        "math_replace" -> "="
        "math_append" -> "+="
        "number_space" -> "1 000"
        "number_period" -> "1.000"
        "number_comma" -> "1,000"
        else -> "•"
    }

    private fun createSelectionToolbarGroupGestureListener(
        button: View,
        action: SelectionToolbarAction,
        settings: AppSettings,
    ): View.OnTouchListener {
        val actionIds = action.groupActionIds.distinct()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        var downX = 0f
        var downY = 0f
        var moved = false
        var dragMenuActive = false
        var longPressTask: Runnable? = null
        var menuScreenBounds: Rect? = null
        var optionViews: List<TextView> = emptyList()
        var hoveredIndex = -1
        var menuColumns = 1
        var menuRows = 1
        var menuRowHeight = dp(48)

        fun cancelLongPress() {
            longPressTask?.let(mainHandler::removeCallbacks)
            longPressTask = null
        }

        fun clearDragMenuState() {
            hideSelectionGroupOverlay()
            dragMenuActive = false
            menuScreenBounds = null
            optionViews = emptyList()
            hoveredIndex = -1
            menuColumns = 1
            menuRows = 1
        }

        fun updateHighlight(index: Int) {
            if (index == hoveredIndex) return
            val previousIndex = hoveredIndex
            hoveredIndex = index
            optionViews.forEachIndexed { optionIndex, option ->
                val selected = optionIndex == hoveredIndex
                option.background = ui.surface(
                    radiusDp = 10,
                    emphasized = selected,
                )
                option.alpha = if (selected) 1f else 0.82f
                option.scaleX = if (selected) 1.06f else 1f
                option.scaleY = if (selected) 1.06f else 1f
            }
            if (
                settings.hapticFeedback &&
                previousIndex in actionIds.indices &&
                index in actionIds.indices
            ) {
                vibrateTick()
            }
        }

        fun showDragMenu() {
            val toolbarView = selectionToolbar ?: return
            val toolbarParams = selectionToolbarWindowParams ?: return
            if (actionIds.isEmpty() || selectionToolbarState == null) return

            hideSelectionGroupOverlay()
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = displayBounds(windowManager)
            val horizontalMargin = dp(8)
            val outerPadding = dp(4)
            val targetCellWidth = dp(56)
            val minimumCellWidth = dp(48)
            menuRowHeight = dp(48)

            val availableWidth = (bounds.width() - horizontalMargin * 2)
                .coerceAtLeast(minimumCellWidth + outerPadding * 2)
            val maxColumns = ((availableWidth - outerPadding * 2) / minimumCellWidth)
                .coerceAtLeast(1)
            val balancedRows = ((actionIds.size + maxColumns - 1) / maxColumns)
                .coerceAtLeast(1)
            menuColumns = ((actionIds.size + balancedRows - 1) / balancedRows)
                .coerceAtLeast(1)
            menuRows = ((actionIds.size + menuColumns - 1) / menuColumns)
                .coerceAtLeast(1)

            val desiredWidth = menuColumns * targetCellWidth + outerPadding * 2
            val menuWidth = minOf(
                availableWidth,
                maxOf(dp(200), desiredWidth),
            )
            val innerWidth = (menuWidth - outerPadding * 2).coerceAtLeast(menuColumns)
            val cellGap = dp(2)
            val totalGaps = cellGap * (menuColumns - 1).coerceAtLeast(0)
            val optionWidth = ((innerWidth - totalGaps) / menuColumns).coerceAtLeast(1)
            val menuHeight = menuRows * menuRowHeight + outerPadding * 2

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
                background = ui.panel(14)
                elevation = dp(10).toFloat()
            }

            val builtViews = mutableListOf<TextView>()
            actionIds.chunked(menuColumns).forEach { rowActionIds ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                root.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        menuRowHeight,
                    ),
                )
                rowActionIds.forEachIndexed { columnIndex, actionId ->
                    val option = ui.body(
                        action.groupActionLabels[actionId] ?: selectionQuickLabel(actionId),
                        sizeSp = when {
                            optionWidth < dp(44) -> 11.5f
                            optionWidth < dp(52) -> 12.5f
                            else -> 14f
                        },
                    ).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        alpha = 0.82f
                        background = ui.surface(10)
                        contentDescription = selectionActionTitle(
                            actionId,
                            settings,
                            ActionEngine.definitions.firstOrNull { it.id == actionId }?.title.orEmpty(),
                        )
                    }
                    builtViews += option
                    row.addView(
                        option,
                        LinearLayout.LayoutParams(
                            optionWidth,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                        ).apply {
                            if (columnIndex < rowActionIds.lastIndex) marginEnd = cellGap
                        },
                    )
                }
            }
            optionViews = builtViews

            // Convert the button's real screen center back into accessibility-overlay
            // coordinates. This stays correct even if the toolbar layout becomes nested.
            val toolbarScreenLocation = IntArray(2)
            val buttonScreenLocation = IntArray(2)
            toolbarView.getLocationOnScreen(toolbarScreenLocation)
            button.getLocationOnScreen(buttonScreenLocation)
            val screenOffsetX = toolbarScreenLocation[0] - toolbarParams.x
            val screenOffsetY = toolbarScreenLocation[1] - toolbarParams.y
            val buttonCenterOverlayX =
                buttonScreenLocation[0] + button.width / 2 - screenOffsetX

            val x = (buttonCenterOverlayX - menuWidth / 2).coerceIn(
                horizontalMargin,
                (bounds.width() - menuWidth - horizontalMargin).coerceAtLeast(horizontalMargin),
            )

            val top = safeTop()
            val bottom = safeBottom(bounds)
            val edgeGap = dp(2)
            val toolbarTop = toolbarParams.y
            val toolbarBottom = toolbarParams.y +
                toolbarView.height.coerceAtLeast(toolbarParams.height)
            val roomAbove = (toolbarTop - edgeGap - top).coerceAtLeast(0)
            val roomBelow = (bottom - toolbarBottom - edgeGap).coerceAtLeast(0)
            val placeAbove = when {
                roomAbove >= menuHeight -> true
                roomBelow >= menuHeight -> false
                else -> roomAbove >= roomBelow
            }
            val unclampedY = if (placeAbove) {
                toolbarTop - menuHeight - edgeGap
            } else {
                toolbarBottom + edgeGap
            }
            val y = unclampedY.coerceIn(
                top,
                (bottom - menuHeight).coerceAtLeast(top),
            )

            val params = WindowManager.LayoutParams(
                menuWidth,
                menuHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }

            menuScreenBounds = Rect(
                x + screenOffsetX,
                y + screenOffsetY,
                x + screenOffsetX + menuWidth,
                y + screenOffsetY + menuHeight,
            )

            runCatching {
                windowManager.addView(root, params)
                selectionGroupOverlay = root
                dragMenuActive = true
                if (settings.hapticFeedback) vibrateTick()
            }.onFailure {
                Log.w(TAG, "Could not show selection group drag menu", it)
                clearDragMenuState()
            }
        }

        fun hoverFor(rawX: Float, rawY: Float): Int {
            val bounds = menuScreenBounds ?: return -1
            val verticalTolerance = dp(12)
            if (
                rawX < bounds.left || rawX >= bounds.right ||
                rawY < bounds.top - verticalTolerance ||
                rawY >= bounds.bottom + verticalTolerance
            ) {
                return -1
            }

            val outerPadding = dp(4)
            val innerLeft = bounds.left + outerPadding
            val innerRight = bounds.right - outerPadding
            val innerTop = bounds.top + outerPadding
            val innerBottom = bounds.bottom - outerPadding
            if (rawX < innerLeft || rawX >= innerRight || innerBottom <= innerTop) return -1

            val innerWidth = (innerRight - innerLeft).coerceAtLeast(1)
            val relativeX = rawX - innerLeft
            val column = ((relativeX / innerWidth) * menuColumns)
                .toInt()
                .coerceIn(0, menuColumns - 1)

            val clampedY = rawY.coerceIn(
                innerTop.toFloat(),
                (innerBottom - 1).coerceAtLeast(innerTop).toFloat(),
            )
            val row = ((clampedY - innerTop) / menuRowHeight)
                .toInt()
                .coerceIn(0, menuRows - 1)

            val index = row * menuColumns + column
            return index.takeIf { it in actionIds.indices } ?: -1
        }

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    clearDragMenuState()
                    cancelLongPress()
                    val task = Runnable { showDragMenu() }
                    longPressTask = task
                    mainHandler.postDelayed(task, longPressTimeout)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragMenuActive) {
                        if (selectionToolbarState == null || selectionGroupOverlay == null) {
                            clearDragMenuState()
                        } else {
                            updateHighlight(hoverFor(event.rawX, event.rawY))
                        }
                    } else if (!moved &&
                        (kotlin.math.abs(event.rawX - downX) > touchSlop ||
                            kotlin.math.abs(event.rawY - downY) > touchSlop)
                    ) {
                        moved = true
                        cancelLongPress()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    if (dragMenuActive) {
                        val index = hoverFor(event.rawX, event.rawY)
                        clearDragMenuState()
                        if (index in actionIds.indices) {
                            runSelectionTool(actionIds[index])
                        }
                    } else if (!moved) {
                        button.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelLongPress()
                    moved = true
                    clearDragMenuState()
                    true
                }

                else -> false
            }
        }
    }

    private fun showSelectionActionMenu(
        title: String,
        actionIds: List<String>,
        showAll: Boolean,
        actionLabels: Map<String, String> = emptyMap(),
    ) {
        val state = selectionToolbarState ?: return
        val settings = settingsRepository.settings.value
        val availableIds = actionIds.filter { actionId ->
            if (showAll || actionId in SELECTION_INTERACTIVE_ACTION_IDS) {
                true
            } else {
                actionEngine.processSelectedText(actionId, state.selectedText)
                    ?.let { it != state.selectedText } == true
            }
        }
        if (availableIds.isEmpty()) return

        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            addView(ui.title(title).apply {
                setPadding(dp(6), dp(2), dp(6), dp(8))
            })
            addView(ui.body(state.selectedText.replace('\n', ' ').take(120), secondary = true).apply {
                setPadding(dp(6), 0, dp(6), dp(10))
                maxLines = 2
            })
        }

        var previousGroup: String? = null
        availableIds.forEach { actionId ->
            if (showAll) {
                val group = selectionToolGroup(actionId)
                if (group != previousGroup) {
                    content.addView(ui.body(selectionGroupTitle(group, settings), secondary = true).apply {
                        setPadding(dp(6), dp(if (previousGroup == null) 2 else 10), dp(6), dp(6))
                    })
                    previousGroup = group
                }
            }
            val definition = ActionEngine.definitions.firstOrNull { it.id == actionId }
            val titleText = actionLabels[actionId]
                ?.takeIf(String::isNotBlank)
                ?: selectionActionTitle(actionId, settings, definition?.title.orEmpty())
            val descriptionText = selectionActionDescription(actionId, settings, definition?.description.orEmpty())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minimumHeight = dp(48)
                background = ui.surface()
                isClickable = true
                isFocusable = true
                contentDescription = descriptionText
                addView(ui.body(titleText))
                if (descriptionText.isNotBlank()) {
                    addView(ui.body(descriptionText, secondary = true).apply {
                        setPadding(0, dp(2), 0, 0)
                        maxLines = 2
                    })
                }
                setOnClickListener {
                    hideFormOverlay()
                    runSelectionTool(actionId)
                }
            }
            content.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) },
            )
        }

        val footer = overlayCancelFooter(
            ui,
            localizedSelectionUi(settings, "Cancel", "Cancelar"),
        ) { hideFormOverlay() }
        val bounds = displayBounds(windowManager)
        val maxContentHeight = (bounds.height() * 0.62f).toInt().coerceAtLeast(dp(220))
        val root = buildPickerOverlayRoot(
            content = content,
            footer = footer,
            itemCount = availableIds.size + if (showAll) 4 else 0,
            maxContentHeightPx = maxContentHeight,
            background = ui.panel(22),
            ui = ui,
        )
        val params = overlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
        }.onFailure {
            Log.w(TAG, "Could not show selection action menu", it)
            formOverlay = null
        }
    }

    private fun runSelectionTool(actionId: String) {
        when (actionId) {
            SELECTION_FIND_REPLACE_ID -> showFindReplaceOverlay()
            SELECTION_TEXT_COUNTER_ID -> showTextCounterOverlay()
            SELECTION_REPEAT_TEXT_ID -> showRepeatTextOverlay()
            SELECTION_PREFIX_SUFFIX_ID -> showPrefixSuffixOverlay()
            else -> applySelectionToolbarAction(actionId)
        }
    }

    private fun canUndoSelection(anchor: SuggestionAnchor, currentText: String): Boolean {
        val undo = selectionUndoHistory.peekLast() ?: return false
        return SuggestionAnchorPolicy.shouldKeep(undo.anchor, anchor) && undo.afterText == currentText
    }

    private fun pushSelectionUndo(
        anchor: SuggestionAnchor,
        beforeText: String,
        beforeStart: Int,
        beforeEnd: Int,
        outcome: SelectedTextOutcome,
    ) {
        if (selectionUndoHistory.size >= MAX_SELECTION_UNDO_HISTORY) {
            selectionUndoHistory.removeFirst()
        }
        selectionUndoHistory.addLast(
            SelectionUndoEntry(
                anchor = anchor,
                beforeText = beforeText,
                beforeStart = beforeStart,
                beforeEnd = beforeEnd,
                afterText = outcome.text,
                afterStart = outcome.selectionStart,
                afterEnd = outcome.selectionEnd,
            ),
        )
    }

    private fun undoSelectionToolbarAction() {
        val undo = selectionUndoHistory.peekLast() ?: return
        clearAccessibilityCache()
        val node = findAnchoredEditor(undo.anchor, requireActiveWindow = false) ?: run {
            selectionUndoHistory.clear()
            hideSelectionToolbar()
            return
        }
        try {
            runCatching { node.refresh() }
            val currentText = editableText(node)
            if (currentText != undo.afterText) {
                selectionUndoHistory.clear()
                hideSelectionToolbar()
                return
            }
            val replacement = undo.beforeText.substring(
                undo.beforeStart.coerceIn(0, undo.beforeText.length),
                undo.beforeEnd.coerceIn(0, undo.beforeText.length),
            )
            val outcome = SelectedTextOutcome(
                text = undo.beforeText,
                selectionStart = undo.beforeStart,
                selectionEnd = undo.beforeEnd,
                replacement = replacement,
            )
            val settings = settingsRepository.settings.value
            hideSelectionToolbar()
            if (applySelectedTextOutcome(node, currentText, outcome, settings)) {
                selectionUndoHistory.removeLast()
                lastAppliedText = undo.beforeText
                lastAppliedAt = SystemClock.elapsedRealtime()
                if (settings.hapticFeedback) vibrate()
            }
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun applyCustomSelectionReplacement(
        state: SelectionToolbarState,
        replacement: String,
    ) {
        clearAccessibilityCache()
        val node = findAnchoredEditor(state.anchor, requireActiveWindow = false) ?: return
        try {
            runCatching { node.refresh() }
            val text = editableText(node)
            val start = state.start
            val end = state.end
            if (start !in 0..text.length || end !in start..text.length) return
            val currentSelected = text.substring(start, end)
            if (currentSelected != state.selectedText || replacement == currentSelected) return
            val outcome = SelectedTextOutcome(
                text = text.replaceRange(start, end, replacement),
                selectionStart = start,
                selectionEnd = start + replacement.length,
                replacement = replacement,
            )
            val settings = settingsRepository.settings.value
            hideSelectionToolbar()
            if (applySelectedTextOutcome(node, text, outcome, settings)) {
                pushSelectionUndo(state.anchor, text, start, end, outcome)
                lastAppliedText = outcome.text
                lastAppliedAt = SystemClock.elapsedRealtime()
                if (settings.hapticFeedback) vibrate()
            }
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun showFindReplaceOverlay() {
        val state = selectionToolbarState ?: return
        val settings = settingsRepository.settings.value
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val findInput = ui.input(selectionUiText(settings, "find"), "").apply {
            setSingleLine(true)
        }
        val replaceInput = ui.input(selectionUiText(settings, "replace_with"), "").apply {
            setSingleLine(true)
        }
        val caseSensitive = CheckBox(this).apply {
            text = selectionUiText(settings, "case_sensitive")
            setTextColor(ui.theme.onSurface)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(ui.title(selectionUiText(settings, "find_replace")))
            addView(ui.body(state.selectedText.replace('\n', ' ').take(140), secondary = true).apply {
                setPadding(0, dp(4), 0, dp(10))
                maxLines = 2
            })
            addView(ui.fieldGroup(selectionUiText(settings, "find"), findInput))
            addView(ui.fieldGroup(selectionUiText(settings, "replace_with"), replaceInput))
            addView(caseSensitive)
        }
        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = selectionUiText(settings, "replace_all"),
            cancelLabel = selectionUiText(settings, "cancel"),
            onCancel = { hideFormOverlay() },
            onPrimary = {
                val needle = findInput.text.toString()
                if (needle.isEmpty()) return@overlayActionFooter
                val replacement = replaceInput.text.toString()
                val result = if (caseSensitive.isChecked) {
                    state.selectedText.replace(needle, replacement)
                } else {
                    Regex(Regex.escape(needle), RegexOption.IGNORE_CASE)
                        .replace(state.selectedText, replacement)
                }
                hideFormOverlay()
                applyCustomSelectionReplacement(state, result)
            },
        )
        val root = buildOverlayRoot(panel, footer, ui.panel(22), ui)
        val params = overlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            findInput.requestFocus()
            findInput.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }.onFailure {
            formOverlay = null
        }
    }

    private fun showPrefixSuffixOverlay() {
        val state = selectionToolbarState ?: return
        val settings = settingsRepository.settings.value
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val prefix = ui.input(selectionUiText(settings, "prefix"), "").apply { setSingleLine(true) }
        val suffix = ui.input(selectionUiText(settings, "suffix"), "").apply { setSingleLine(true) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(ui.title(selectionUiText(settings, "prefix_suffix")))
            addView(ui.fieldGroup(selectionUiText(settings, "prefix"), prefix))
            addView(ui.fieldGroup(selectionUiText(settings, "suffix"), suffix))
        }
        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = selectionUiText(settings, "apply"),
            cancelLabel = selectionUiText(settings, "cancel"),
            onCancel = { hideFormOverlay() },
            onPrimary = {
                val result = prefix.text.toString() + state.selectedText + suffix.text.toString()
                hideFormOverlay()
                applyCustomSelectionReplacement(state, result)
            },
        )
        val root = buildOverlayRoot(panel, footer, ui.panel(22), ui)
        val params = overlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            prefix.requestFocus()
            prefix.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(prefix, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }.onFailure { formOverlay = null }
    }

    private fun showRepeatTextOverlay() {
        val state = selectionToolbarState ?: return
        val settings = settingsRepository.settings.value
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val count = ui.input(selectionUiText(settings, "repeat_count"), "2").apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val separator = ui.input(selectionUiText(settings, "separator"), "\\n").apply {
            setSingleLine(true)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(ui.title(selectionUiText(settings, "repeat_text")))
            addView(ui.fieldGroup(selectionUiText(settings, "repeat_count"), count))
            addView(ui.fieldGroup(selectionUiText(settings, "separator"), separator))
            addView(ui.body(selectionUiText(settings, "separator_hint"), secondary = true))
        }
        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = selectionUiText(settings, "apply"),
            cancelLabel = selectionUiText(settings, "cancel"),
            onCancel = { hideFormOverlay() },
            onPrimary = {
                val times = count.text.toString().toIntOrNull()?.coerceIn(1, 1000) ?: 1
                val separatorValue = separator.text.toString()
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                val result = List(times) { state.selectedText }.joinToString(separatorValue)
                hideFormOverlay()
                applyCustomSelectionReplacement(state, result)
            },
        )
        val root = buildOverlayRoot(panel, footer, ui.panel(22), ui)
        val params = overlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            count.requestFocus()
            count.selectAll()
        }.onFailure { formOverlay = null }
    }

    private fun showTextCounterOverlay() {
        val state = selectionToolbarState ?: return
        val settings = settingsRepository.settings.value
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val text = state.selectedText
        val words = Regex("\\S+").findAll(text).count()
        val lines = if (text.isEmpty()) 0 else text.split(Regex("\\R")).size
        val withoutSpaces = text.count { !it.isWhitespace() }
        val details = buildString {
            appendLine("${selectionUiText(settings, "characters")}: ${text.length}")
            appendLine("${selectionUiText(settings, "characters_no_spaces")}: $withoutSpaces")
            appendLine("${selectionUiText(settings, "words")}: $words")
            append("${selectionUiText(settings, "lines")}: $lines")
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(ui.title(selectionUiText(settings, "text_counter")))
            addView(ui.body(details).apply { setPadding(0, dp(10), 0, dp(8)) })
        }
        val footer = overlayCancelFooter(
            ui,
            selectionUiText(settings, "close"),
        ) { hideFormOverlay() }
        val root = buildOverlayRoot(content, footer, ui.panel(22), ui)
        val params = overlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
        }.onFailure { formOverlay = null }
    }

    private fun selectionUsesSpanish(settings: AppSettings): Boolean = when (settings.displayLanguage) {
        DisplayLanguage.SPANISH -> true
        DisplayLanguage.ENGLISH -> false
        DisplayLanguage.SYSTEM -> Locale.getDefault().language.equals("es", ignoreCase = true)
    }

    private fun localizedSelectionUi(
        settings: AppSettings,
        english: String,
        spanish: String,
    ): String = if (selectionUsesSpanish(settings)) spanish else english

    private fun selectionUiText(settings: AppSettings, key: String): String {
        val es = selectionUsesSpanish(settings)
        return when (key) {
            "undo" -> if (es) "Deshacer" else "Undo"
            "suggested" -> if (es) "Sugeridas" else "Suggested"
            "all_tools" -> if (es) "Todas las herramientas" else "All tools"
            "find_replace" -> if (es) "Buscar y reemplazar" else "Find & replace"
            "find" -> if (es) "Buscar" else "Find"
            "replace_with" -> if (es) "Reemplazar por" else "Replace with"
            "replace_all" -> if (es) "Reemplazar todo" else "Replace all"
            "case_sensitive" -> if (es) "Distinguir mayúsculas y minúsculas" else "Case sensitive"
            "cancel" -> if (es) "Cancelar" else "Cancel"
            "close" -> if (es) "Cerrar" else "Close"
            "apply" -> if (es) "Aplicar" else "Apply"
            "prefix" -> if (es) "Prefijo" else "Prefix"
            "suffix" -> if (es) "Sufijo" else "Suffix"
            "prefix_suffix" -> if (es) "Prefijo / Sufijo" else "Prefix / Suffix"
            "repeat_text" -> if (es) "Repetir texto" else "Repeat text"
            "repeat_count" -> if (es) "Cantidad" else "Count"
            "separator" -> if (es) "Separador" else "Separator"
            "separator_hint" -> if (es) "Usa \\n para salto de línea y \\t para tabulación." else "Use \\n for a new line and \\t for a tab."
            "text_counter" -> if (es) "Contador de texto" else "Text counter"
            "characters" -> if (es) "Caracteres" else "Characters"
            "characters_no_spaces" -> if (es) "Caracteres sin espacios" else "Characters without spaces"
            "words" -> if (es) "Palabras" else "Words"
            "lines" -> if (es) "Líneas" else "Lines"
            else -> key
        }
    }

    private fun selectionActionTitle(
        id: String,
        settings: AppSettings,
        fallback: String,
    ): String {
        if (!selectionUsesSpanish(settings)) {
            return when (id) {
                "wrap_guillemets" -> "Guillemets"
                "wrap_parentheses" -> "Parentheses"
                "wrap_question" -> "Question marks"
                "wrap_exclamation" -> "Exclamation marks"
                "wrap_brackets" -> "Brackets"
                "wrap_double_asterisk" -> "Double asterisk"
                "wrap_double_underscore" -> "Double underscore"
                SELECTION_FIND_REPLACE_ID -> "Find & replace"
                SELECTION_TEXT_COUNTER_ID -> "Text counter"
                SELECTION_REPEAT_TEXT_ID -> "Repeat text"
                SELECTION_PREFIX_SUFFIX_ID -> "Prefix / Suffix"
                else -> fallback.ifBlank { id }
            }
        }
        return when (id) {
            "uppercase" -> "Mayúsculas"
            "lowercase" -> "Minúsculas"
            "sentence_case" -> "Tipo oración"
            "title_case" -> "Capitalizar palabras"
            "wrap_guillemets" -> "Comillas angulares"
            "wrap_parentheses" -> "Paréntesis"
            "wrap_question" -> "Interrogación"
            "wrap_exclamation" -> "Exclamación"
            "wrap_brackets" -> "Corchetes"
            "wrap_double_asterisk" -> "Doble asterisco"
            "wrap_double_underscore" -> "Doble guion bajo"
            "remove_diacritics" -> "Quitar diacríticos"
            "space_underscore" -> "Espacios a guiones bajos"
            "space_dash" -> "Espacios a guiones"
            "underscore_space" -> "Guiones bajos a espacios"
            "dash_space" -> "Guiones a espacios"
            "trim_spaces" -> "Quitar espacios de los extremos"
            "remove_all_spaces" -> "Eliminar todos los espacios"
            "delete_blank_lines" -> "Eliminar líneas vacías"
            "remove_duplicate_lines" -> "Eliminar líneas duplicadas"
            "remove_duplicate_words" -> "Eliminar palabras duplicadas"
            "remove_line_breaks" -> "Quitar saltos de línea"
            "sort_lines" -> "Ordenar líneas"
            "number_lines" -> "Numerar líneas"
            "reverse_text" -> "Invertir texto"
            "reverse_lines" -> "Invertir líneas"
            "reverse_words" -> "Invertir palabras"
            "math_replace" -> "Calcular expresión"
            "math_append" -> "Calcular y añadir resultado"
            "number_space" -> "Miles con espacios"
            "number_period" -> "Miles con punto"
            "number_comma" -> "Miles con coma"
            SELECTION_FIND_REPLACE_ID -> "Buscar y reemplazar"
            SELECTION_TEXT_COUNTER_ID -> "Contador de texto"
            SELECTION_REPEAT_TEXT_ID -> "Repetir texto"
            SELECTION_PREFIX_SUFFIX_ID -> "Prefijo / Sufijo"
            else -> fallback.ifBlank { id }
        }
    }

    private fun selectionActionDescription(
        id: String,
        settings: AppSettings,
        fallback: String,
    ): String {
        if (!selectionUsesSpanish(settings)) return when (id) {
            "wrap_guillemets" -> "Wrap the selection in guillemets"
            "wrap_parentheses" -> "Wrap the selection in parentheses"
            "wrap_question" -> "Wrap the selection in Spanish question marks"
            "wrap_exclamation" -> "Wrap the selection in Spanish exclamation marks"
            "wrap_brackets" -> "Wrap the selection in brackets"
            "wrap_double_asterisk" -> "Wrap the selection in double asterisks"
            "wrap_double_underscore" -> "Wrap the selection in double underscores"
            SELECTION_FIND_REPLACE_ID -> "Replace occurrences only inside the selected text"
            SELECTION_TEXT_COUNTER_ID -> "Count characters, words and lines"
            SELECTION_REPEAT_TEXT_ID -> "Repeat the selected text a chosen number of times"
            SELECTION_PREFIX_SUFFIX_ID -> "Add text before and after the selection"
            else -> fallback
        }
        return when (id) {
            "uppercase" -> "Convertir la selección a mayúsculas"
            "lowercase" -> "Convertir la selección a minúsculas"
            "sentence_case" -> "Aplicar mayúscula al inicio de cada oración"
            "title_case" -> "Capitalizar la primera letra de cada palabra"
            "wrap_guillemets" -> "Envolver la selección entre « y »"
            "wrap_parentheses" -> "Envolver la selección entre paréntesis"
            "wrap_question" -> "Envolver la selección entre ¿ y ?"
            "wrap_exclamation" -> "Envolver la selección entre ¡ y !"
            "wrap_brackets" -> "Envolver la selección entre corchetes"
            "wrap_double_asterisk" -> "Envolver la selección entre dobles asteriscos"
            "wrap_double_underscore" -> "Envolver la selección entre dobles guiones bajos"
            "remove_diacritics" -> "Convertir á, é, ñ, etc. a caracteres simples"
            "trim_spaces" -> "Eliminar espacios al inicio y al final"
            "remove_all_spaces" -> "Eliminar todos los espacios y caracteres en blanco"
            "delete_blank_lines" -> "Eliminar líneas vacías"
            "remove_duplicate_lines" -> "Conservar sólo la primera aparición de cada línea"
            "remove_duplicate_words" -> "Conservar sólo la primera aparición de cada palabra"
            "remove_line_breaks" -> "Unir las líneas con espacios"
            "sort_lines" -> "Ordenar alfabéticamente las líneas seleccionadas"
            "number_lines" -> "Agregar numeración consecutiva a las líneas"
            "reverse_text" -> "Invertir todos los caracteres"
            "reverse_lines" -> "Invertir el orden de las líneas"
            "reverse_words" -> "Invertir el orden de las palabras"
            SELECTION_FIND_REPLACE_ID -> "Buscar y reemplazar sólo dentro de la selección"
            SELECTION_TEXT_COUNTER_ID -> "Contar caracteres, palabras y líneas"
            SELECTION_REPEAT_TEXT_ID -> "Repetir la selección la cantidad indicada"
            SELECTION_PREFIX_SUFFIX_ID -> "Agregar texto antes y después de la selección"
            else -> fallback
        }
    }

    private fun selectionToolGroup(id: String): String = when (id) {
        SELECTION_FIND_REPLACE_ID, "sort_lines", SELECTION_TEXT_COUNTER_ID, SELECTION_REPEAT_TEXT_ID,
        "uppercase", "lowercase", "sentence_case", "title_case" -> "basic"
        "trim_spaces", "remove_all_spaces", "delete_blank_lines", "remove_duplicate_lines",
        "remove_duplicate_words", "remove_line_breaks" -> "remove"
        SELECTION_PREFIX_SUFFIX_ID, "number_lines", "reverse_text", "reverse_lines", "reverse_words" -> "edit"
        else -> "format"
    }

    private fun selectionGroupTitle(group: String, settings: AppSettings): String {
        val es = selectionUsesSpanish(settings)
        return when (group) {
            "basic" -> if (es) "Herramientas básicas" else "Basic tools"
            "remove" -> if (es) "Limpiar / eliminar" else "Remove tools"
            "edit" -> if (es) "Editar" else "Edit tools"
            else -> if (es) "Formato y utilidades" else "Format & utilities"
        }
    }

    private fun applySelectionToolbarAction(actionId: String) {
        val state = selectionToolbarState ?: return
        clearAccessibilityCache()
        val node = findAnchoredEditor(state.anchor, requireActiveWindow = false) ?: run {
            hideSelectionToolbar()
            return
        }
        try {
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) {
                hideSelectionToolbar()
                return
            }
            runCatching { node.refresh() }
            val text = editableText(node)

            val currentStart = node.textSelectionStart
            val currentEnd = node.textSelectionEnd
            val currentRange = if (
                currentStart in 0..text.length &&
                currentEnd in 0..text.length &&
                currentStart != currentEnd
            ) {
                minOf(currentStart, currentEnd) to maxOf(currentStart, currentEnd)
            } else null

            val storedRange = if (
                state.start in 0..text.length &&
                state.end in state.start..text.length &&
                text.substring(state.start, state.end) == state.selectedText
            ) {
                state.start to state.end
            } else null

            val (start, end) = currentRange ?: storedRange ?: run {
                hideSelectionToolbar()
                return
            }
            val outcome = actionEngine.processSelectedRange(actionId, text, start, end) ?: return
            val settings = settingsRepository.settings.value
            hideSelectionToolbar()
            if (applySelectedTextOutcome(node, text, outcome, settings)) {
                pushSelectionUndo(state.anchor, text, start, end, outcome)
                lastAppliedText = outcome.text
                lastAppliedAt = SystemClock.elapsedRealtime()
                if (settings.hapticFeedback) vibrate()
            }
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun applySelectedTextOutcome(
        node: AccessibilityNodeInfo,
        originalText: String,
        outcome: SelectedTextOutcome,
        settings: AppSettings,
    ): Boolean {
        val nativeEditor = isNativeEditText(node)
        if (nativeEditor && writeViaSetText(
                node,
                outcome.text,
                outcome.selectionStart,
                outcome.selectionEnd,
            )
        ) {
            return true
        }

        val pasted = pasteReplacement(
            node = node,
            start = outcome.selectionStart,
            end = outcome.selectionStart + (
                originalText.length - outcome.text.length + outcome.replacement.length
            ),
            replacement = outcome.replacement,
            cursor = outcome.selectionEnd,
        ) && setSelection(node, outcome.selectionStart, outcome.selectionEnd)
        if (pasted) return true

        return !nativeEditor && writeViaSetText(
            node,
            outcome.text,
            outcome.selectionStart,
            outcome.selectionEnd,
        )
    }

    private fun scheduleSelectionToolbarValidation() {
        if (selectionToolbar == null || selectionToolbarState == null) return
        cancelSelectionToolbarValidation()
        val validation = Runnable { validateSelectionToolbar() }
        selectionToolbarValidation = validation
        mainHandler.postDelayed(validation, SELECTION_TOOLBAR_VALIDATION_DELAY_MS)
    }

    private fun cancelSelectionToolbarValidation() {
        selectionToolbarValidation?.let(mainHandler::removeCallbacks)
        selectionToolbarValidation = null
    }

    private fun validateSelectionToolbar() {
        selectionToolbarValidation = null
        val state = selectionToolbarState ?: return
        val node = findAnchoredEditor(state.anchor, requireActiveWindow = false) ?: run {
            hideSelectionToolbar()
            return
        }
        try {
            runCatching { node.refresh() }
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) {
                hideSelectionToolbar()
                return
            }
            val text = editableText(node)
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            val currentSelectionMatches = start in 0..text.length &&
                end in 0..text.length &&
                start != end &&
                minOf(start, end) == state.start &&
                maxOf(start, end) == state.end &&
                text.substring(state.start, state.end) == state.selectedText
            val storedSelectionStillExists = state.start in 0..text.length &&
                state.end in state.start..text.length &&
                text.substring(state.start, state.end) == state.selectedText

            if (!currentSelectionMatches && !storedSelectionStillExists) {
                hideSelectionToolbar()
            }
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun hideSelectionGroupOverlay() {
        val overlay = selectionGroupOverlay
        selectionGroupOverlay = null
        if (overlay != null) {
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay)
            }
        }
    }

    private fun hideSelectionToolbar() {
        hideSelectionGroupOverlay()
        cancelPendingSelectionToolbar()
        cancelSelectionToolbarValidation()
        val overlay = selectionToolbar
        selectionToolbar = null
        selectionToolbarWindowParams = null
        selectionToolbarState = null
        if (overlay != null) {
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay)
            }
        }
    }

    private fun showAllSuggestionsForFocusedInput() {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        try {
            if (focused == null || !focused.isEditable || focused.isPassword || isPasswordInput(focused.inputType)) return
            val packageName = focused.packageName?.toString().orEmpty()
            if (packageName.isBlank()) return
            val settings = settingsRepository.settings.value
            if (!settings.expansionEnabled || settings.isPaused || packageName in settings.globallyExcludedPackages) return
            val text = editableText(focused)
            val cursor = focused.textSelectionEnd.takeIf { it in 0..text.length } ?: text.length
            showSuggestions(focused, text, cursor, packageName, settings, showAll = true)
        } finally {
            focused?.recycle()
            root.recycle()
        }
    }

    private fun isPasswordInput(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun editableText(node: AccessibilityNodeInfo): String =
        AccessibilityEditorText.content(node.isShowingHintText, node.text)

    /**
     * Reads textual clipboard content when Android exposes it to this service.
     * A null result means "not readable here", not "clipboard contains empty text".
     */
    private fun readClipboardTextOrNull(): String? =
        clipboardMonitor.resolve(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)

    private fun readClipboardText(): String = readClipboardTextOrNull().orEmpty()

    /**
     * Returns the last cached clipboard text without querying ClipboardManager.
     *
     * Use this for hot paths (e.g. per-keystroke action detection). On Samsung
     * One UI 8.5 / Android 16, calling [ClipboardManager.getPrimaryClip] from
     * an unfocused accessibility service triggers a system clipboard-access
     * event that dismisses the soft keyboard roughly 1.7 s later (issue #6).
     * The clipboard listener registered by [ClipboardMonitor.start] keeps this
     * cache fresh whenever the user copies text; on OEMs that suppress the
     * listener for background apps the value may be stale, but that only
     * affects paste-style actions, which fall back to the clipboard-capture
     * overlay through [readClipboardTextOrNull].
     */
    private fun readClipboardTextCached(): String = clipboardMonitor.cachedText.orEmpty()

    private fun isClipboardInsertAction(actionId: String): Boolean =
        actionId == "paste" || actionId == "paste_numbers" || actionId == "clipboard_history"

    private fun renderMatch(
        expansion: ExpansionMatch,
        manualIndex: Int? = null,
    ): RenderedTemplate {
        val clipboardText = readClipboardTextOrNull()
        Log.d(TAG, "renderMatch: clipboard=${clipboardText?.let { "${it.length} chars" } ?: "null (will use marker)"}")
        return TemplateRenderer(
            clipboard = { clipboardText ?: CLIPBOARD_PASTE_MARKER },
            matchResolver = { trigger ->
                repository.matches.value.firstOrNull { nested ->
                    nested.runsOnAndroid && nested.triggers.any {
                        it.pattern.equals(trigger, ignoreCase = true)
                    }
                }
            },
        ).render(
            expansion.match,
            expansion,
            manualIndex = manualIndex,
            globalVariables = settingsRepository.settings.value.globalVariables,
        )
    }

    // ── Clipboard expansion ─────────────────────────────────────────────

    private fun applyExpansion(
        node: AccessibilityNodeInfo,
        originalText: String,
        match: ExpansionMatch,
        rendered: RenderedTemplate,
        packageName: String,
        settings: AppSettings,
        clipboardCaptureAttempted: Boolean = false,
    ): Boolean {
        if (rendered.unresolvedTokens.isNotEmpty() || !match.match.runsOnAndroid) {
            Log.w(TAG, "Blocked unsafe expansion: ${rendered.unresolvedTokens}")
            return false
        }

        val applied = engine.applyMatch(originalText, match, rendered)
        val hasMarkers = applied.text.contains(CLIPBOARD_PASTE_MARKER)

        // ── resolve clipboard markers ───────────────────────────────────
        val clipboardText = if (hasMarkers) readClipboardTextOrNull() else null
        Log.d(TAG, "applyExpansion: hasMarkers=$hasMarkers clipboardCaptureAttempted=$clipboardCaptureAttempted clipboardText=${clipboardText?.let { "${it.length}ch" } ?: "null"}")
        val (finalText, finalCursor) = when {
            hasMarkers && clipboardText != null ->
                substituteClipboardMarkers(applied.text, applied.cursor, clipboardText)

            hasMarkers && !clipboardCaptureAttempted -> {
                Log.d(TAG, "applyExpansion: launching clipboard capture")
                launchClipboardCapture(node, packageName, settings)
                return false
            }

            hasMarkers -> {
                Log.w(TAG, "Clipboard still unresolved after capture; inserting empty")
                substituteClipboardMarkers(applied.text, applied.cursor, "")
            }

            else -> applied.text to applied.cursor
        }

        // ── write into the editor ───────────────────────────────────────
        // Native EditText widgets accept ACTION_SET_TEXT as an atomic replacement.
        // Everything else — WebView editors (Obsidian/CodeMirror, Chrome, Discord,
        // Capacitor apps), Compose fields exposed as android.view.View, custom OEM
        // widgets — either desyncs its buffer or ignores the action, so we route
        // those through ACTION_PASTE (which Blink treats as a real paste event).
        val nativeEditor = isNativeEditText(node)
        val replacement = replacementSlice(match, applied, originalText, finalText)
        val writeSucceeded = if (nativeEditor) {
            writeViaSetText(
                node = node,
                finalText = finalText,
                selectionStart = finalCursor,
                selectionEnd = finalCursor,
            ) || pasteReplacement(
                node, match.replaceFrom, match.replaceTo,
                replacement, finalCursor,
            )
        } else {
            pasteReplacement(
                node, match.replaceFrom, match.replaceTo,
                replacement, finalCursor,
            ) || writeViaSetText(
                node = node,
                finalText = finalText,
                selectionStart = finalCursor,
                selectionEnd = finalCursor,
            )
        }
        if (!writeSucceeded) return false

        // ── book-keeping ────────────────────────────────────────────────
        val restoredText = originalText.replaceRange(
            match.replaceFrom, match.replaceTo, match.matchedText,
        )
        val expansionAnchor = createSuggestionAnchor(node, packageName)
        reversibleExpansion = ReversibleExpansion(
            anchor = expansionAnchor,
            appliedText = finalText,
            appliedCursor = finalCursor,
            restoredText = restoredText,
            restoredCursor = match.replaceFrom + match.matchedText.length,
            matchId = match.match.id,
            matchedText = match.matchedText,
        )
        pendingSmartCursorCase = if (
            settings.smartCursorCaseEnabled &&
            rendered.cursorOffset < rendered.text.length &&
            SmartCursorCase.classify(originalText.substring(0, match.replaceFrom)) ==
                SmartCursorCase.Context.CONTINUATION
        ) {
            PendingSmartCursorCase(
                anchor = expansionAnchor,
                baselineText = finalText,
                cursor = finalCursor,
                createdAt = SystemClock.elapsedRealtime(),
            )
        } else {
            null
        }
        suppressedExpansion = null
        lastAppliedText = finalText
        lastAppliedAt = SystemClock.elapsedRealtime()
        finishExpansion(match.match, packageName, settings)
        if (rendered.actions.any { it.type == dev.diego.expanda.engine.TemplateActionType.SEND }) {
            mainHandler.postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }, 100L)
        }
        return true
    }

    // ── overlay-based clipboard capture ─────────────────────────────────
    //
    // A 1x1 transparent focusable TYPE_ACCESSIBILITY_OVERLAY window gains
    // input focus (satisfying Android 10+'s clipboard-read check) without
    // collapsing system dialogs or flickering the keyboard. If the overlay
    // cannot gain focus (OEM quirk), we fall back to a transparent activity.

    private fun launchClipboardCapture(
        node: AccessibilityNodeInfo,
        packageName: String,
        settings: AppSettings,
    ) {
        if (pendingClipboardRetry != null) return
        pendingClipboardRetry = PendingClipboardRetry.Expansion(
            anchor = createSuggestionAnchor(node, packageName),
            packageName = packageName,
            settings = settings,
        )
        Log.d(TAG, "Clipboard capture: starting overlay")
        if (!startClipboardOverlay()) {
            Log.w(TAG, "Clipboard capture: overlay failed, trying activity fallback")
            launchClipboardCaptureActivity()
        }
    }

    private fun launchClipboardActionCapture(
        node: AccessibilityNodeInfo,
        packageName: String,
        settings: AppSettings,
        definition: ActionDefinition,
        replaceStart: Int,
        replaceEnd: Int,
    ) {
        if (pendingClipboardRetry != null) return
        pendingClipboardRetry = PendingClipboardRetry.Action(
            anchor = createSuggestionAnchor(node, packageName),
            settings = settings,
            actionId = definition.id,
            shortcut = definition.shortcut,
            replaceStart = replaceStart,
            replaceEnd = replaceEnd,
        )
        Log.d(TAG, "Clipboard action capture: starting overlay for ${definition.id}")
        if (!startClipboardOverlay()) {
            Log.w(TAG, "Clipboard action capture overlay failed, trying activity fallback")
            launchClipboardCaptureActivity()
        }
    }

    private fun startClipboardOverlay(): Boolean {
        removeClipboardOverlay()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        var handled = false
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && !handled) {
                handled = true
                view.post {
                    val text = clipboardMonitor.capture(
                        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager,
                    )
                    Log.d(TAG, "Overlay clipboard read: ${text?.let { "${it.length} chars" } ?: "null"}")
                    removeClipboardOverlay()
                    mainHandler.postDelayed(::onClipboardCaptureComplete, CLIPBOARD_OVERLAY_SETTLE_MS)
                }
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)

        return try {
            wm.addView(view, params)
            clipboardOverlay = view
            val timeout = Runnable {
                if (clipboardOverlay != null) {
                    Log.w(TAG, "Clipboard overlay timeout — falling back to activity")
                    removeClipboardOverlay()
                    launchClipboardCaptureActivity()
                }
            }
            clipboardOverlayTimeout = timeout
            mainHandler.postDelayed(timeout, CLIPBOARD_OVERLAY_TIMEOUT_MS)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not add clipboard overlay", t)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            false
        }
    }

    private fun removeClipboardOverlay() {
        clipboardOverlayTimeout?.let(mainHandler::removeCallbacks)
        clipboardOverlayTimeout = null
        val view = clipboardOverlay ?: return
        clipboardOverlay = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
    }

    private fun launchClipboardCaptureActivity() {
        runCatching {
            startActivity(
                Intent(this, ClipboardCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                },
            )
        }.onFailure {
            Log.w(TAG, "Could not start ClipboardCaptureActivity", it)
            pendingClipboardRetry = null
        }
    }

    private fun onClipboardCaptureComplete() {
        val retry = pendingClipboardRetry ?: return
        pendingClipboardRetry = null
        when (retry) {
            is PendingClipboardRetry.Expansion -> retryClipboardExpansion(retry, attempt = 0)
            is PendingClipboardRetry.Action -> retryClipboardAction(retry, attempt = 0)
        }
    }

    private fun onClipboardCaptureCancel() {
        pendingClipboardRetry = null
    }

    private fun retryClipboardExpansion(retry: PendingClipboardRetry.Expansion, attempt: Int) {
        Log.d(TAG, "retryClipboardExpansion: attempt=$attempt")
        val node = findAnchoredEditor(retry.anchor)
        if (node == null) {
            if (attempt < CLIPBOARD_RETRY_ATTEMPTS) {
                Log.d(TAG, "retryClipboardExpansion: editor not found, scheduling retry ${attempt + 1}")
                mainHandler.postDelayed({
                    retryClipboardExpansion(retry, attempt + 1)
                }, CLIPBOARD_RETRY_DELAY_MS)
                return
            }
            Log.w(TAG, "Clipboard retry: editor not found after $attempt attempts")
            return
        }
        try {
            val text = editableText(node)
            val cursor = node.textSelectionEnd
                .takeIf { it in 0..text.length } ?: text.length
            val candidates = engine.findMatchesAtCursor(
                text, cursor, repository.matches.value, retry.packageName,
            )
            val match = candidates.singleOrNull() ?: return
            val rendered = renderMatch(match)
            applyExpansion(
                node, text, match, rendered,
                retry.packageName, retry.settings,
                clipboardCaptureAttempted = true,
            )
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun retryClipboardAction(retry: PendingClipboardRetry.Action, attempt: Int) {
        Log.d(TAG, "retryClipboardAction: action=${retry.actionId} attempt=$attempt")
        val node = findAnchoredEditor(retry.anchor)
        if (node == null) {
            if (attempt < CLIPBOARD_RETRY_ATTEMPTS) {
                mainHandler.postDelayed({
                    retryClipboardAction(retry, attempt + 1)
                }, CLIPBOARD_RETRY_DELAY_MS)
                return
            }
            Log.w(TAG, "Clipboard action retry: editor not found after $attempt attempts")
            return
        }
        try {
            val originalText = editableText(node)
            val start = retry.replaceStart.coerceIn(0, originalText.length)
            val end = retry.replaceEnd.coerceIn(start, originalText.length)
            val commandText = originalText.replaceRange(start, end, retry.shortcut)
            val commandCursor = start + retry.shortcut.length
            val outcome = actionEngine.execute(
                ActionContext(
                    text = commandText,
                    cursor = commandCursor,
                    selectionStart = commandCursor,
                    selectionEnd = commandCursor,
                    clipboard = clipboardMonitor.cachedText.orEmpty(),
                ),
                enabledActionIds = setOf(retry.actionId),
                shortcutOverrides = mapOf(retry.actionId to retry.shortcut),
            ) ?: return
            if (applyAction(node, originalText, outcome, retry.settings)) {
                lastAppliedText = outcome.text
                lastAppliedAt = SystemClock.elapsedRealtime()
                handleActionRequest(outcome.request, outcome.text)
            }
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun showMatchDisambiguation(
        node: AccessibilityNodeInfo,
        originalText: String,
        candidates: List<ExpansionMatch>,
        packageName: String,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        pendingFormNode = node
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            addView(ui.title(localizedSelectionUi(settings, "Choose snippet", "Elegir fragmento")).apply {
                setPadding(dp(6), dp(2), dp(6), dp(8))
            })
        }
        candidates.forEach { expansion ->
            val match = expansion.match
            val title = match.label.ifBlank {
                match.replace.replace('\n', ' ').take(180)
            }
            val row = ui.body(title).apply {
                if (match.label.isNotBlank()) {
                    val preview = match.replace.replace('\n', ' ').take(180)
                    text = "$title\n$preview"
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = ui.surface()
                isClickable = true
                isFocusable = true
                contentDescription = localizedSelectionUi(settings, "Use $title", "Usar $title")
                setOnClickListener {
                    formOverlay?.let { overlay -> runCatching { windowManager.removeView(overlay) } }
                    formOverlay = null
                    pendingFormNode = null
                    continueExpansion(node, originalText, expansion, packageName, settings)
                }
            }
            content.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) },
            )
        }
        val footer = overlayCancelFooter(
            ui,
            localizedSelectionUi(settings, "Cancel", "Cancelar"),
        ) { hideFormOverlay() }
        val bounds = displayBounds(windowManager)
        val maxContentHeight = (bounds.height() * 0.55f).toInt().coerceAtLeast(dp(180))
        val root = buildPickerOverlayRoot(content, footer, candidates.size, maxContentHeight, ui.panel(22), ui)
        val params = overlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
        }.onFailure {
            pendingFormNode = null
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun continueExpansion(
        node: AccessibilityNodeInfo,
        originalText: String,
        match: ExpansionMatch,
        packageName: String,
        settings: AppSettings,
    ) {
        if (match.match.selectionMode == TemplateSelectionMode.MANUAL && match.match.replacements.size > 1) {
            showTemplateChooser(node, originalText, match, packageName, settings)
            return
        }
        val rendered = renderMatch(match)
        if (rendered.requiresInput) {
            showFormOverlay(node, originalText, match, rendered, packageName, settings)
            return
        }
        applyExpansion(node, originalText, match, rendered, packageName, settings)
    }

    private fun showTemplateChooser(
        node: AccessibilityNodeInfo,
        originalText: String,
        match: ExpansionMatch,
        packageName: String,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        pendingFormNode = node
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            addView(ui.title(localizedSelectionUi(settings, "Choose replacement", "Elegir reemplazo")).apply {
                setPadding(dp(6), dp(2), dp(6), dp(8))
            })
        }
        match.match.replacements.forEachIndexed { index, template ->
            list.addView(ui.body("${index + 1}. ${template.replace('\n', ' ').take(180)}").apply {
                this.text = "${index + 1}. ${template.replace('\n', ' ').take(180)}"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = ui.surface()
                isClickable = true
                isFocusable = true
                contentDescription = localizedSelectionUi(
                    settings,
                    "Use replacement ${index + 1}",
                    "Usar reemplazo ${index + 1}",
                )
                setOnClickListener {
                    val rendered = renderMatch(match, index)
                    formOverlay?.let { overlay -> runCatching { windowManager.removeView(overlay) } }
                    formOverlay = null
                    pendingFormNode = null
                    if (rendered.requiresInput) {
                        showFormOverlay(node, originalText, match, rendered, packageName, settings)
                    } else {
                        try {
                            applyExpansion(node, originalText, match, rendered, packageName, settings)
                        } finally {
                            @Suppress("DEPRECATION")
                            node.recycle()
                        }
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
        val footer = overlayCancelFooter(
            ui,
            localizedSelectionUi(settings, "Cancel", "Cancelar"),
        ) { hideFormOverlay() }
        val bounds = displayBounds(windowManager)
        val maxContentHeight = (bounds.height() * 0.55f).toInt().coerceAtLeast(dp(180))
        val root = buildPickerOverlayRoot(list, footer, match.match.replacements.size, maxContentHeight, ui.panel(22), ui)
        val params = overlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
        }.onFailure {
            pendingFormNode = null
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun showFormOverlay(
        node: AccessibilityNodeInfo,
        originalText: String,
        match: ExpansionMatch,
        rendered: RenderedTemplate,
        packageName: String,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        pendingFormNode = node
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val fields = rendered.fields.distinctBy { it.name }
        if (fields.size == 1 && fields.single().inputType == TemplateFieldInputType.CHOICE) {
            showChoiceOverlay(node, originalText, match, rendered, packageName, settings, fields.single())
            return
        }
        val valueReaders = linkedMapOf<String, () -> String>()
        var firstTextInput: EditText? = null
        val formScroll = BoundedScrollView(this, 0)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            addView(ui.title(localizedSelectionUi(settings, "Complete snippet", "Completar fragmento")).apply {
                setPadding(0, 0, 0, dp(12))
            })
        }
        var previewIndex = 0
        rendered.fields.sortedBy { it.start }.forEach { field ->
            if (field.start >= previewIndex) {
                addPreviewText(panel, rendered.text.substring(previewIndex, field.start), ui)
            }
            if (field.name !in valueReaders) {
                when (field.inputType) {
                    TemplateFieldInputType.TEXT -> {
                        val input = ui.input(field.label, field.defaultValue).apply {
                            hint = ""
                        }
                        if (!field.multiline) {
                            input.setSingleLine(true)
                            input.maxLines = 1
                        }
                        valueReaders[field.name] = { input.text.toString() }
                        if (firstTextInput == null) firstTextInput = input
                        input.scrollIntoViewWhenFocused(formScroll)
                        panel.addView(ui.fieldGroup(field.label, input))
                    }
                    TemplateFieldInputType.CHOICE -> {
                        val spinner = ui.spinner(field.options)
                        valueReaders[field.name] = {
                            field.optionValues.getOrElse(spinner.selectedItemPosition) {
                                spinner.selectedItem?.toString().orEmpty()
                            }
                        }
                        panel.addView(ui.fieldGroup(field.label, spinner))
                    }
                    TemplateFieldInputType.DATE,
                    TemplateFieldInputType.TIME -> {
                        val button = dateTimeFieldButton(field.inputType, field.label, field.defaultValue, ui)
                        valueReaders[field.name] = { button.text.toString() }
                        panel.addView(ui.fieldGroup(field.label, button))
                    }
                }
            } else {
                addPreviewText(panel, "⟦${field.label}⟧", ui)
            }
            previewIndex = maxOf(previewIndex, field.end)
        }
        addPreviewText(panel, rendered.text.substring(previewIndex.coerceAtMost(rendered.text.length)), ui)
        val footer = overlayActionFooter(
            ui,
            primaryLabel = localizedSelectionUi(settings, "Insert", "Insertar"),
            cancelLabel = localizedSelectionUi(settings, "Cancel", "Cancelar"),
            onCancel = { hideFormOverlay() },
            onPrimary = {
                val values = valueReaders.mapValues { it.value.invoke() }
                val completed = rendered.fillFields(values)
                scheduleFormApply(node, 120L) {
                    applyExpansion(node, originalText, match, completed, packageName, settings)
                }
            },
        )
        val bounds = displayBounds(windowManager)
        val maxContentHeight = (bounds.height() * 0.5f).toInt().coerceAtLeast(dp(160))
        formScroll.setMaxHeight(maxContentHeight)
        val root = buildFormOverlayRoot(panel, footer, formScroll, ui.panel(), ui)
        val params = overlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            firstTextInput?.let { first ->
                first.requestFocus()
                first.setSelection(first.text.length)
                first.postDelayed({
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
                }, 150L)
            }
        }.onFailure {
            pendingFormNode = null
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun showChoiceOverlay(
        node: AccessibilityNodeInfo,
        originalText: String,
        match: ExpansionMatch,
        rendered: RenderedTemplate,
        packageName: String,
        settings: AppSettings,
        field: TemplateFieldRequest,
    ) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(ui.title(localizedSelectionUi(settings, "Choose ${field.label}", "Elegir ${field.label}")).apply {
                setPadding(0, 0, 0, dp(8))
            })
            val preview = rendered.text.substring(0, field.start) + "[…]" +
                rendered.text.substring(field.end.coerceAtMost(rendered.text.length))
            addView(ui.body(preview, secondary = true).apply {
                setPadding(0, 0, 0, dp(10))
            })
        }
        field.options.forEachIndexed { index, option ->
            panel.addView(ui.choiceOption(option) {
                val completed = rendered.fillFields(
                    mapOf(field.name to field.optionValues.getOrElse(index) { option }),
                )
                scheduleFormApply(node, 80L) {
                    applyExpansion(node, originalText, match, completed, packageName, settings)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) })
        }
        val footer = overlayCancelFooter(
            ui,
            localizedSelectionUi(settings, "Cancel", "Cancelar"),
        ) { hideFormOverlay() }
        val bounds = displayBounds(windowManager)
        val maxContentHeight = (bounds.height() * 0.55f).toInt().coerceAtLeast(dp(180))
        val root = buildPickerOverlayRoot(panel, footer, field.options.size, maxContentHeight, ui.panel(), ui)
        val params = overlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
        }.onFailure {
            pendingFormNode = null
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun dismissVaultOverlayForSystemContext(event: AccessibilityEvent) {
        if (!vaultOverlayActive || formOverlay == null) return
        if (SystemClock.elapsedRealtime() - vaultOverlayShownAt < VAULT_SYSTEM_UI_GRACE_MS) return

        // Use the package reported by the WINDOW event itself. rootInActiveWindow
        // can remain anchored to the app beneath a TYPE_ACCESSIBILITY_OVERLAY,
        // which is why Home/Recents/notifications previously failed to dismiss it.
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank()) return
        if (eventPackage == applicationContext.packageName) return
        if (eventPackage == vaultOverlayOriginPackage) return

        val imePackage = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                .orEmpty()
        }.getOrDefault("")
        if (eventPackage == imePackage) return

        // Any other window package means the user left the vault's context:
        // System UI, launcher, recents, notification target, or another app.
        hideFormOverlay()
    }

    private fun vaultSystemBarInsets(windowManager: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            return insets.top to insets.bottom
        }
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val navId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val top = if (statusId != 0) resources.getDimensionPixelSize(statusId) else 0
        val bottom = if (navId != 0) resources.getDimensionPixelSize(navId) else 0
        return top to bottom
    }

    private fun vaultOverlayDialogParams(
        windowManager: WindowManager,
        softInput: Boolean,
    ): WindowManager.LayoutParams {
        val bounds = displayBounds(windowManager)
        val (topInset, bottomInset) = vaultSystemBarInsets(windowManager)
        val usableHeight = (bounds.height() - topInset - bottomInset)
            .coerceAtLeast(dp(220))
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            usableHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = topInset
            dimAmount = 0.35f
            if (softInput) {
                @Suppress("DEPRECATION")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }
    }

    private fun markVaultOverlayShown(anchor: SuggestionAnchor?) {
        vaultOverlayActive = true
        val fallbackPackage = rootInActiveWindow?.let { node ->
            try {
                node.packageName?.toString()
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        vaultOverlayOriginPackage = anchor?.packageName ?: fallbackPackage
        vaultOverlayShownAt = SystemClock.elapsedRealtime()
    }

    private fun overlayDialogParams(windowManager: WindowManager, softInput: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            dimAmount = 0.35f
            if (softInput) {
                @Suppress("DEPRECATION")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }

    private fun dismissibleOverlayRoot(
        card: View,
        windowManager: WindowManager,
        onDismiss: () -> Unit = { hideFormOverlay() },
        verticalOffsetPx: Int = 0,
    ): FrameLayout {
        val screenWidth = displayBounds(windowManager).width()
        val cardWidth = (screenWidth * 0.9f).toInt()
        val backdrop = View(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = localizedSelectionUi(
                settingsRepository.settings.value,
                "Close dialog",
                "Cerrar ventana",
            )
            setOnClickListener { onDismiss() }
        }
        card.isClickable = true
        card.translationY = verticalOffsetPx.toFloat()
        return FrameLayout(this).apply {
            addView(
                backdrop,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                card,
                FrameLayout.LayoutParams(
                    cardWidth,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    private fun overlayCancelFooter(
        ui: OverlayViews,
        cancelLabel: String = "Cancel",
        onCancel: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(56)
        addView(
            ui.footerButton(cancelLabel, primary = false, onCancel),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48),
            ),
        )
    }

    private fun overlayActionFooter(
        ui: OverlayViews,
        primaryLabel: String = "Insert",
        cancelLabel: String = "Cancel",
        onCancel: () -> Unit,
        onPrimary: () -> Unit,
        onPrimaryLongClick: (() -> Unit)? = null,
        onPrimarySwipe: ((PrimarySwipeDirection) -> Unit)? = null,
        swipeHapticEnabled: Boolean = false,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(56)
        addView(
            ui.footerButton(cancelLabel, primary = false, onCancel),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48),
            ).apply { marginEnd = dp(8) },
        )
        val primaryButton = ui.footerButton(primaryLabel, primary = true, onPrimary)
        if (onPrimaryLongClick != null && onPrimarySwipe == null) {
            primaryButton.setOnLongClickListener {
                onPrimaryLongClick()
                true
            }
        }
        if (onPrimarySwipe != null) {
            var downX = 0f
            var downY = 0f
            var downAt = 0L
            var gestureMoved = false
            var activeDirection: PrimarySwipeDirection? = null
            val touchSlop = ViewConfiguration.get(this@ExpansionAccessibilityService).scaledTouchSlop
            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            val swipeThreshold = maxOf(
                ViewConfiguration.get(this@ExpansionAccessibilityService).scaledTouchSlop * 3,
                dp(24),
            ).toFloat()
            // Keep the gesture discoverable without letting the button leave
            // its visual slot or collide with the adjacent Cancel pill.
            val travel = dp(4).toFloat()
            val dragResistance = 0.12f

            fun resetSwipeVisual() {
                primaryButton.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start()
                primaryButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                primaryButton.compoundDrawablePadding = 0
            }

            fun showDirection(direction: PrimarySwipeDirection?) {
                if (direction == activeDirection) return
                activeDirection = direction
                // Always keep the icon inline with the label. Putting it above
                // the text inside a fixed 48dp button caused the label to clip
                // during upward swipes.
                val gestureIcon = when (direction) {
                    PrimarySwipeDirection.LEFT,
                    PrimarySwipeDirection.RIGHT,
                    -> R.drawable.ic_select_fine
                    PrimarySwipeDirection.UP -> R.drawable.ic_copy_fine
                    null -> 0
                }
                primaryButton.setCompoundDrawablesWithIntrinsicBounds(
                    gestureIcon,
                    0,
                    0,
                    0,
                )
                primaryButton.compoundDrawablePadding = if (direction == null) 0 else dp(6)
                val activeScale = if (direction == null) 1f else 0.98f
                primaryButton.scaleX = activeScale
                primaryButton.scaleY = activeScale
                if (direction != null && swipeHapticEnabled) {
                    primaryButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }

            primaryButton.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        primaryButton.animate().cancel()
                        downX = event.rawX
                        downY = event.rawY
                        downAt = SystemClock.uptimeMillis()
                        gestureMoved = false
                        activeDirection = null
                        primaryButton.cancelLongPress()
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (
                            !gestureMoved &&
                            (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)
                        ) {
                            gestureMoved = true
                            primaryButton.cancelLongPress()
                        }
                        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                        val candidate = when {
                            !horizontal && dy <= -swipeThreshold -> PrimarySwipeDirection.UP
                            horizontal && dx <= -swipeThreshold -> PrimarySwipeDirection.LEFT
                            horizontal && dx >= swipeThreshold -> PrimarySwipeDirection.RIGHT
                            else -> null
                        }
                        if (horizontal) {
                            primaryButton.translationX =
                                (dx * dragResistance).coerceIn(-travel, travel)
                            primaryButton.translationY = 0f
                        } else {
                            // Never translate the pill vertically: the footer is
                            // height-constrained and clips translated children.
                            // Upward feedback is conveyed by icon + scale only.
                            primaryButton.translationX = 0f
                            primaryButton.translationY = 0f
                        }
                        showDirection(candidate)
                        candidate != null || gestureMoved
                    }
                    MotionEvent.ACTION_UP -> {
                        val direction = activeDirection
                        val wasMoved = gestureMoved
                        val shouldLongPress =
                            direction == null &&
                                !wasMoved &&
                                onPrimaryLongClick != null &&
                                SystemClock.uptimeMillis() - downAt >= longPressTimeout
                        resetSwipeVisual()
                        activeDirection = null
                        gestureMoved = false
                        when {
                            direction != null -> {
                                onPrimarySwipe(direction)
                                true
                            }
                            shouldLongPress -> {
                                onPrimaryLongClick?.invoke()
                                true
                            }
                            wasMoved -> true
                            else -> false
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        val consume = activeDirection != null || gestureMoved
                        resetSwipeVisual()
                        activeDirection = null
                        gestureMoved = false
                        consume
                    }
                    else -> activeDirection != null || gestureMoved
                }
            }
        }
        addView(
            primaryButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48),
            ),
        )
    }

    private fun buildOverlayRoot(
        content: LinearLayout,
        footer: LinearLayout,
        background: android.graphics.drawable.Drawable,
        ui: OverlayViews,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.background = background
        addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            ui.divider(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ui.dp(1),
            ).apply {
                topMargin = ui.dp(4)
                marginStart = ui.dp(16)
                marginEnd = ui.dp(16)
            },
        )
        addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun buildPickerOverlayRoot(
        content: LinearLayout,
        footer: LinearLayout,
        itemCount: Int,
        maxContentHeightPx: Int,
        background: android.graphics.drawable.Drawable,
        ui: OverlayViews,
    ): LinearLayout = if (itemCount > 6) {
        buildScrollableOverlayRoot(content, footer, maxContentHeightPx, background, ui)
    } else {
        buildOverlayRoot(content, footer, background, ui)
    }

    private fun buildFormOverlayRoot(
        content: LinearLayout,
        footer: LinearLayout,
        scroll: BoundedScrollView,
        background: android.graphics.drawable.Drawable,
        ui: OverlayViews,
    ): LinearLayout {
        scroll.addView(content)
        attachKeyboardScrollAssist(scroll, content)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.background = background
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                ui.divider(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ui.dp(1),
                ).apply {
                    topMargin = ui.dp(4)
                    marginStart = ui.dp(16)
                    marginEnd = ui.dp(16)
                },
            )
            addView(
                footer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun buildScrollableOverlayRoot(
        content: LinearLayout,
        footer: LinearLayout,
        maxContentHeightPx: Int,
        background: android.graphics.drawable.Drawable,
        ui: OverlayViews,
    ): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.background = background
        }
        val scroll = BoundedScrollView(this, maxContentHeightPx).apply { addView(content) }
        attachKeyboardScrollAssist(scroll, content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            ui.divider(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ui.dp(1),
            ).apply {
                topMargin = ui.dp(4)
                marginStart = ui.dp(16)
                marginEnd = ui.dp(16)
            },
        )
        root.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return root
    }

    private fun attachKeyboardScrollAssist(scroll: BoundedScrollView, content: LinearLayout) {
        val baseBottomPadding = content.paddingBottom
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = Rect()
            scroll.getWindowVisibleDisplayFrame(visible)
            val screenHeight = scroll.rootView.height
            val keyboardHeight = (screenHeight - visible.bottom).coerceAtLeast(0)
            val extra = if (keyboardHeight > screenHeight * 0.12) keyboardHeight else 0
            val targetBottom = baseBottomPadding + extra
            if (content.paddingBottom != targetBottom) {
                content.setPadding(
                    content.paddingLeft,
                    content.paddingTop,
                    content.paddingRight,
                    targetBottom,
                )
            }
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(listener)
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                scroll.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        })
    }

    private fun EditText.scrollIntoViewWhenFocused(scroll: ScrollView) {
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            postDelayed({
                val rect = Rect(0, 0, width, height)
                requestRectangleOnScreen(rect, false)
                scroll.post { scroll.smoothScrollTo(0, bottom) }
            }, 180L)
        }
    }

    private fun addPreviewText(panel: LinearLayout, value: String, ui: OverlayViews) {
        if (value.isEmpty()) return
        panel.addView(ui.body(value, 14f, secondary = true).apply {
            setTextIsSelectable(false)
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun dateTimeFieldButton(
        inputType: TemplateFieldInputType,
        label: String,
        defaultValue: String,
        ui: OverlayViews,
    ): TextView = ui.pickerField(
        defaultValue.ifBlank {
            SimpleDateFormat(
                if (inputType == TemplateFieldInputType.DATE) "yyyy-MM-dd" else "HH:mm",
                Locale.getDefault(),
            ).format(Calendar.getInstance().time)
        },
    ).apply {
        val calendar = Calendar.getInstance()
        contentDescription = label
        setOnClickListener {
            val dialogContext = ContextThemeWrapper(
                this@ExpansionAccessibilityService,
                if (ui.theme.dark) R.style.Theme_Expanda_Dialog_Dark else R.style.Theme_Expanda_Dialog_Light,
            )
            val dialog = if (inputType == TemplateFieldInputType.DATE) {
                DatePickerDialog(
                    dialogContext,
                    { _, year, month, day -> text = "%04d-%02d-%02d".format(year, month + 1, day) },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH),
                )
            } else {
                TimePickerDialog(
                    dialogContext,
                    { _, hour, minute -> text = "%02d:%02d".format(hour, minute) },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true,
                )
            }
            activeFieldDialog?.dismiss()
            activeFieldDialog = dialog
            dialog.setOnDismissListener { if (activeFieldDialog === dialog) activeFieldDialog = null }
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.show()
        }
    }

    private fun scheduleFormApply(
        node: AccessibilityNodeInfo,
        delayMillis: Long,
        apply: () -> Unit,
    ) {
        formOverlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        formOverlay = null
        pendingFormApply?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            if (pendingFormNode !== node) return@Runnable
            pendingFormApply = null
            pendingFormNode = null
            try {
                apply()
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        pendingFormApply = task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun hideFormOverlay() {
        pendingFormApply?.let(mainHandler::removeCallbacks)
        pendingFormApply = null
        activeFieldDialog?.dismiss()
        activeFieldDialog = null
        val overlay = formOverlay
        formOverlay = null
        vaultOverlayActive = false
        vaultOverlayOriginPackage = null
        vaultOverlayShownAt = 0L
        if (overlay != null) runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay) }
        pendingFormNode?.let {
            @Suppress("DEPRECATION")
            it.recycle()
        }
        pendingFormNode = null
    }

    private fun vibrate(durationMs: Long = HAPTIC_CONFIRM_MS) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                durationMs,
                VibrationEffect.DEFAULT_AMPLITUDE,
            ),
        )
    }

    private fun vibrateTick() = vibrate(HAPTIC_TICK_MS)

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            },
        )

    /**
     * Positions the caret / selection after an [AccessibilityNodeInfo.ACTION_SET_TEXT] call.
     *
     * WebView-based editors (Gmail composer, Chrome, Obsidian) apply the text change
     * asynchronously in Blink. The immediate ACTION_SET_SELECTION lands before the
     * DOM catches up and Chromium collapses the caret to position 0. We clear our
     * per-service cache, refresh the node so future reads see fresh data, apply the
     * selection once, and schedule a second attempt on the next frame that only
     * fires if the caret really did drift from what we requested.
     */
    private fun commitSelectionAfterSetText(
        node: AccessibilityNodeInfo,
        start: Int,
        end: Int = start,
    ) {
        clearAccessibilityCache()
        runCatching { node.refresh() }
        setSelection(node, start, end)

        val packageName = node.packageName?.toString().orEmpty()
        if (packageName.isEmpty()) return
        val anchor = createSuggestionAnchor(node, packageName)
        mainHandler.postDelayed(
            {
                val fresh = findAnchoredEditor(anchor) ?: return@postDelayed
                try {
                    val actualEnd = fresh.textSelectionEnd
                    val actualStart = fresh.textSelectionStart
                    if (actualEnd != end || actualStart != start) {
                        runCatching { fresh.refresh() }
                        setSelection(fresh, start, end)
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    fresh.recycle()
                }
            },
            WEBVIEW_SELECTION_RETRY_DELAY_MS,
        )
    }

    /** Drops the framework's per-service node cache; a no-op below API 33. */
    private fun clearAccessibilityCache() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { clearCache() }
        }
    }

    /**
     * True when [node] is a native Android EditText / AutoCompleteTextView (or any
     * subclass). ACTION_SET_TEXT is only reliable for these; anything else — WebView
     * editors (Chrome, Discord message input), Compose text fields exposed with a
     * generic class name, custom OEM widgets, canvas-based editors — is routed
     * through ACTION_PASTE.
     *
     * An explicit package override lets us force paste for apps that lie about the
     * class name; today that list is empty because the offenders we've seen so far
     * (Obsidian) desync with paste too, so forcing it makes things worse.
     */
    private fun isNativeEditText(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()
        val packageName = node.packageName?.toString()
        if (WebViewEditorDetection.requiresPasteWrite(packageName)) return false
        return WebViewEditorDetection.isNativeEditorClass(className)
    }

    /**
     * Slice of [finalText] that must land in `[match.replaceFrom, match.replaceTo)`
     * inside the editor. Everything outside that range stays untouched.
     */
    private fun replacementSlice(
        match: ExpansionMatch,
        applied: AppliedExpansion,
        originalText: String,
        finalText: String,
    ): String {
        val replacementLength = applied.text.length - originalText.length +
            (match.replaceTo - match.replaceFrom)
        return finalText.substring(
            match.replaceFrom,
            (match.replaceFrom + replacementLength).coerceAtMost(finalText.length),
        )
    }

    /** Apply an action as one atomic field update, with the existing fallback for editors that reject ACTION_SET_TEXT. */
    private fun applyAction(
        node: AccessibilityNodeInfo,
        originalText: String,
        outcome: ActionOutcome,
        settings: AppSettings,
    ): Boolean {
        return setFieldText(
            node = node,
            originalText = originalText,
            newText = outcome.text,
            selectionStart = outcome.selectionStart,
            selectionEnd = outcome.selectionEnd,
            settings = settings,
        )
    }

    private fun setFieldText(
        node: AccessibilityNodeInfo,
        originalText: String,
        newText: String,
        selectionStart: Int,
        selectionEnd: Int,
        settings: AppSettings,
    ): Boolean {
        // Same rationale as applyExpansion: only trust ACTION_SET_TEXT on native EditText.
        val nativeEditor = isNativeEditText(node)
        if (nativeEditor && writeViaSetText(node, newText, selectionStart, selectionEnd)) {
            return true
        }
        val pasted = pasteReplacement(
            node = node,
            start = 0,
            end = originalText.length,
            replacement = newText,
            cursor = selectionStart,
        ) && setSelection(node, selectionStart, selectionEnd)
        if (pasted) return true
        // Last-resort fallback for exotic non-native editors that refuse ACTION_PASTE.
        return !nativeEditor && writeViaSetText(node, newText, selectionStart, selectionEnd)
    }

    /**
     * Sends the whole field text through [AccessibilityNodeInfo.ACTION_SET_TEXT] and
     * commits the resulting selection with the WebView-aware retry helper.
     * Returns false if the editor rejected the action.
     */
    private fun writeViaSetText(
        node: AccessibilityNodeInfo,
        finalText: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        commitSelectionAfterSetText(node, selectionStart, selectionEnd)
        return true
    }

    private fun clearExpansionUndo() {
        reversibleExpansion = null
        suppressedExpansion = null
    }

    private fun handleActionRequest(request: ActionRequest?, text: String) {
        when (request) {
            null -> Unit
            is ActionRequest.Copy -> writeClipboard(request.text)
            is ActionRequest.Share -> shareText(request.text)
            ActionRequest.ToggleSuggestions -> {
                val enabled = !settingsRepository.settings.value.suggestionEnabled
                hideSuggestions()
                scope.launch { settingsRepository.setSuggestionEnabled(enabled) }
            }
            ActionRequest.OpenNewSnippet -> openNewSnippetEditor()
            ActionRequest.OpenVault -> showVaultOverlay()
        }
    }

    private fun findVaultTrigger(
        text: String,
        cursor: Int,
    ): Pair<VaultTriggerTarget, String>? {
        if (cursor !in 0..text.length) return null
        val candidates = buildList {
            vaultRepository.entries.value.forEach { entry ->
                TriggerMatcher.matchLiteralSuffix(
                    text = text,
                    cursor = cursor,
                    triggers = entry.triggers,
                    caseSensitive = entry.caseSensitive,
                )?.let { match ->
                    add(VaultTriggerTarget.Entry(entry) to match.trigger)
                }
            }
            vaultRepository.categories.value.forEach { category ->
                TriggerMatcher.matchLiteralSuffix(
                    text = text,
                    cursor = cursor,
                    triggers = category.triggers,
                    caseSensitive = category.caseSensitive,
                )?.let { match ->
                    add(VaultTriggerTarget.Category(category) to match.trigger)
                }
            }
        }
        return candidates.maxByOrNull { (_, trigger) -> trigger.length }
    }

    private fun showVaultOverlay(
        entryId: Long? = null,
        categoryName: String? = null,
        anchor: SuggestionAnchor? = null,
        insertionCursor: Int? = null,
    ) {
        val settings = settingsRepository.settings.value
        val allEntries = vaultRepository.entries.value
        val entries = if (categoryName.isNullOrBlank()) {
            allEntries
        } else {
            allEntries.filter { it.category?.equals(categoryName, ignoreCase = true) == true }
        }
        val editor = if (anchor == null || insertionCursor == null) {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } else {
            null
        }
        val resolvedAnchor = anchor ?: editor?.let {
            createSuggestionAnchor(it, it.packageName?.toString().orEmpty())
        }
        val resolvedCursor = insertionCursor ?: editor?.let {
            editableText(it).let { text ->
                it.textSelectionEnd.takeIf { cursor -> cursor in 0..text.length } ?: text.length
            }
        }
        @Suppress("DEPRECATION")
        editor?.recycle()

        if (entryId != null) {
            allEntries.firstOrNull { it.id == entryId }?.let { entry ->
                showVaultEntryOverlay(entry, resolvedAnchor, resolvedCursor, settings)
            }
            return
        }

        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
            val headerRow = LinearLayout(this@ExpansionAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (!categoryName.isNullOrBlank()) {
                    addView(
                        ImageView(this@ExpansionAccessibilityService).apply {
                            setImageResource(R.drawable.ic_back_fine)
                            setColorFilter(ui.theme.onSurface)
                            scaleType = ImageView.ScaleType.CENTER
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            isClickable = true
                            isFocusable = true
                            contentDescription = localizedSelectionUi(
                                settings,
                                "Back to vault",
                                "Volver a la bóveda",
                            )
                            setOnClickListener {
                                showVaultOverlay(
                                    anchor = resolvedAnchor,
                                    insertionCursor = resolvedCursor,
                                )
                            }
                        },
                        LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                            marginEnd = dp(2)
                        },
                    )
                }
                addView(
                    ui.title(
                        categoryName?.takeIf(String::isNotBlank)
                            ?: localizedSelectionUi(settings, "Vault", "Bóveda"),
                    ).apply {
                        setPadding(dp(6), dp(2), dp(6), dp(4))
                    },
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ),
                )
            }
            addView(headerRow)
            addView(
                ui.body(
                    localizedSelectionUi(
                        settings,
                        "Encrypted on this device",
                        "Cifrada en este dispositivo",
                    ),
                    secondary = true,
                ).apply {
                    setPadding(
                        if (categoryName.isNullOrBlank()) dp(6) else dp(48),
                        0,
                        dp(6),
                        dp(10),
                    )
                },
            )
        }

        if (categoryName.isNullOrBlank()) {
            content.addView(
                ui.compactButton(
                    localizedSelectionUi(settings, "Add category", "Agregar categoría"),
                    primary = false,
                ) {
                    showVaultCategoryCreateOverlay(
                        anchor = resolvedAnchor,
                        insertionCursor = resolvedCursor,
                        settings = settings,
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { bottomMargin = dp(10) },
            )
        } else {
            content.addView(
                ui.compactButton(
                    localizedSelectionUi(settings, "Add entry", "Agregar entrada"),
                    primary = false,
                ) {
                    showVaultEntryCreateOverlay(
                        categoryName = categoryName,
                        anchor = resolvedAnchor,
                        insertionCursor = resolvedCursor,
                        settings = settings,
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { bottomMargin = dp(10) },
            )
        }

        var renderedItems = 0
        if (categoryName.isNullOrBlank()) {
            val categoryNames = (
                vaultRepository.categories.value.map(VaultCategory::name) +
                    allEntries.mapNotNull(VaultEntry::category)
                )
                .distinctBy { it.lowercase(Locale.ROOT) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

            categoryNames.forEach { name ->
                val category = vaultRepository.categories.value.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }
                val categoryEntries = allEntries
                    .filter { it.category?.equals(name, ignoreCase = true) == true }
                    .sortedWith(
                        compareByDescending<VaultEntry> { it.favorite }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    )
                val subtitle = buildString {
                    append(
                        localizedSelectionUi(
                            settings,
                            "${categoryEntries.size} entries",
                            "${categoryEntries.size} entradas",
                        ),
                    )
                    if (category?.triggers?.isNotEmpty() == true) {
                        append(" · ")
                        append(category.triggers.joinToString(" · "))
                    }
                }
                content.addView(
                    ui.body("$name\n$subtitle").apply {
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        minimumHeight = dp(54)
                        background = ui.surface()
                        isClickable = true
                        isFocusable = true
                        setTextColor(ui.theme.primary)
                        contentDescription = localizedSelectionUi(
                            settings,
                            "Open category $name",
                            "Abrir categoría $name",
                        )
                        setOnClickListener {
                            showVaultOverlay(
                                categoryName = name,
                                anchor = resolvedAnchor,
                                insertionCursor = resolvedCursor,
                            )
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(6) },
                )
                renderedItems++
            }

            val uncategorized = allEntries
                .filter { it.category.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<VaultEntry> { it.favorite }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                )
            if (uncategorized.isNotEmpty()) {
                content.addView(
                    ui.body(
                        localizedSelectionUi(settings, "Uncategorized", "Sin categoría"),
                        sizeSp = 13f,
                        secondary = true,
                    ).apply {
                        setPadding(dp(8), dp(10), dp(8), dp(4))
                        setTextColor(ui.theme.primary)
                    },
                )
                renderedItems++
                uncategorized.forEach { entry ->
                    addVaultEntryRow(
                        content = content,
                        entry = entry,
                        ui = ui,
                        settings = settings,
                        anchor = resolvedAnchor,
                        insertionCursor = resolvedCursor,
                    )
                    renderedItems++
                }
            }

            if (categoryNames.isEmpty() && uncategorized.isEmpty()) {
                content.addView(
                    ui.body(
                        localizedSelectionUi(
                            settings,
                            "The vault is empty. Add a category to get started.",
                            "La bóveda está vacía. Agrega una categoría para comenzar.",
                        ),
                        secondary = true,
                    ).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) },
                )
            }
        } else {
            val sorted = entries.sortedWith(
                compareByDescending<VaultEntry> { it.favorite }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
            if (sorted.isEmpty()) {
                content.addView(
                    ui.body(
                        localizedSelectionUi(
                            settings,
                            "This category has no entries yet.",
                            "Esta categoría todavía no tiene entradas.",
                        ),
                        secondary = true,
                    ).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) },
                )
            } else {
                sorted.forEach { entry ->
                    addVaultEntryRow(
                        content = content,
                        entry = entry,
                        ui = ui,
                        settings = settings,
                        anchor = resolvedAnchor,
                        insertionCursor = resolvedCursor,
                    )
                    renderedItems++
                }
            }
        }

        val footer = overlayCancelFooter(
            ui,
            localizedSelectionUi(settings, "Close", "Cerrar"),
        ) { hideFormOverlay() }
        val bounds = displayBounds(windowManager)
        val root = buildPickerOverlayRoot(
            content = content,
            footer = footer,
            itemCount = renderedItems + 1,
            maxContentHeightPx = (bounds.height() * 0.58f).toInt().coerceAtLeast(dp(180)),
            background = ui.panel(22),
            ui = ui,
        )
        val params = vaultOverlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(resolvedAnchor)
        }
    }

    private fun addVaultEntryRow(
        content: LinearLayout,
        entry: VaultEntry,
        ui: OverlayViews,
        settings: AppSettings,
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
    ) {
        val rowText = buildString {
            append(entry.title)
            if (entry.triggers.isNotEmpty()) {
                append("\n")
                append(entry.triggers.joinToString(" · "))
            }
        }
        content.addView(
            ui.body(rowText).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(52)
                background = ui.surface()
                isClickable = true
                isFocusable = true
                contentDescription = localizedSelectionUi(
                    settings,
                    "Open ${entry.title}",
                    "Abrir ${entry.title}",
                )
                setOnClickListener {
                    showVaultEntryOverlay(
                        entry = entry,
                        anchor = anchor,
                        insertionCursor = insertionCursor,
                        settings = settings,
                    )
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) },
        )
    }

    private fun showVaultCategoryCreateOverlay(
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val nameInput = ui.input(
            localizedSelectionUi(settings, "Category name", "Nombre de la categoría"),
            "",
        ).apply { setSingleLine(true) }
        val triggersInput = ui.input(
            localizedSelectionUi(settings, "Triggers", "Triggers"),
            "",
        ).apply {
            setSingleLine(false)
            minLines = 2
            maxLines = 4
        }
        val caseSensitive = CheckBox(this).apply {
            text = localizedSelectionUi(
                settings,
                "Match letter case exactly",
                "Distinguir mayúsculas y minúsculas",
            )
            setTextColor(ui.theme.onSurface)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(ui.title(localizedSelectionUi(settings, "New category", "Nueva categoría")))
            addView(ui.fieldGroup(localizedSelectionUi(settings, "Name", "Nombre"), nameInput))
            addView(ui.fieldGroup(localizedSelectionUi(settings, "Triggers — one per line", "Triggers — uno por línea"), triggersInput))
            addView(caseSensitive)
        }

        fun returnToVault() {
            showVaultOverlay(anchor = anchor, insertionCursor = insertionCursor)
        }

        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = localizedSelectionUi(settings, "Save", "Guardar"),
            cancelLabel = localizedSelectionUi(settings, "Cancel", "Cancelar"),
            onCancel = { returnToVault() },
            onPrimary = {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) return@overlayActionFooter
                val triggers = triggersInput.text.toString()
                    .lines()
                    .filter(String::isNotBlank)
                    .distinct()
                hideFormOverlay()
                scope.launch {
                    runCatching {
                        vaultRepository.saveCategory(
                            VaultCategory(
                                name = name,
                                triggers = triggers,
                                caseSensitive = caseSensitive.isChecked,
                            ),
                        )
                    }.onFailure { Log.w(TAG, "Failed to create vault category", it) }
                    returnToVault()
                }
            },
        )
        val root = buildOverlayRoot(panel, footer, ui.panel(22), ui)
        val params = vaultOverlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(
                card = root,
                windowManager = windowManager,
                verticalOffsetPx = -dp(VAULT_KEYBOARD_DIALOG_LIFT_DP),
                onDismiss = { returnToVault() },
            )
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(anchor)
            nameInput.requestFocus()
            nameInput.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }.onFailure { formOverlay = null }
    }

    private fun showVaultEntryCreateOverlay(
        categoryName: String,
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val titleInput = ui.input(localizedSelectionUi(settings, "Entry name", "Nombre de la entrada"), "").apply {
            setSingleLine(true)
        }
        val triggersInput = ui.input(localizedSelectionUi(settings, "Triggers", "Triggers"), "").apply {
            setSingleLine(false)
            minLines = 2
            maxLines = 4
        }
        val caseSensitive = CheckBox(this).apply {
            text = localizedSelectionUi(
                settings,
                "Match letter case exactly",
                "Distinguir mayúsculas y minúsculas",
            )
            setTextColor(ui.theme.onSurface)
        }
        val fieldsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val fieldInputs = mutableListOf<Triple<EditText, EditText, CheckBox>>()

        fun addField() {
            val labelInput = ui.input(localizedSelectionUi(settings, "Field name", "Nombre del campo"), "")
                .apply { setSingleLine(true) }
            val valueInput = ui.input(localizedSelectionUi(settings, "Value", "Valor"), "")
                .apply {
                    setSingleLine(false)
                    minLines = 1
                    maxLines = 4
                }
            val sensitive = CheckBox(this).apply {
                text = localizedSelectionUi(settings, "Sensitive", "Sensible")
                setTextColor(ui.theme.onSurface)
            }
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(8))
                addView(ui.fieldGroup(localizedSelectionUi(settings, "Field", "Campo"), labelInput))
                addView(ui.fieldGroup(localizedSelectionUi(settings, "Value", "Valor"), valueInput))
                addView(sensitive)
            }
            fieldsContainer.addView(group)
            fieldInputs += Triple(labelInput, valueInput, sensitive)
        }
        addField()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(ui.title(localizedSelectionUi(settings, "New entry", "Nueva entrada")))
            addView(ui.body(categoryName, secondary = true).apply {
                setPadding(0, dp(4), 0, dp(10))
            })
            addView(ui.fieldGroup(localizedSelectionUi(settings, "Name", "Nombre"), titleInput))
            addView(ui.fieldGroup(localizedSelectionUi(settings, "Triggers — one per line", "Triggers — uno por línea"), triggersInput))
            addView(caseSensitive)
            addView(fieldsContainer)
            addView(
                ui.compactButton(
                    localizedSelectionUi(settings, "Add field", "Agregar campo"),
                    primary = false,
                ) { addField() },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { topMargin = dp(4) },
            )
        }

        fun returnToCategory() {
            showVaultOverlay(
                categoryName = categoryName,
                anchor = anchor,
                insertionCursor = insertionCursor,
            )
        }

        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = localizedSelectionUi(settings, "Save", "Guardar"),
            cancelLabel = localizedSelectionUi(settings, "Cancel", "Cancelar"),
            onCancel = { returnToCategory() },
            onPrimary = {
                val title = titleInput.text.toString().trim()
                val fields = fieldInputs.mapNotNull { (labelInput, valueInput, sensitive) ->
                    val value = valueInput.text.toString()
                    val label = labelInput.text.toString().trim().ifBlank {
                        localizedSelectionUi(settings, "Value", "Valor")
                    }
                    if (value.isEmpty() && labelInput.text.toString().isBlank()) {
                        null
                    } else {
                        VaultField(label = label, value = value, sensitive = sensitive.isChecked)
                    }
                }
                if (title.isBlank() || fields.isEmpty()) return@overlayActionFooter
                val triggers = triggersInput.text.toString()
                    .lines()
                    .filter(String::isNotBlank)
                    .distinct()
                hideFormOverlay()
                scope.launch {
                    runCatching {
                        vaultRepository.save(
                            VaultEntry(
                                title = title,
                                triggers = triggers,
                                caseSensitive = caseSensitive.isChecked,
                                fields = fields,
                                category = categoryName,
                            ),
                        )
                    }.onFailure { Log.w(TAG, "Failed to create vault entry", it) }
                    returnToCategory()
                }
            },
        )
        val root = buildScrollableOverlayRoot(
            content = panel,
            footer = footer,
            maxContentHeightPx = (displayBounds(windowManager).height() * 0.62f).toInt(),
            background = ui.panel(22),
            ui = ui,
        )
        val params = vaultOverlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(
                card = root,
                windowManager = windowManager,
                verticalOffsetPx = -dp(VAULT_KEYBOARD_DIALOG_LIFT_DP),
                onDismiss = { returnToCategory() },
            )
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(anchor)
            titleInput.requestFocus()
            titleInput.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(titleInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }.onFailure { formOverlay = null }
    }

    private fun showVaultEntryOverlay(
        entry: VaultEntry,
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))

            val headerRow = LinearLayout(this@ExpansionAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(this@ExpansionAccessibilityService).apply {
                        setImageResource(R.drawable.ic_back_fine)
                        setColorFilter(ui.theme.onSurface)
                        scaleType = ImageView.ScaleType.CENTER
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        isClickable = true
                        isFocusable = true
                        contentDescription = localizedSelectionUi(
                            settings,
                            "Back",
                            "Volver",
                        )
                        setOnClickListener {
                            showVaultOverlay(
                                categoryName = entry.category,
                                anchor = anchor,
                                insertionCursor = insertionCursor,
                            )
                        }
                    },
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        marginEnd = dp(2)
                    },
                )
                addView(
                    ui.title(entry.title).apply {
                        setPadding(dp(6), dp(2), dp(6), dp(4))
                    },
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ),
                )
            }
            addView(headerRow)

            entry.category?.takeIf(String::isNotBlank)?.let { category ->
                addView(
                    ui.body(category, secondary = true).apply {
                        setPadding(dp(48), 0, dp(6), dp(1))
                    },
                )
            }

            val triggerRow = LinearLayout(this@ExpansionAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(48), 0, dp(6), dp(8))
                background = android.graphics.drawable.ColorDrawable(ui.theme.surface)
                isClickable = true
                isFocusable = true
                contentDescription = localizedSelectionUi(
                    settings,
                    "Edit triggers",
                    "Editar triggers",
                )
                setOnClickListener {
                    showVaultTriggerEditOverlay(
                        entry = entry,
                        anchor = anchor,
                        insertionCursor = insertionCursor,
                        settings = settings,
                    )
                }
            }
            val triggerLabel = if (entry.triggers.isEmpty()) {
                localizedSelectionUi(settings, "No trigger", "Sin trigger")
            } else {
                entry.triggers.joinToString(" · ")
            }
            triggerRow.addView(
                ui.body(triggerLabel, secondary = true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            triggerRow.addView(
                ui.body("›", sizeSp = 19f, secondary = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), 0, dp(2), 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(32),
                ),
            )
            addView(
                triggerRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        entry.fields.forEach { field ->
            val fieldBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = ui.surface()
            }
            fieldBox.addView(ui.body(field.label).apply {
                setTextColor(ui.theme.primary)
            })

            var revealed = !field.sensitive
            val valueView = ui.body(if (revealed) field.value else "••••••••").apply {
                setPadding(0, dp(4), dp(6), dp(4))
                maxLines = 3
            }
            val valueRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            valueRow.addView(
                valueView,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )

            if (field.sensitive) {
                lateinit var revealControl: TextView
                revealControl = ui.body(
                    localizedSelectionUi(settings, "Show", "Mostrar"),
                    sizeSp = 12.5f,
                    secondary = true,
                ).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(48)
                    minimumWidth = dp(48)
                    minHeight = dp(48)
                    minimumHeight = dp(48)
                    setPadding(dp(6), 0, dp(6), 0)
                    isClickable = true
                    isFocusable = true
                    contentDescription = localizedSelectionUi(
                        settings,
                        "Show sensitive value",
                        "Mostrar valor sensible",
                    )
                    setOnClickListener {
                        revealed = !revealed
                        valueView.text = if (revealed) field.value else "••••••••"
                        revealControl.text = localizedSelectionUi(
                            settings,
                            if (revealed) "Hide" else "Show",
                            if (revealed) "Ocultar" else "Mostrar",
                        )
                    }
                }
                valueRow.addView(revealControl)
            }

            fieldBox.addView(valueRow)

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
            }

            fun addActionButton(button: TextView, addStartMargin: Boolean = true) {
                actions.addView(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(48),
                    ).apply {
                        if (addStartMargin) marginStart = dp(8)
                    },
                )
            }

            addActionButton(
                ui.compactButton(
                    localizedSelectionUi(settings, "Copy", "Copiar"),
                    primary = false,
                ) {
                    writeVaultClipboard(field.value, field.sensitive)
                    if (settings.hapticFeedback) vibrateTick()
                },
                addStartMargin = false,
            )
            if (anchor != null && insertionCursor != null) {
                addActionButton(
                    ui.compactButton(
                        localizedSelectionUi(settings, "Insert", "Insertar"),
                        primary = true,
                    ) {
                        hideFormOverlay()
                        insertVaultValue(
                            anchor = anchor,
                            insertionCursor = insertionCursor,
                            field = field,
                            settings = settings,
                        )
                    },
                )
            }
            addActionButton(
                ui.compactButton(
                    localizedSelectionUi(settings, "Update", "Actualizar"),
                    primary = false,
                ) {
                    val clipboard = readClipboardTextOrNull()
                        ?: clipboardMonitor.cachedText
                        ?: return@compactButton
                    scope.launch {
                        if (vaultRepository.updateFieldFromClipboard(entry.id, field.id, clipboard)) {
                            if (settings.hapticFeedback) vibrate()
                            val updated = vaultRepository.entries.value.firstOrNull { it.id == entry.id }
                            if (updated != null) {
                                showVaultEntryOverlay(
                                    updated,
                                    anchor,
                                    insertionCursor,
                                    settings,
                                )
                            }
                        }
                    }
                },
            )

            fieldBox.addView(actions)
            content.addView(
                vaultSwipeEditCard(
                    front = fieldBox,
                    ui = ui,
                    settings = settings,
                    editDescription = localizedSelectionUi(
                        settings,
                        "Edit ${field.label}",
                        "Editar ${field.label}",
                    ),
                ) {
                    showVaultFieldEditOverlay(
                        entry = entry,
                        field = field,
                        anchor = anchor,
                        insertionCursor = insertionCursor,
                        settings = settings,
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) },
            )
        }

        content.addView(
            ui.compactButton(
                localizedSelectionUi(settings, "Add field", "Agregar campo"),
                primary = false,
            ) {
                showVaultFieldCreateOverlay(
                    entry = entry,
                    anchor = anchor,
                    insertionCursor = insertionCursor,
                    settings = settings,
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) },
        )

        val preferredIds = entry.preferredCopyFieldIds?.toSet()
        val copyFields = entry.fields
            .filter { preferredIds == null || it.id in preferredIds }
            .ifEmpty { entry.fields }
        val copyLabel = if (copyFields.size == entry.fields.size) {
            localizedSelectionUi(settings, "Copy all", "Copiar todo")
        } else {
            localizedSelectionUi(
                settings,
                "Copy ${copyFields.size}/${entry.fields.size}",
                "Copiar ${copyFields.size}/${entry.fields.size}",
            )
        }

        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = copyLabel,
            cancelLabel = localizedSelectionUi(settings, "Close", "Cerrar"),
            onCancel = { hideFormOverlay() },
            onPrimary = {
                val valuesOnly = copyFields.joinToString("\n", transform = VaultField::value)
                writeVaultClipboard(valuesOnly, copyFields.any(VaultField::sensitive))
                if (settings.hapticFeedback) vibrateTick()
            },
            onPrimaryLongClick = {
                val labeled = copyFields.joinToString("\n") { "${it.label}: ${it.value}" }
                writeVaultClipboard(labeled, copyFields.any(VaultField::sensitive))
                if (settings.hapticFeedback) vibrateTick()
            },
            swipeHapticEnabled = settings.hapticFeedback,
            onPrimarySwipe = if (entry.fields.size > 1) {
                { direction ->
                    when (direction) {
                        PrimarySwipeDirection.LEFT,
                        PrimarySwipeDirection.RIGHT,
                        -> {
                            if (settings.hapticFeedback) vibrateTick()
                            showVaultCopyFieldSelectorOverlay(
                                entry = entry,
                                anchor = anchor,
                                insertionCursor = insertionCursor,
                                settings = settings,
                            )
                        }
                        PrimarySwipeDirection.UP -> {
                            copyVaultFieldsIndividually(copyFields, settings)
                        }
                    }
                }
            } else {
                null
            },
        )
        val bounds = displayBounds(windowManager)
        val root = buildPickerOverlayRoot(
            content = content,
            footer = footer,
            itemCount = entry.fields.size,
            maxContentHeightPx = (bounds.height() * 0.58f).toInt().coerceAtLeast(dp(180)),
            background = ui.panel(22),
            ui = ui,
        )
        val params = vaultOverlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(root, windowManager)
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(anchor)
        }
    }

    private fun vaultSwipeEditCard(
        front: View,
        ui: OverlayViews,
        settings: AppSettings,
        editDescription: String,
        onEdit: () -> Unit,
    ): View {
        val revealWidth = dp(70).toFloat()
        val commitThreshold = dp(46).toFloat()
        val maxTravel = dp(86).toFloat()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        val container = object : FrameLayout(this) {
            var downX = 0f
            var downY = 0f
            var dragging = false
            var armed = false
            // First horizontal direction wins until ACTION_UP/ACTION_CANCEL.
            // +1 = right, -1 = left, 0 = undecided.
            var directionLock = 0

            private fun updateDrag(event: MotionEvent) {
                val dx = event.rawX - downX
                val clamped = when (directionLock) {
                    1 -> dx.coerceIn(0f, maxTravel)
                    -1 -> dx.coerceIn(-maxTravel, 0f)
                    else -> 0f
                }
                front.translationX = clamped
                val nowArmed = kotlin.math.abs(clamped) >= commitThreshold
                if (nowArmed && !armed && settings.hapticFeedback) {
                    vibrateTick()
                }
                armed = nowArmed
            }

            private fun finishDrag(runEdit: Boolean) {
                val shouldEdit = runEdit && dragging && armed
                dragging = false
                armed = false
                directionLock = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                front.animate()
                    .translationX(0f)
                    .setDuration(120L)
                    .withEndAction {
                        if (shouldEdit) onEdit()
                    }
                    .start()
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        dragging = false
                        armed = false
                        directionLock = 0

                        // Always keep ownership of this touch sequence, even when
                        // ACTION_DOWN lands on a non-clickable area of the card.
                        // Still forward it so Copy/Insert/Update can behave normally
                        // when the gesture never becomes a swipe.
                        super.dispatchTouchEvent(event)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (
                            !dragging &&
                            kotlin.math.abs(dx) > touchSlop &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f
                        ) {
                            dragging = true
                            directionLock = if (dx > 0f) 1 else -1
                            parent?.requestDisallowInterceptTouchEvent(true)

                            // Cancel whichever child received ACTION_DOWN so a swipe
                            // can never also fire Copy/Insert/Update.
                            val cancel = MotionEvent.obtain(event).apply {
                                action = MotionEvent.ACTION_CANCEL
                            }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()

                            updateDrag(event)
                            return true
                        }

                        if (dragging) {
                            updateDrag(event)
                            return true
                        }

                        // Preserve ordinary child interactions until horizontal
                        // intent is established. Vertical movement remains free for
                        // the surrounding ScrollView to intercept.
                        super.dispatchTouchEvent(event)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            finishDrag(runEdit = true)
                            return true
                        }
                        super.dispatchTouchEvent(event)
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            finishDrag(runEdit = false)
                            return true
                        }
                        super.dispatchTouchEvent(event)
                        return true
                    }
                }

                if (!dragging) super.dispatchTouchEvent(event)
                return true
            }
        }.apply {
            clipChildren = true
            clipToPadding = true
            background = ui.surface(14, emphasized = true)
            contentDescription = editDescription
        }

        fun editAffordance(): TextView = ui.body(
            localizedSelectionUi(settings, "Edit", "Editar"),
            sizeSp = 12.5f,
            secondary = false,
        ).apply {
            gravity = Gravity.CENTER
            setTextColor(ui.theme.primary)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_edit_fine, 0, 0, 0)
            compoundDrawablePadding = dp(4)
            compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(ui.theme.primary)
            isClickable = false
            isFocusable = false
        }

        container.addView(
            editAffordance(),
            FrameLayout.LayoutParams(
                revealWidth.toInt(),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START,
            ),
        )
        container.addView(
            editAffordance(),
            FrameLayout.LayoutParams(
                revealWidth.toInt(),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END,
            ),
        )
        container.addView(
            front,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return container
    }

    private fun showVaultTriggerEditOverlay(
        entry: VaultEntry,
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val triggersInput = ui.input(
            localizedSelectionUi(settings, "Triggers", "Triggers"),
            entry.triggers.joinToString("\n"),
        ).apply {
            setSingleLine(false)
            minLines = 2
            maxLines = 5
        }
        val caseSensitive = CheckBox(this).apply {
            text = localizedSelectionUi(
                settings,
                "Match letter case exactly",
                "Distinguir mayúsculas y minúsculas",
            )
            isChecked = entry.caseSensitive
            setTextColor(ui.theme.onSurface)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(
                ui.title(
                    localizedSelectionUi(
                        settings,
                        "Edit triggers",
                        "Editar triggers",
                    ),
                ),
            )
            addView(
                ui.body(entry.title, secondary = true).apply {
                    setPadding(0, dp(4), 0, dp(10))
                },
            )
            addView(
                ui.fieldGroup(
                    localizedSelectionUi(
                        settings,
                        "Triggers — one per line",
                        "Triggers — uno por línea",
                    ),
                    triggersInput,
                ),
            )
            addView(caseSensitive)
        }

        fun returnToEntry() {
            val latest = vaultRepository.entries.value.firstOrNull { it.id == entry.id } ?: entry
            showVaultEntryOverlay(
                entry = latest,
                anchor = anchor,
                insertionCursor = insertionCursor,
                settings = settings,
            )
        }

        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = localizedSelectionUi(settings, "Save", "Guardar"),
            cancelLabel = localizedSelectionUi(settings, "Cancel", "Cancelar"),
            onCancel = { returnToEntry() },
            onPrimary = {
                val triggers = triggersInput.text.toString()
                    .lines()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                hideFormOverlay()
                scope.launch {
                    runCatching {
                        vaultRepository.save(
                            entry.copy(
                                triggers = triggers,
                                caseSensitive = caseSensitive.isChecked,
                            ),
                        )
                    }.onSuccess {
                        if (settings.hapticFeedback) vibrateTick()
                    }.onFailure {
                        Log.w(TAG, "Failed to update vault triggers", it)
                    }
                    returnToEntry()
                }
            },
        )

        val root = buildOverlayRoot(panel, footer, ui.panel(22), ui)
        val params = vaultOverlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(
                card = root,
                windowManager = windowManager,
                onDismiss = { returnToEntry() },
                verticalOffsetPx = -dp(VAULT_KEYBOARD_DIALOG_LIFT_DP),
            )
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(anchor)
            triggersInput.requestFocus()
            triggersInput.setSelection(triggersInput.text.length)
            triggersInput.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(triggersInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }.onFailure {
            formOverlay = null
        }
    }

    private fun showVaultCopyFieldSelectorOverlay(
        entry: VaultEntry,
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val selectedIds = (
            entry.preferredCopyFieldIds?.toMutableSet()
                ?: entry.fields.mapTo(linkedSetOf(), VaultField::id)
            )
        val checkboxes = linkedMapOf<String, CheckBox>()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            addView(
                ui.title(
                    localizedSelectionUi(
                        settings,
                        "Choose fields to copy",
                        "Elegir campos para copiar",
                    ),
                ),
            )
            addView(
                ui.body(
                    localizedSelectionUi(
                        settings,
                        "This selection is remembered for this entry.",
                        "Esta selección se recuerda para esta entrada.",
                    ),
                    secondary = true,
                ).apply { setPadding(0, dp(4), 0, dp(10)) },
            )
        }

        lateinit var bulkToggle: TextView
        fun syncBulkToggle() {
            val allSelected = entry.fields.isNotEmpty() &&
                entry.fields.all { it.id in selectedIds }
            bulkToggle.text = localizedSelectionUi(
                settings,
                if (allSelected) "Deselect all" else "Select all",
                if (allSelected) "Deseleccionar todo" else "Seleccionar todo",
            )
            bulkToggle.setCompoundDrawablesWithIntrinsicBounds(
                if (allSelected) R.drawable.ic_deselect_all_fine else R.drawable.ic_select_all_fine,
                0,
                0,
                0,
            )
            bulkToggle.compoundDrawablePadding = dp(8)
            bulkToggle.compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(ui.theme.onSurface)
            bulkToggle.contentDescription = bulkToggle.text
        }

        bulkToggle = ui.compactButton(
            localizedSelectionUi(settings, "Select all", "Seleccionar todo"),
            primary = false,
        ) {
            val shouldSelectAll = entry.fields.any { it.id !in selectedIds }
            checkboxes.values.forEach { checkbox ->
                checkbox.isChecked = shouldSelectAll
            }
            if (shouldSelectAll) {
                selectedIds.clear()
                selectedIds += entry.fields.map(VaultField::id)
            } else {
                selectedIds.clear()
            }
            syncBulkToggle()
        }
        syncBulkToggle()
        content.addView(
            bulkToggle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { bottomMargin = dp(8) },
        )

        entry.fields.forEach { field ->
            val checkbox = CheckBox(this).apply {
                text = field.label
                isChecked = field.id in selectedIds
                setTextColor(ui.theme.onSurface)
                minimumHeight = dp(48)
                setPadding(dp(6), 0, dp(6), 0)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedIds += field.id else selectedIds -= field.id
                    syncBulkToggle()
                }
            }
            checkboxes[field.id] = checkbox
            content.addView(
                checkbox,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ),
            )
        }

        fun returnToEntry() {
            val latest = vaultRepository.entries.value.firstOrNull { it.id == entry.id } ?: entry
            showVaultEntryOverlay(
                entry = latest,
                anchor = anchor,
                insertionCursor = insertionCursor,
                settings = settings,
            )
        }

        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = localizedSelectionUi(settings, "Save", "Guardar"),
            cancelLabel = localizedSelectionUi(settings, "Cancel", "Cancelar"),
            onCancel = { returnToEntry() },
            onPrimary = {
                if (selectedIds.isEmpty()) return@overlayActionFooter
                hideFormOverlay()
                scope.launch {
                    vaultRepository.updateCopyFieldSelection(
                        entryId = entry.id,
                        fieldIds = entry.fields
                            .map(VaultField::id)
                            .filter { it in selectedIds },
                    )
                    returnToEntry()
                }
            },
        )
        val root = buildPickerOverlayRoot(
            content = content,
            footer = footer,
            itemCount = entry.fields.size,
            maxContentHeightPx = (displayBounds(windowManager).height() * 0.58f).toInt(),
            background = ui.panel(22),
            ui = ui,
        )
        val params = vaultOverlayDialogParams(windowManager, softInput = false)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(
                card = root,
                windowManager = windowManager,
                verticalOffsetPx = -dp(VAULT_KEYBOARD_DIALOG_LIFT_DP),
                onDismiss = { returnToEntry() },
            )
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(anchor)
        }.onFailure { formOverlay = null }
    }

    private fun showVaultFieldCreateOverlay(
        entry: VaultEntry,
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val labelInput = ui.input(
            localizedSelectionUi(settings, "Field name", "Nombre del campo"),
            "",
        ).apply { setSingleLine(true) }
        val valueInput = ui.input(
            localizedSelectionUi(settings, "Value", "Valor"),
            "",
        ).apply {
            setSingleLine(false)
            minLines = 1
            maxLines = 5
        }
        val sensitive = CheckBox(this).apply {
            text = localizedSelectionUi(settings, "Sensitive", "Sensible")
            setTextColor(ui.theme.onSurface)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(
                ui.title(
                    localizedSelectionUi(
                        settings,
                        "New field",
                        "Nuevo campo",
                    ),
                ),
            )
            addView(
                ui.body(entry.title, secondary = true).apply {
                    setPadding(0, dp(4), 0, dp(10))
                },
            )
            addView(
                ui.fieldGroup(
                    localizedSelectionUi(settings, "Field", "Campo"),
                    labelInput,
                ),
            )
            addView(
                ui.fieldGroup(
                    localizedSelectionUi(settings, "Value", "Valor"),
                    valueInput,
                ),
            )
            addView(sensitive)
        }

        fun returnToEntry() {
            val latest = vaultRepository.entries.value.firstOrNull { it.id == entry.id } ?: entry
            showVaultEntryOverlay(
                entry = latest,
                anchor = anchor,
                insertionCursor = insertionCursor,
                settings = settings,
            )
        }

        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = localizedSelectionUi(settings, "Save", "Guardar"),
            cancelLabel = localizedSelectionUi(settings, "Cancel", "Cancelar"),
            onCancel = { returnToEntry() },
            onPrimary = {
                val rawLabel = labelInput.text.toString()
                val value = valueInput.text.toString()
                if (rawLabel.isBlank() && value.isEmpty()) return@overlayActionFooter
                val label = rawLabel.trim().ifBlank {
                    localizedSelectionUi(settings, "Value", "Valor")
                }
                hideFormOverlay()
                scope.launch {
                    runCatching {
                        vaultRepository.save(
                            entry.copy(
                                fields = entry.fields + VaultField(
                                    label = label,
                                    value = value,
                                    sensitive = sensitive.isChecked,
                                ),
                            ),
                        )
                    }.onSuccess {
                        if (settings.hapticFeedback) vibrate()
                    }.onFailure {
                        Log.w(TAG, "Failed to add vault field", it)
                    }
                    returnToEntry()
                }
            },
        )
        val root = buildOverlayRoot(panel, footer, ui.panel(22), ui)
        val params = vaultOverlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(
                card = root,
                windowManager = windowManager,
                onDismiss = { returnToEntry() },
            )
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(anchor)
            labelInput.requestFocus()
            labelInput.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(labelInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }.onFailure {
            formOverlay = null
        }
    }

    private fun showVaultFieldEditOverlay(
        entry: VaultEntry,
        field: VaultField,
        anchor: SuggestionAnchor?,
        insertionCursor: Int?,
        settings: AppSettings,
    ) {
        hideFormOverlay()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val valueInput = ui.input(field.label, field.value).apply {
            setSingleLine(false)
            minLines = 1
            maxLines = 5
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            addView(
                ui.title(
                    localizedSelectionUi(
                        settings,
                        "Edit ${field.label}",
                        "Editar ${field.label}",
                    ),
                ),
            )
            addView(
                ui.body(entry.title, secondary = true).apply {
                    setPadding(0, dp(4), 0, dp(10))
                },
            )
            addView(ui.fieldGroup(field.label, valueInput))
        }

        fun returnToEntry() {
            val latest = vaultRepository.entries.value.firstOrNull { it.id == entry.id } ?: entry
            showVaultEntryOverlay(
                entry = latest,
                anchor = anchor,
                insertionCursor = insertionCursor,
                settings = settings,
            )
        }

        val footer = overlayActionFooter(
            ui = ui,
            primaryLabel = localizedSelectionUi(settings, "Save", "Guardar"),
            cancelLabel = localizedSelectionUi(settings, "Cancel", "Cancelar"),
            onCancel = { returnToEntry() },
            onPrimary = {
                val newValue = valueInput.text.toString()
                hideFormOverlay()
                scope.launch {
                    val changed = vaultRepository.updateFieldValue(entry.id, field.id, newValue)
                    if (changed && settings.hapticFeedback) vibrate()
                    returnToEntry()
                }
            },
        )
        val root = buildOverlayRoot(panel, footer, ui.panel(22), ui)
        val params = vaultOverlayDialogParams(windowManager, softInput = true)
        runCatching {
            val overlayRoot = dismissibleOverlayRoot(
                card = root,
                windowManager = windowManager,
                onDismiss = { returnToEntry() },
                verticalOffsetPx = -dp(VAULT_KEYBOARD_DIALOG_LIFT_DP),
            )
            windowManager.addView(overlayRoot, params)
            formOverlay = overlayRoot
            markVaultOverlayShown(anchor)
            valueInput.requestFocus()
            valueInput.selectAll()
            valueInput.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(valueInput, InputMethodManager.SHOW_IMPLICIT)
            }, 120L)
        }.onFailure {
            formOverlay = null
        }
    }

    private fun insertVaultValue(
        anchor: SuggestionAnchor,
        insertionCursor: Int,
        field: VaultField,
        settings: AppSettings,
    ) {
        val node = findAnchoredEditor(anchor, requireActiveWindow = false) ?: return
        try {
            runCatching { node.refresh() }
            val originalText = editableText(node)
            val cursor = insertionCursor.coerceIn(0, originalText.length)
            val finalText = originalText.replaceRange(cursor, cursor, field.value)
            val finalCursor = cursor + field.value.length
            val success = if (isNativeEditText(node)) {
                writeViaSetText(node, finalText, finalCursor, finalCursor)
            } else {
                if (field.sensitive) clipboardMonitor.suppressHistoryOnce(field.value)
                pasteReplacement(node, cursor, cursor, field.value, finalCursor) ||
                    writeViaSetText(node, finalText, finalCursor, finalCursor)
            }
            if (success) {
                lastAppliedText = finalText
                lastAppliedAt = SystemClock.elapsedRealtime()
                if (settings.hapticFeedback) vibrate()
            }
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun copyVaultFieldsIndividually(
        fields: List<VaultField>,
        settings: AppSettings,
    ) {
        if (fields.isEmpty()) return
        scope.launch {
            // Gboard keeps clipboard history newest-first. Write bottom-to-top
            // so the resulting cards preserve the field order shown in Expanda.
            //
            // This gesture explicitly means "copy as separate clipboard-history
            // items", so sensitive fields are still suppressed from Expanda's
            // own history but are not tagged IS_SENSITIVE for the system clip;
            // keyboards may intentionally omit IS_SENSITIVE clips from history.
            fields.asReversed().forEachIndexed { index, field ->
                writeVaultClipboardHistoryItem(field.value, field.sensitive)
                if (index < fields.lastIndex) delay(VAULT_GBOARD_COPY_INTERVAL_MS)
            }
            if (settings.hapticFeedback) vibrate()
        }
    }

    private fun writeVaultClipboardHistoryItem(text: String, sensitive: Boolean) {
        if (text.isEmpty()) return
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        if (sensitive) clipboardMonitor.suppressHistoryOnce(text)
        manager.setPrimaryClip(ClipData.newPlainText("Expanda vault item", text))
    }

    private fun writeVaultClipboard(text: String, sensitive: Boolean) {
        if (text.isEmpty()) return
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        if (sensitive) clipboardMonitor.suppressHistoryOnce(text)
        val clip = ClipData.newPlainText(
            if (sensitive) "Expanda vault" else "Expanda note",
            text,
        )
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        manager.setPrimaryClip(clip)
        if (sensitive) {
            mainHandler.postDelayed({
                if (clipboardMonitor.cachedText == text) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        manager.clearPrimaryClip()
                    } else {
                        manager.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }, VAULT_CLIPBOARD_CLEAR_MS)
        }
    }

    private fun writeClipboard(text: String) {
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Expanda action", text))
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            startActivity(
                Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun openNewSnippetEditor() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(EXTRA_OPEN_NEW_SNIPPET, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
    }

    private fun pasteReplacement(
        node: AccessibilityNodeInfo,
        start: Int,
        end: Int,
        replacement: String,
        cursor: Int,
    ): Boolean {
        if (!setSelection(node, start, end)) return false
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val previous = manager.primaryClip
        manager.setPrimaryClip(ClipData.newPlainText("Expanda replacement", replacement))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) setSelection(node, cursor, cursor)
        Handler(Looper.getMainLooper()).postDelayed({
            if (previous != null) {
                manager.setPrimaryClip(previous)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, CLIPBOARD_RESTORE_DELAY_MS)
        return pasted
    }

    private fun showSuggestions(
        anchorNode: AccessibilityNodeInfo,
        text: String,
        cursor: Int,
        packageName: String,
        settings: AppSettings,
        showAll: Boolean = false,
    ) {
        val typed = if (showAll) "" else currentToken(text, cursor)
        val minimumCharacters = settings.suggestionMinChars.coerceIn(1, MAX_SUGGESTION_LENGTH)
        if (!showAll && !SuggestionMatcher.canShow(typed, minimumCharacters)) {
            hideSuggestions()
            return
        }
        fun shortcutMatches(shortcut: String, caseSensitive: Boolean = false): Boolean {
            if (showAll) return true
            if (!caseSensitive) {
                return SuggestionMatcher.matchRange(
                    shortcut,
                    typed,
                    settings.matchFromBeginning,
                ) != null
            }
            if (typed.isEmpty()) return false
            return if (settings.matchFromBeginning) {
                shortcut.startsWith(typed)
            } else {
                shortcut.contains(typed)
            }
        }

        fun suggestionTypeOrder(item: PopupSuggestion): Int = when (item) {
            is PopupSuggestion.TextSnippet -> 0
            is PopupSuggestion.VaultEntryItem -> 1
            is PopupSuggestion.VaultCategoryItem -> 2
            is PopupSuggestion.Action -> 3
        }

        val suggestions = buildList<PopupSuggestion> {
            repository.matches.value.asSequence()
                .filter {
                    it.enabled &&
                        it.suggestionEnabled &&
                        it.runsOnAndroid &&
                        packageName !in it.excludedPackages
                }
                .flatMap { match -> match.textTriggers().asSequence().map { match to it } }
                .filter { (_, trigger) -> shortcutMatches(trigger) }
                .mapTo(this) { (match, trigger) -> PopupSuggestion.TextSnippet(match, trigger, typed) }

            if (settings.suggestionShowVault) {
                vaultRepository.entries.value.asSequence()
                    .flatMap { entry -> entry.triggers.asSequence().map { entry to it } }
                    .filter { (_, trigger) -> trigger.isNotBlank() }
                    .filter { (entry, trigger) -> shortcutMatches(trigger, entry.caseSensitive) }
                    .mapTo(this) { (entry, trigger) ->
                        PopupSuggestion.VaultEntryItem(entry, trigger, typed)
                    }
                vaultRepository.categories.value.asSequence()
                    .flatMap { category -> category.triggers.asSequence().map { category to it } }
                    .filter { (_, trigger) -> trigger.isNotBlank() }
                    .filter { (category, trigger) -> shortcutMatches(trigger, category.caseSensitive) }
                    .mapTo(this) { (category, trigger) ->
                        PopupSuggestion.VaultCategoryItem(category, trigger, typed)
                    }
            }

            if (settings.suggestionShowActions && !showAll) {
                val enabledActions = actionSettingsStore.enabledIds.value
                val triggerOverrides = actionSettingsStore.triggerOverrides.value
                ActionEngine.definitions.asSequence()
                    .filter { it.id in enabledActions }
                    .flatMap { definition ->
                        val triggers = triggerOverrides[definition.id] ?: definition.triggers
                        triggers.asSequence().map { trigger ->
                            definition.copy(shortcut = trigger, aliases = emptyList())
                        }
                    }
                    .mapNotNull { definition ->
                        actionSuggestionPrefix(
                            shortcut = definition.shortcut,
                            text = text,
                            cursor = cursor,
                            minimumCharacters = minimumCharacters,
                            fromBeginning = settings.matchFromBeginning,
                        )?.let { matchedText -> PopupSuggestion.Action(definition, matchedText) }
                    }
                    .forEach { add(it) }
            }
        }.let { candidates ->
            if (showAll) {
                candidates.sortedWith(
                    compareBy<PopupSuggestion>(::suggestionTypeOrder)
                        .thenBy { it.shortcut.lowercase() },
                ).take(MAX_BROWSE_SUGGESTIONS)
            } else {
                candidates.sortedWith(
                    compareBy<PopupSuggestion> { it.shortcut.length }
                        .thenBy(::suggestionTypeOrder)
                        .thenByDescending { (it as? PopupSuggestion.TextSnippet)?.textMatch?.usageCount ?: 0L },
                ).take(MAX_SUGGESTIONS)
            }
        }
        if (suggestions.isEmpty()) {
            hideSuggestions()
            return
        }

        val anchor = createSuggestionAnchor(anchorNode, packageName)
        hideSuggestions()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val ui = OverlayViews(this, resolveNativeTheme(this, settings))
        val bounds = displayBounds(windowManager)
        val horizontalMargin = dp(12)
        val popupWidth = (bounds.width() * settings.suggestionWidthFraction).toInt()
            .coerceIn(dp(MIN_WIDTH_DP), (bounds.width() - horizontalMargin * 2).coerceAtLeast(dp(MIN_WIDTH_DP)))
        val listMaxHeight = dp(
            settings.suggestionMaxHeightDp.coerceIn(
                SettingsRepository.MIN_SUGGESTION_HEIGHT_DP,
                SettingsRepository.MAX_SUGGESTION_HEIGHT_DP,
            ),
        )
        val estimatedHeight = listMaxHeight + dp(72)

        val container = FrameLayout(this).apply {
            setPadding(
                dp(SuggestionOverlaySpec.HORIZONTAL_PADDING_DP),
                dp(SuggestionOverlaySpec.TOP_PADDING_DP),
                dp(SuggestionOverlaySpec.HORIZONTAL_PADDING_DP),
                dp(SuggestionOverlaySpec.BOTTOM_PADDING_DP),
            )
            background = ui.panel(SuggestionOverlaySpec.PANEL_RADIUS_DP)
            elevation = dp(8).toFloat()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val handle = object : TextView(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            this.text = "⠿"
            gravity = Gravity.CENTER
            setTextColor(ui.theme.onSurfaceVariant)
            textSize = ui.scaled(19f)
            includeFontPadding = false
            setPadding(0, dp(1), 0, dp(3))
            contentDescription = localizedSelectionUi(settings, "Move suggestion popup", "Mover panel de sugerencias")
        }
        val close = TextView(this).apply {
            this.text = "×"
            gravity = Gravity.CENTER
            setTextColor(ui.theme.onSurfaceVariant)
            textSize = ui.scaled(18f)
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = localizedSelectionUi(settings, "Close suggestion popup", "Cerrar panel de sugerencias")
            background = ui.surface(9)
            setOnClickListener { hideSuggestions() }
        }
        val resize = object : TextView(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            this.text = "⤢"
            gravity = Gravity.CENTER
            setTextColor(ui.theme.onSurfaceVariant)
            textSize = ui.scaled(18f)
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = localizedSelectionUi(settings, "Resize suggestion popup width and height", "Redimensionar ancho y alto del panel de sugerencias")
            background = ui.surface(9)
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = BoundedScrollView(this, listMaxHeight).apply {
            isFillViewport = false
            clipToPadding = false
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val footer = FrameLayout(this)
        handle.rotation = 90f
        footer.addView(
            handle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        footer.addView(
            close,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL),
        )
        if (settings.suggestionResizeHandleEnabled) footer.addView(
            resize,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        content.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SuggestionOverlaySpec.HANDLE_HEIGHT_DP),
            ),
        )

        suggestions.forEach { suggestion ->
            val row = when (suggestion) {
                is PopupSuggestion.TextSnippet -> createSnippetSuggestionRow(
                    match = suggestion.textMatch,
                    trigger = suggestion.suggestionTrigger,
                    typed = suggestion.matchedText,
                    packageName = packageName,
                    settings = settings,
                    ui = ui,
                    browseMode = showAll,
                )
                is PopupSuggestion.VaultEntryItem -> createVaultSuggestionRow(
                    title = suggestion.entry.title,
                    subtitle = localizedSelectionUi(settings, "Vault entry", "Entrada de bóveda"),
                    trigger = suggestion.suggestionTrigger,
                    typed = suggestion.matchedText,
                    settings = settings,
                    ui = ui,
                    onClick = {
                        applyVaultSuggestion(
                            target = VaultTriggerTarget.Entry(suggestion.entry),
                            trigger = suggestion.suggestionTrigger,
                            browseMode = showAll,
                        )
                    },
                )
                is PopupSuggestion.VaultCategoryItem -> createVaultSuggestionRow(
                    title = suggestion.category.name,
                    subtitle = localizedSelectionUi(settings, "Vault category", "Categoría de bóveda"),
                    trigger = suggestion.suggestionTrigger,
                    typed = suggestion.matchedText,
                    settings = settings,
                    ui = ui,
                    onClick = {
                        applyVaultSuggestion(
                            target = VaultTriggerTarget.Category(suggestion.category),
                            trigger = suggestion.suggestionTrigger,
                            browseMode = showAll,
                        )
                    },
                )
                is PopupSuggestion.Action -> createActionSuggestionRow(
                    definition = suggestion.definition,
                    typed = suggestion.matchedText,
                    settings = settings,
                    ui = ui,
                )
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(SuggestionOverlaySpec.ROW_GAP_DP) },
            )
        }
        container.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = container.measuredHeight.takeIf { it > 0 } ?: estimatedHeight
        handle.setOnTouchListener(
            createDragListener(
                container = container,
                handle = handle,
                windowManager = windowManager,
                bounds = bounds,
                fallbackHeight = popupHeight,
            ),
        )
        if (settings.suggestionResizeHandleEnabled) {
            resize.setOnTouchListener(
                createResizeListener(
                    container = container,
                    scroll = scroll,
                    windowManager = windowManager,
                    bounds = bounds,
                    fallbackHeight = popupHeight,
                ),
            )
        }

        val safeBottom = safeBottom(bounds)
        val defaultTop = safeTop()
        val storedBottom = when {
            settings.suggestionPositionBottom >= 0 -> settings.suggestionPositionBottom
            settings.suggestionPositionY >= 0 -> settings.suggestionPositionY + popupHeight
            else -> defaultTop + popupHeight
        }
        val params = WindowManager.LayoutParams(
            popupWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.suggestionPositionX >= 0) {
                settings.suggestionPositionX
            } else {
                (bounds.width() - popupWidth) / 2
            }
            y = storedBottom - popupHeight
            x = x.coerceIn(horizontalMargin, (bounds.width() - popupWidth - horizontalMargin).coerceAtLeast(horizontalMargin))
            y = y.coerceIn(defaultTop, (safeBottom - popupHeight).coerceAtLeast(defaultTop))
        }
        runCatching {
            windowManager.addView(container, params)
            suggestionOverlay = container
            suggestionWindowParams = params
            suggestionAnchor = anchor
        }
    }

    private fun createSnippetSuggestionRow(
        match: TextMatch,
        trigger: String,
        typed: String,
        packageName: String,
        settings: AppSettings,
        ui: OverlayViews,
        browseMode: Boolean,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val verticalPadding = if (settings.suggestionCompactList) {
            SuggestionOverlaySpec.COMPACT_ROW_VERTICAL_PADDING_DP
        } else {
            SuggestionOverlaySpec.COMFORTABLE_ROW_VERTICAL_PADDING_DP
        }
        setPadding(dp(10), dp(verticalPadding), dp(10), dp(verticalPadding))
        isClickable = true
        isFocusable = true
        background = ui.surface(SuggestionOverlaySpec.ROW_RADIUS_DP)
        contentDescription = "Text match $trigger: ${matchPreview(match)}"
        setOnClickListener { applySuggestion(match, trigger, packageName, browseMode) }
        addView(TextView(this@ExpansionAccessibilityService).apply {
            text = highlightedShortcut(trigger, typed, settings.matchFromBeginning, ui.theme)
            setTextColor(ui.theme.onSurface)
            textSize = ui.scaled(if (settings.suggestionCompactList) 15f else 14f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (settings.suggestionCompactList) {
            if (match.label.isNotBlank()) addView(TextView(this@ExpansionAccessibilityService).apply {
                text = highlightedTemplateTokens(match.label.replace('\n', ' ').trim().take(PREVIEW_LENGTH), ui.theme)
                setTextColor(ui.theme.onSurfaceVariant)
                textSize = ui.scaled(12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        } else {
            addView(TextView(this@ExpansionAccessibilityService).apply {
                text = highlightedTemplateTokens(match.replace.trim().take(PREVIEW_LENGTH), ui.theme)
                setTextColor(ui.theme.onSurfaceVariant)
                textSize = ui.scaled(15f)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun createVaultSuggestionRow(
        title: String,
        subtitle: String,
        trigger: String,
        typed: String,
        settings: AppSettings,
        ui: OverlayViews,
        onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(7), dp(10), dp(7))
        isClickable = true
        isFocusable = true
        background = ui.surface(SuggestionOverlaySpec.ROW_RADIUS_DP)
        contentDescription = "$subtitle $trigger: $title"
        setOnClickListener { onClick() }

        addView(TextView(this@ExpansionAccessibilityService).apply {
            text = "▣"
            gravity = Gravity.CENTER
            setTextColor(ui.theme.primary)
            textSize = ui.scaled(17f)
            background = ui.surface(9, emphasized = true)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(9) })

        addView(LinearLayout(this@ExpansionAccessibilityService).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ExpansionAccessibilityService).apply {
                text = highlightedShortcut(trigger, typed, settings.matchFromBeginning, ui.theme)
                setTextColor(ui.theme.onSurface)
                textSize = ui.scaled(14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@ExpansionAccessibilityService).apply {
                text = "$subtitle · $title"
                setTextColor(ui.theme.onSurfaceVariant)
                textSize = ui.scaled(12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun createActionSuggestionRow(
        definition: ActionDefinition,
        typed: String,
        settings: AppSettings,
        ui: OverlayViews,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(7), dp(10), dp(7))
        isClickable = true
        isFocusable = true
        background = ui.surface(SuggestionOverlaySpec.ROW_RADIUS_DP, emphasized = true)
        contentDescription = "${definition.category.name.lowercase()} action ${definition.shortcut}: ${definition.title}"
        setOnClickListener { applyActionSuggestion(definition) }

        addView(TextView(this@ExpansionAccessibilityService).apply {
            text = actionCategoryGlyph(definition.category)
            gravity = Gravity.CENTER
            setTextColor(ui.theme.onPrimaryContainer)
            textSize = ui.scaled(17f)
            background = ui.surface(9, emphasized = true)
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(9) })

        addView(LinearLayout(this@ExpansionAccessibilityService).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ExpansionAccessibilityService).apply {
                text = highlightedShortcut(definition.shortcut, typed, settings.matchFromBeginning, ui.theme)
                setTextColor(ui.theme.onSecondaryContainer)
                textSize = ui.scaled(14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@ExpansionAccessibilityService).apply {
                text = definition.title
                setTextColor(ui.theme.onSecondaryContainer)
                textSize = ui.scaled(12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!settings.suggestionCompactList) addView(TextView(this@ExpansionAccessibilityService).apply {
                text = definition.description
                setTextColor(ui.theme.onSurfaceVariant)
                textSize = ui.scaled(12f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun actionCategoryGlyph(category: ActionCategory): String = when (category) {
        ActionCategory.NUMBER -> "∑"
        ActionCategory.TEXT -> "T"
        ActionCategory.SELECTION -> "◉"
        ActionCategory.DELETION -> "⌫"
        ActionCategory.CURSOR -> "↦"
        ActionCategory.CLIPBOARD -> "▣"
        ActionCategory.ANDROID -> "◇"
        ActionCategory.EXPANDA -> "⚡"
    }

    private fun applySuggestion(
        textMatch: TextMatch,
        trigger: String,
        packageName: String,
        browseMode: Boolean = false,
    ) {
        val anchor = suggestionAnchor ?: run {
            hideSuggestions()
            return
        }
        // Drop any cached snapshot the framework may still be serving before we
        // resolve the editor; suggestion taps often arrive right after a burst
        // of keystrokes, and stale text used to make matchRange fail silently.
        clearAccessibilityCache()
        val node = findAnchoredEditor(anchor) ?: run {
            Log.d(TAG, "applySuggestion: anchored editor gone, hiding popup")
            hideSuggestions()
            return
        }
        try {
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) {
                Log.d(TAG, "applySuggestion: node no longer editable, skipping")
                return
            }
            runCatching { node.refresh() }
            val text = editableText(node)
            val cursor = node.textSelectionEnd.takeIf { it in 0..text.length } ?: text.length
            val range = SuggestionApplyLocator.locate(
                text = text,
                cursor = cursor,
                trigger = trigger,
                browseMode = browseMode,
            ) ?: run {
                Log.d(TAG, "applySuggestion: cursor $cursor out of range for text of length ${text.length}")
                return
            }
            val currentSettings = settingsRepository.settings.value
            val match = ExpansionMatch(
                match = textMatch,
                replaceFrom = range.start,
                replaceTo = range.end,
                trailingDelimiter = "",
                matchedText = range.matchedText,
            )
            if (textMatch.selectionMode == TemplateSelectionMode.MANUAL && textMatch.replacements.size > 1) {
                @Suppress("DEPRECATION")
                showTemplateChooser(AccessibilityNodeInfo.obtain(node), text, match, packageName, currentSettings)
                return
            }
            val rendered = renderMatch(match)
            if (rendered.requiresInput) {
                @Suppress("DEPRECATION")
                showFormOverlay(AccessibilityNodeInfo.obtain(node), text, match, rendered, packageName, currentSettings)
            } else {
                applyExpansion(node, text, match, rendered, packageName, currentSettings)
            }
        } finally {
            node.recycle()
            hideSuggestions()
        }
    }

    private fun applyVaultSuggestion(
        target: VaultTriggerTarget,
        trigger: String,
        browseMode: Boolean,
    ) {
        val anchor = suggestionAnchor ?: run {
            hideSuggestions()
            return
        }
        clearAccessibilityCache()
        val node = findAnchoredEditor(anchor) ?: run {
            hideSuggestions()
            return
        }
        try {
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) return
            runCatching { node.refresh() }
            val text = editableText(node)
            val cursor = node.textSelectionEnd.takeIf { it in 0..text.length } ?: text.length
            val range = SuggestionApplyLocator.locate(
                text = text,
                cursor = cursor,
                trigger = trigger,
                browseMode = browseMode,
            ) ?: return
            val currentSettings = settingsRepository.settings.value
            val withoutTypedPrefix = text.removeRange(range.start, range.end)
            if (!setFieldText(
                    node = node,
                    originalText = text,
                    newText = withoutTypedPrefix,
                    selectionStart = range.start,
                    selectionEnd = range.start,
                    settings = currentSettings,
                )
            ) return

            lastAppliedText = withoutTypedPrefix
            lastAppliedAt = SystemClock.elapsedRealtime()
            hideSuggestions()
            when (target) {
                is VaultTriggerTarget.Entry -> showVaultOverlay(
                    entryId = target.entry.id,
                    anchor = anchor,
                    insertionCursor = range.start,
                )
                is VaultTriggerTarget.Category -> showVaultOverlay(
                    categoryName = target.category.name,
                    anchor = anchor,
                    insertionCursor = range.start,
                )
            }
        } finally {
            node.recycle()
            hideSuggestions()
        }
    }

    private fun applyActionSuggestion(shownDefinition: ActionDefinition) {
        val anchor = suggestionAnchor ?: run {
            hideSuggestions()
            return
        }
        clearAccessibilityCache()
        val node = findAnchoredEditor(anchor) ?: run {
            hideSuggestions()
            return
        }
        try {
            if (!node.isEditable || node.isPassword || isPasswordInput(node.inputType)) return
            runCatching { node.refresh() }
            val currentSettings = settingsRepository.settings.value
            val enabledActions = actionSettingsStore.enabledIds.value
            if (!currentSettings.suggestionShowActions || shownDefinition.id !in enabledActions) return

            val baseDefinition = ActionEngine.definitions.firstOrNull { it.id == shownDefinition.id } ?: return
            val effectiveTriggers = actionSettingsStore.triggerOverrides.value[baseDefinition.id]
                ?: baseDefinition.triggers
            val selectedTrigger = shownDefinition.shortcut
                .takeIf { it in effectiveTriggers }
                ?: effectiveTriggers.firstOrNull()
                ?: return
            val definition = baseDefinition.copy(
                shortcut = selectedTrigger,
                aliases = emptyList(),
            )
            val originalText = node.text?.toString() ?: ""
            val cursor = node.textSelectionEnd.takeIf { it in 0..originalText.length } ?: originalText.length
            val minimumCharacters = currentSettings.suggestionMinChars.coerceIn(1, MAX_SUGGESTION_LENGTH)
            // Same rationale as applySuggestion: never silently drop the tap. If the
            // typed prefix no longer matches, treat the tap as "insert the shortcut
            // at the caret and run the action against that".
            val typed = actionSuggestionPrefix(
                shortcut = definition.shortcut,
                text = originalText,
                cursor = cursor,
                minimumCharacters = minimumCharacters,
                fromBeginning = currentSettings.matchFromBeginning,
            )
            val commandStart = if (typed != null) cursor - typed.length else cursor
            val commandEnd = cursor
            val commandText = originalText.replaceRange(commandStart, commandEnd, definition.shortcut)
            val commandCursor = commandStart + definition.shortcut.length
            if (isClipboardInsertAction(definition.id)) {
                launchClipboardActionCapture(
                    node = node,
                    packageName = node.packageName?.toString().orEmpty(),
                    settings = currentSettings,
                    definition = definition,
                    replaceStart = commandStart,
                    replaceEnd = commandEnd,
                )
                return
            }
            val outcome = actionEngine.execute(
                ActionContext(
                    text = commandText,
                    cursor = commandCursor,
                    selectionStart = commandCursor,
                    selectionEnd = commandCursor,
                    clipboard = readClipboardTextCached(),
                ),
                enabledActionIds = setOf(definition.id),
                shortcutOverrides = mapOf(definition.id to definition.shortcut),
                triggerOverrides = mapOf(definition.id to listOf(definition.shortcut)),
            ) ?: return
            val selectionAction = definition.category == ActionCategory.SELECTION &&
                outcome.selectionStart != outcome.selectionEnd
            if (selectionAction) {
                programmaticSelectionUntil =
                    SystemClock.elapsedRealtime() + PROGRAMMATIC_SELECTION_GRACE_MS
                hideSelectionToolbar()
            }
            if (applyAction(node, originalText, outcome, currentSettings)) {
                lastAppliedText = outcome.text
                lastAppliedAt = SystemClock.elapsedRealtime()
                handleActionRequest(outcome.request, outcome.text)
                if (selectionAction) {
                    scheduleSelectionToolbarFromOutcome(
                        anchor = anchor,
                        packageName = node.packageName?.toString().orEmpty(),
                        outcome = outcome,
                        settings = currentSettings,
                    )
                }
            }
        } finally {
            node.recycle()
            hideSuggestions()
        }
    }

    private fun finishExpansion(match: TextMatch, packageName: String, settings: AppSettings) {
        if (settings.hapticFeedback) vibrate()
        // Sequential templates must advance even when usage statistics are disabled.
        scope.launch { repository.recordExpansion(match, packageName, settings.statisticsEnabled) }
    }

    private fun scheduleSuggestionValidation() {
        if (suggestionOverlay == null || suggestionAnchor == null) return
        cancelSuggestionValidation()
        val validation = Runnable { validateSuggestionAnchor() }
        suggestionValidation = validation
        mainHandler.postDelayed(validation, SUGGESTION_VALIDATION_DELAY_MS)
    }

    private fun cancelSuggestionValidation() {
        suggestionValidation?.let(mainHandler::removeCallbacks)
        suggestionValidation = null
    }

    private fun validateSuggestionAnchor() {
        suggestionValidation = null
        val anchor = suggestionAnchor ?: return
        val active = findAnchoredEditor(anchor)
        if (active == null) {
            hideSuggestions()
        } else {
            active.recycle()
        }
    }

    private fun createSuggestionAnchor(node: AccessibilityNodeInfo, packageName: String): SuggestionAnchor {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return SuggestionAnchor(
            packageName = packageName,
            windowId = node.windowId,
            uniqueId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) node.uniqueId else null,
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            bounds = AnchorGeometry(bounds.left, bounds.top, bounds.right, bounds.bottom),
        )
    }

    private fun findAnchoredEditor(
        anchor: SuggestionAnchor,
        requireActiveWindow: Boolean = true,
    ): AccessibilityNodeInfo? {
        val availableWindows = runCatching { windows }.getOrDefault(emptyList())
        val roots = mutableListOf<AccessibilityNodeInfo>()
        if (availableWindows.isNotEmpty()) {
            val anchorWindow = availableWindows.firstOrNull { it.id == anchor.windowId } ?: return null
            if (requireActiveWindow && !anchorWindow.isActive && !anchorWindow.isFocused) return null
            anchorWindow.root?.let(roots::add)
        } else {
            rootInActiveWindow?.let(roots::add)
        }
        var matched: AccessibilityNodeInfo? = null
        roots.forEach { root ->
            if (matched == null) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    val packageName = focused.packageName?.toString().orEmpty()
                    val candidate = createSuggestionAnchor(focused, packageName)
                    val valid = focused.isEditable &&
                        !focused.isPassword &&
                        !isPasswordInput(focused.inputType) &&
                        SuggestionAnchorPolicy.shouldKeep(anchor, candidate)
                    if (valid) matched = focused else focused.recycle()
                }
            }
            root.recycle()
        }
        return matched
    }

    private fun hideSuggestions() {
        cancelSuggestionValidation()
        val overlay = suggestionOverlay
        suggestionOverlay = null
        suggestionWindowParams = null
        suggestionAnchor = null
        if (overlay != null) {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay) }
        }
    }

    private fun actionSuggestionPrefix(
        shortcut: String,
        text: String,
        cursor: Int,
        minimumCharacters: Int,
        fromBeginning: Boolean,
    ): String? {
        if (cursor !in 0..text.length) return null
        val beforeCursor = text.substring(0, cursor)
        val minimum = minimumCharacters.coerceIn(1, MAX_SUGGESTION_LENGTH)
        val maximum = minOf(shortcut.length, beforeCursor.length, MAX_SUGGESTION_LENGTH)
        if (maximum < minimum) return null
        for (length in maximum downTo minimum) {
            val suffix = beforeCursor.takeLast(length)
            if (SuggestionMatcher.matchRange(shortcut, suffix, fromBeginning) != null) return suffix
        }
        return null
    }

    private fun currentToken(text: String, cursor: Int): String {
        if (cursor !in 0..text.length) return ""
        val before = text.substring(0, cursor)
        val boundary = before.indexOfLast(Char::isWhitespace)
        return before.substring(boundary + 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun highlightedShortcut(
        shortcut: String,
        typed: String,
        fromBeginning: Boolean,
        theme: NativeThemeTokens,
    ): CharSequence {
        val text = SpannableString(shortcut)
        SuggestionMatcher.matchRange(shortcut, typed, fromBeginning)?.let { range ->
            text.setSpan(
                BackgroundColorSpan(theme.warningContainer),
                range.first,
                range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return text
    }

    private fun highlightedTemplateTokens(value: String, theme: NativeThemeTokens): CharSequence {
        val text = SpannableString(value)
        Regex("\\{\\{[^{}]+\\}\\}|\\{[^{}]+\\}").findAll(value).forEach { match ->
            text.setSpan(BackgroundColorSpan(theme.primaryContainer), match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(theme.onPrimaryContainer), match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun matchPreview(match: TextMatch): String =
        match.label.ifBlank { match.replace }
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(PREVIEW_LENGTH)

    private fun displayBounds(windowManager: WindowManager): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(windowManager.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also { metrics ->
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }.let { metrics -> Rect(0, 0, metrics.widthPixels, metrics.heightPixels) }
        }
    }

    /** Default top edge for the suggestion popup when no position has been saved yet. */
    private fun safeTop(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusInset = if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0
        return (statusInset + dp(12)).coerceAtLeast(dp(12))
    }

    /** Keep the window above both 3-button navigation bars and gesture handles. */
    private fun safeBottom(bounds: Rect): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navigationInset = (if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0)
            .coerceAtLeast(dp(24))
        return (bounds.bottom - navigationInset - dp(14)).coerceAtLeast(dp(80))
    }

    private fun constrainSuggestionPosition(
        container: View,
        params: WindowManager.LayoutParams,
        bounds: Rect,
        fallbackHeight: Int,
    ) {
        val margin = dp(12)
        // LayoutParams changes before View.width catches up while resizing. Prefer the
        // requested window width so clamping never jumps back to the previous size.
        val width = params.width.takeIf { it > 0 }
            ?: container.measuredWidth.takeIf { it > 0 }
            ?: container.width
        val height = container.measuredHeight.takeIf { it > 0 }
            ?: container.height.takeIf { it > 0 }
            ?: fallbackHeight
        val maxX = (bounds.width() - width - margin).coerceAtLeast(margin)
        val maxY = (safeBottom(bounds) - height).coerceAtLeast(dp(12))
        params.x = params.x.coerceIn(margin, maxX)
        params.y = params.y.coerceIn(dp(12), maxY)
    }

    private fun createSelectionToolbarDragListener(
        container: View,
        handle: View,
        windowManager: WindowManager,
        bounds: Rect,
    ): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var startWidth = 0
        var startHeight = 0
        var dragging = false
        var resizing = false
        var longPressTask: Runnable? = null

        fun cancelLongPress() {
            longPressTask?.let(mainHandler::removeCallbacks)
            longPressTask = null
        }

        fun constrain(params: WindowManager.LayoutParams) {
            val margin = dp(8)
            val top = safeTop()
            val bottom = safeBottom(bounds)
            val width = params.width.coerceAtLeast(1)
            val height = params.height.coerceAtLeast(1)
            params.x = params.x.coerceIn(
                margin,
                (bounds.width() - width - margin).coerceAtLeast(margin),
            )
            params.y = params.y.coerceIn(
                top,
                (bottom - height).coerceAtLeast(top),
            )
        }

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val params = selectionToolbarWindowParams ?: return@OnTouchListener false
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    startWidth = params.width
                    startHeight = params.height
                    dragging = false
                    resizing = false
                    cancelLongPress()
                    val task = Runnable {
                        if (!dragging) {
                            resizing = true
                            container.alpha = DRAG_ALPHA
                            (handle as? TextView)?.text = "↘"
                            if (settingsRepository.settings.value.hapticFeedback) vibrateTick()
                        }
                    }
                    longPressTask = task
                    mainHandler.postDelayed(task, longPressTimeout)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val params = selectionToolbarWindowParams ?: return@OnTouchListener false
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (resizing) {
                        val minWidth = (
                            bounds.width() * SettingsRepository.MIN_SELECTION_TOOLBAR_WIDTH
                        ).roundToInt()
                        val maxWidth = (
                            bounds.width() * SettingsRepository.MAX_SELECTION_TOOLBAR_WIDTH
                        ).roundToInt()
                        params.width = (startWidth + deltaX.toInt()).coerceIn(minWidth, maxWidth)
                        params.height = (startHeight + deltaY.toInt()).coerceIn(
                            dp(SettingsRepository.MIN_SELECTION_TOOLBAR_HEIGHT_DP),
                            dp(SettingsRepository.MAX_SELECTION_TOOLBAR_HEIGHT_DP),
                        )
                        constrain(params)
                        runCatching { windowManager.updateViewLayout(container, params) }
                    } else if (!dragging &&
                        (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)
                    ) {
                        cancelLongPress()
                        dragging = true
                        container.alpha = DRAG_ALPHA
                        if (settingsRepository.settings.value.hapticFeedback) vibrateTick()
                    }
                    if (dragging) {
                        params.x = startX + deltaX.toInt()
                        params.y = startY + deltaY.toInt()
                        constrain(params)
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    val params = selectionToolbarWindowParams ?: return@OnTouchListener false
                    container.alpha = 1f
                    (handle as? TextView)?.text = "⠿"
                    when {
                        resizing -> {
                            constrain(params)
                            runCatching { windowManager.updateViewLayout(container, params) }
                            val widthFraction = params.width.toFloat() / bounds.width().coerceAtLeast(1)
                            val heightDp = (
                                params.height / resources.displayMetrics.density
                            ).roundToInt()
                            scope.launch {
                                settingsRepository.setSelectionToolbarSize(widthFraction, heightDp)
                                settingsRepository.setSelectionToolbarPosition(params.x, params.y)
                            }
                        }
                        dragging -> {
                            constrain(params)
                            runCatching { windowManager.updateViewLayout(container, params) }
                            scope.launch {
                                settingsRepository.setSelectionToolbarPosition(params.x, params.y)
                            }
                        }
                        else -> handle.performClick()
                    }
                    dragging = false
                    resizing = false
                    true
                }

                else -> false
            }
        }
    }

    private fun createDragListener(
        container: View,
        handle: View,
        windowManager: WindowManager,
        bounds: Rect,
        fallbackHeight: Int,
    ): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val params = suggestionWindowParams ?: return@OnTouchListener false
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val params = suggestionWindowParams ?: return@OnTouchListener false
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (!dragging && (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)) {
                        dragging = true
                        container.alpha = DRAG_ALPHA
                    }
                    if (dragging) {
                        params.x = startX + deltaX.toInt()
                        params.y = startY + deltaY.toInt()
                        constrainSuggestionPosition(container, params, bounds, fallbackHeight)
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val params = suggestionWindowParams ?: return@OnTouchListener false
                    if (dragging) {
                        container.alpha = 1f
                        constrainSuggestionPosition(container, params, bounds, fallbackHeight)
                        runCatching { windowManager.updateViewLayout(container, params) }
                        // Persist only after the gesture ends; DataStore is never written for every MOVE.
                        scope.launch {
                            settingsRepository.setSuggestionPosition(params.x, params.y, container.height)
                        }
                    } else {
                        handle.performClick()
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun createResizeListener(
        container: View,
        scroll: BoundedScrollView,
        windowManager: WindowManager,
        bounds: Rect,
        fallbackHeight: Int,
    ): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startWidth = 0
        var startListHeight = 0
        var fixedRight = 0
        var fixedBottom = 0
        var maximumListHeight = 0
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val params = suggestionWindowParams ?: return@OnTouchListener false
                    downX = event.rawX
                    downY = event.rawY
                    startWidth = params.width
                    startListHeight = scroll.maxHeightPx
                    fixedRight = params.x + params.width
                    fixedBottom = params.y + (
                        container.measuredHeight.takeIf { it > 0 }
                            ?: container.height.takeIf { it > 0 }
                            ?: fallbackHeight
                        )
                    val nonListHeight = (
                        container.measuredHeight.takeIf { it > 0 }
                            ?: container.height.takeIf { it > 0 }
                            ?: fallbackHeight
                        ) - scroll.measuredHeight
                    maximumListHeight = minOf(
                        dp(SettingsRepository.MAX_SUGGESTION_HEIGHT_DP),
                        fixedBottom - dp(12) - nonListHeight.coerceAtLeast(0),
                    ).coerceAtLeast(dp(SettingsRepository.MIN_SUGGESTION_HEIGHT_DP))
                    container.alpha = DRAG_ALPHA
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val params = suggestionWindowParams ?: return@OnTouchListener false
                    val result = SuggestionResizePolicy.resize(
                        startWidthPx = startWidth,
                        startListHeightPx = startListHeight,
                        horizontalDragPx = (event.rawX - downX).toInt(),
                        verticalDragPx = (event.rawY - downY).toInt(),
                        minWidthPx = dp(MIN_WIDTH_DP),
                        maxWidthPx = (bounds.width() - dp(24)).coerceAtLeast(dp(MIN_WIDTH_DP)),
                        minListHeightPx = dp(SettingsRepository.MIN_SUGGESTION_HEIGHT_DP),
                        maxListHeightPx = maximumListHeight,
                    )
                    scroll.setMaxHeight(result.listHeightPx)
                    params.width = result.widthPx
                    params.x = fixedRight - result.widthPx
                    container.measure(
                        View.MeasureSpec.makeMeasureSpec(result.widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                    params.y = fixedBottom - container.measuredHeight.coerceAtLeast(1)
                    constrainSuggestionPosition(container, params, bounds, fallbackHeight)
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val params = suggestionWindowParams ?: return@OnTouchListener false
                    container.alpha = 1f
                    constrainSuggestionPosition(container, params, bounds, fallbackHeight)
                    runCatching { windowManager.updateViewLayout(container, params) }
                    scope.launch {
                        settingsRepository.setSuggestionLayout(
                            params.x,
                            params.y,
                            container.measuredHeight.takeIf { it > 0 }
                                ?: container.height.takeIf { it > 0 }
                                ?: fallbackHeight,
                            params.width.toFloat() / bounds.width().coerceAtLeast(1),
                            (scroll.maxHeightPx / resources.displayMetrics.density).roundToInt(),
                        )
                    }
                    view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private class BoundedScrollView(context: android.content.Context, maxHeight: Int) : ScrollView(context) {
        var maxHeightPx: Int = maxHeight
            private set

        fun setMaxHeight(heightPx: Int) {
            if (maxHeightPx == heightPx) return
            maxHeightPx = heightPx
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val boundedHeight = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, boundedHeight)
        }
    }

    companion object {
        private const val VAULT_KEYBOARD_DIALOG_LIFT_DP = 28
        @Volatile private var activeService: WeakReference<ExpansionAccessibilityService>? = null

        /** Opens the real overlay for the currently focused editor, without requiring typed characters. */
        fun requestSuggestionOverlay(): Boolean {
            val service = activeService?.get() ?: return false
            service.mainHandler.post(service::showAllSuggestionsForFocusedInput)
            return true
        }

        internal fun onClipboardCaptured() {
            activeService?.get()?.let { s ->
                s.mainHandler.post(s::onClipboardCaptureComplete)
            }
        }

        internal fun onClipboardCaptureFailed() {
            activeService?.get()?.let { s ->
                s.mainHandler.post(s::onClipboardCaptureCancel)
            }
        }

        private const val TAG = "ExpandaAccessibility"
        private const val REENTRANCY_WINDOW_MS = 1_000L
        private const val CLIPBOARD_RETRY_ATTEMPTS = 4
        private const val CLIPBOARD_RETRY_DELAY_MS = 200L
        private const val CLIPBOARD_OVERLAY_TIMEOUT_MS = 800L
        private const val CLIPBOARD_OVERLAY_SETTLE_MS = 100L
        private const val SUGGESTION_VALIDATION_DELAY_MS = 140L
        private const val SELECTION_TOOLBAR_VALIDATION_DELAY_MS = 180L
        private const val SELECTION_TOOLBAR_STABILITY_DELAY_MS = 280L
        private const val SELECTION_TOOLBAR_DELETE_SUPPRESSION_MS = 420L
        private const val PROGRAMMATIC_SELECTION_GRACE_MS = 900L
        private const val SMART_CURSOR_CASE_TIMEOUT_MS = 15_000L
        private const val CLIPBOARD_RESTORE_DELAY_MS = 250L
        private const val VAULT_GBOARD_COPY_INTERVAL_MS = 650L
        private const val VAULT_CLIPBOARD_CLEAR_MS = 60_000L
        private const val VAULT_SYSTEM_UI_GRACE_MS = 250L
        /** Two frames at 60 Hz — enough for Blink to finish applying ACTION_SET_TEXT. */
        private const val WEBVIEW_SELECTION_RETRY_DELAY_MS = 32L
        private const val MAX_SUGGESTIONS = 24
        private const val MAX_BROWSE_SUGGESTIONS = 200
        private data class SelectionToolbarAction(
            val id: String,
            val label: String,
            val description: String,
            val isGroup: Boolean = false,
            val groupActionIds: List<String> = emptyList(),
            val groupActionLabels: Map<String, String> = emptyMap(),
        )

        private const val SELECTION_UNDO_ID = "__selection_undo__"
        private const val SELECTION_TRANSFORMS_MENU_ID = "__selection_transforms__"
        private const val SELECTION_MORE_MENU_ID = "__selection_more__"
        private const val SELECTION_FIND_REPLACE_ID = "find_replace"
        private const val SELECTION_TEXT_COUNTER_ID = "text_counter"
        private const val SELECTION_REPEAT_TEXT_ID = "repeat_text"
        private const val SELECTION_PREFIX_SUFFIX_ID = "prefix_suffix"
        private const val MAX_SELECTION_UNDO_HISTORY = 10
        private const val HAPTIC_TICK_MS = 10L
        private const val HAPTIC_CONFIRM_MS = 25L

        private val SELECTION_INTERACTIVE_ACTION_IDS = setOf(
            SELECTION_FIND_REPLACE_ID,
            SELECTION_TEXT_COUNTER_ID,
            SELECTION_REPEAT_TEXT_ID,
            SELECTION_PREFIX_SUFFIX_ID,
        )

        private val SELECTION_CONTEXT_ACTION_IDS = listOf(
            SELECTION_FIND_REPLACE_ID,
            "remove_diacritics",
            "space_underscore",
            "space_dash",
            "underscore_space",
            "dash_space",
            "trim_spaces",
            "remove_all_spaces",
            "delete_blank_lines",
            "remove_duplicate_lines",
            "remove_duplicate_words",
            "remove_line_breaks",
            "sort_lines",
            "number_lines",
            "reverse_text",
            "reverse_lines",
            "reverse_words",
            "math_replace",
            "math_append",
            "number_space",
            "number_period",
            "number_comma",
        )

        private val SELECTION_CATALOG_ACTION_IDS = listOf(
            "uppercase",
            "lowercase",
            "sentence_case",
            "title_case",
            SELECTION_FIND_REPLACE_ID,
            "sort_lines",
            SELECTION_TEXT_COUNTER_ID,
            SELECTION_REPEAT_TEXT_ID,
            "trim_spaces",
            "remove_all_spaces",
            "delete_blank_lines",
            "remove_duplicate_lines",
            "remove_duplicate_words",
            "remove_line_breaks",
            SELECTION_PREFIX_SUFFIX_ID,
            "number_lines",
            "reverse_text",
            "reverse_lines",
            "reverse_words",
            "remove_diacritics",
            "space_underscore",
            "space_dash",
            "underscore_space",
            "dash_space",
            "math_replace",
            "math_append",
            "number_space",
            "number_period",
            "number_comma",
        )

        private const val MAX_SUGGESTION_LENGTH = 32
        private const val PREVIEW_LENGTH = 220
        private const val DRAG_ALPHA = 0.55f
        private const val MIN_WIDTH_DP = 180
        /** MainActivity may consume this extra to open its snippet editor. */
        const val EXTRA_OPEN_NEW_SNIPPET = "dev.diego.expanda.OPEN_NEW_SNIPPET"

    }
}
