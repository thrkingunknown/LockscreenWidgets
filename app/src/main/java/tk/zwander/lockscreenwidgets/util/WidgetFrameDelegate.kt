package tk.zwander.lockscreenwidgets.util

import android.annotation.SuppressLint
import android.app.IWallpaperManager
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.*
import android.view.*
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.R.attr.screenOrientation
import com.arasthel.spannedgridlayoutmanager.SpannedGridLayoutManager
import tk.zwander.lockscreenwidgets.R
import tk.zwander.lockscreenwidgets.activities.DismissOrUnlockActivity
import tk.zwander.lockscreenwidgets.adapters.WidgetFrameAdapter
import tk.zwander.lockscreenwidgets.data.Mode
import tk.zwander.lockscreenwidgets.data.WidgetType
import tk.zwander.lockscreenwidgets.databinding.WidgetFrameBinding
import tk.zwander.lockscreenwidgets.host.WidgetHostCompat
import tk.zwander.lockscreenwidgets.services.Accessibility
import tk.zwander.lockscreenwidgets.views.WidgetFrameView
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Handle most of the logic involving the widget frame.
 * TODO: make this work with multiple frame "clients" (i.e. a preview in MainActivity).
 */
class WidgetFrameDelegate private constructor(context: Context) : ContextWrapper(context),
    IInformationCallback, EventObserver {
    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: WidgetFrameDelegate? = null

        private val hasInstance: Boolean
            get() = instance != null

        fun peekInstance(context: Context): WidgetFrameDelegate? {
            if (!hasInstance) {
                context.logUtils.debugLog("Accessibility isn't running yet")

                return null
            }

            return getInstance(context)
        }

        fun getInstance(context: Context): WidgetFrameDelegate {
            return instance ?: run {
                if (context !is Accessibility) {
                    throw IllegalStateException("Delegate can only be initialized by Accessibility Service!")
                } else {
                    WidgetFrameDelegate(context).also {
                        instance = it
                    }
                }
            }
        }

        fun invalidateInstance() {
            instance = null
        }
    }

    var state: State = State()
        private set(newState) {
            var actualNewState = newState
            val oldState = field

            // Extra state checks //
            if (actualNewState.notificationsPanelFullyExpanded != oldState.notificationsPanelFullyExpanded) {
                actualNewState = actualNewState.copy(isPendingNotificationStateChange = true, isPreview = false)
            }

            if (actualNewState.isScreenOn != oldState.isScreenOn && !actualNewState.isScreenOn) {
                actualNewState = actualNewState.copy(isPreview = false, notificationsPanelFullyExpanded = false)
            }

            if (actualNewState.isTempHide != oldState.isTempHide && actualNewState.isTempHide) {
                actualNewState = actualNewState.copy(isPreview = false)
            }
            // ------------ //

            field = actualNewState

            if (actualNewState.isPreview != oldState.isPreview) {
                if (actualNewState.isPreview) {
                    if (canShow()) {
                        addWindow(wm)
                    }
                } else {
                    if (!canShow()) {
                        removeWindow(wm)
                    }
                }
            }
        }

    override val saveMode: Mode
        get() = when {
            state.isPreview -> Mode.PREVIEW
            state.notificationsPanelFullyExpanded && prefManager.showInNotificationCenter -> {
                if (kgm.isKeyguardLocked && prefManager.separatePosForLockNC) {
                    Mode.LOCK_NOTIFICATION
                } else {
                    Mode.NOTIFICATION
                }
            }
            else -> Mode.LOCK_NORMAL
        }

    //The size, position, and such of the widget frame on the lock screen.
    private val params = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        width = dpAsPx(prefManager.getCorrectFrameWidth(saveMode))
        height = dpAsPx(prefManager.getCorrectFrameHeight(saveMode))

        x = prefManager.getCorrectFrameX(saveMode)
        y = prefManager.getCorrectFrameY(saveMode)

        gravity = Gravity.CENTER

        flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        format = PixelFormat.RGBA_8888
    }
    private val wallpaper = getSystemService(Context.WALLPAPER_SERVICE) as WallpaperManager
    private val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val widgetManager = AppWidgetManager.getInstance(this)!!
    private val widgetHost = WidgetHostCompat.getInstance(this, 1003) {
        DismissOrUnlockActivity.launch(this)
    }
    private val shortcutIdManager = ShortcutIdManager.getInstance(this, widgetHost)

    //The actual frame View
    private val binding = WidgetFrameBinding.inflate(LayoutInflater.from(ContextThemeWrapper(this, R.style.AppTheme)))
    private val gridLayoutManager = SpannedLayoutManager()
    private val adapter = WidgetFrameAdapter(widgetManager, widgetHost, params) { _, item ->
        binding.removeWidgetConfirmation.root.show(item)
    }
    private val touchHelperCallback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT or ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return adapter.onMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                .also { moved ->
                    if (moved) {
                        updateState { it.copy(updatedForMove = true) }
                        prefManager.currentWidgets = LinkedHashSet(adapter.widgets)
                        adapter.currentEditingInterfacePosition = -1
                    }
                }
        }

        override fun getDragDirs(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            return if (viewHolder is WidgetFrameAdapter.AddWidgetVH || prefManager.lockWidgetFrame) 0
            else super.getDragDirs(recyclerView, viewHolder)
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                viewHolder?.itemView?.alpha = 0.5f
                updateState { it.copy(isHoldingItem = true) }

                //The user has long-pressed a widget. Show the editing UI on that widget.
                //If the UI is already shown on it, hide it.
                val adapterPos = viewHolder?.bindingAdapterPosition ?: -1
                adapter.currentEditingInterfacePosition =
                    if (adapter.currentEditingInterfacePosition == adapterPos) -1 else adapterPos
            }

            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)

            updateState { it.copy(isHoldingItem = false) }
            viewHolder.itemView.alpha = 1.0f
        }

        override fun interpolateOutOfBoundsScroll(
            recyclerView: RecyclerView,
            viewSize: Int,
            viewSizeOutOfBounds: Int,
            totalSize: Int,
            msSinceStartScroll: Long
        ): Int {
            //The default scrolling speed is *way* too fast. Slow it down a bit.
            val direction = sign(viewSizeOutOfBounds.toFloat()).toInt()
            return (viewSize * 0.01f * direction).roundToInt()
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    }

    private val kgm = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private val sharedPreferencesChangeHandler = HandlerRegistry {
        handler(PrefManager.KEY_CURRENT_WIDGETS) {
            //Make sure the adapter knows of any changes to the widget list
            if (!state.updatedForMove) {
                //Only run the update if it wasn't generated by a reorder event
                adapter.updateWidgets(prefManager.currentWidgets.toList())

                mainHandler.postDelayed({
                    gridLayoutManager.scrollToPosition(prefManager.currentPage)
                }, 50)
            } else {
                updateState { it.copy(updatedForMove = false) }
            }
        }
        handler(PrefManager.KEY_PAGE_INDICATOR_BEHAVIOR) {
            binding.frame.updatePageIndicatorBehavior()
        }
        handler(
            PrefManager.KEY_FRAME_ROW_COUNT,
            PrefManager.KEY_FRAME_COL_COUNT
        ) {
            updateRowColCount()
            adapter.updateViews()
        }
        handler(PrefManager.KEY_FRAME_BACKGROUND_COLOR) {
            binding.frame.updateFrameBackground()
        }
        handler(PrefManager.KEY_FRAME_MASKED_MODE, PrefManager.KEY_MASKED_MODE_DIM_AMOUNT) {
            updateWallpaperLayerIfNeeded()
        }
        handler(PrefManager.KEY_BLUR_BACKGROUND, PrefManager.KEY_BLUR_BACKGROUND_AMOUNT) {
            updateBlur()
        }
        handler(
            PrefManager.KEY_SHOW_DEBUG_ID_VIEW,
            PrefManager.KEY_DEBUG_LOG
        ) {
            binding.frame.updateDebugIdViewVisibility()
        }
        handler(PrefManager.KEY_SHOW_IN_NOTIFICATION_CENTER) {
            updateState { it.copy(isPendingNotificationStateChange = true) }
        }
        handler(PrefManager.KEY_FRAME_CORNER_RADIUS) {
            binding.frame.updateCornerRadius()
        }
        handler(
            PrefManager.KEY_POS_X,
            PrefManager.KEY_POS_Y,
            PrefManager.KEY_NOTIFICATION_POS_X,
            PrefManager.KEY_NOTIFICATION_POS_Y,
            PrefManager.KEY_LOCK_NOTIFICATION_POS_X,
            PrefManager.KEY_LOCK_NOTIFICATION_POS_Y
        ) {
            updateParamsIfNeeded()
        }
        handler(
            PrefManager.KEY_LOCK_WIDGET_FRAME
        ) {
            adapter.currentEditingInterfacePosition = -1
        }
    }

    private val showWallpaperLayerCondition: Boolean
        get() = !state.isPreview && prefManager.maskedMode && (!state.notificationsPanelFullyExpanded || !prefManager.showInNotificationCenter)

    override fun onEvent(event: Event) {
        when (event) {
            Event.FrameMoveFinished -> updateWallpaperLayerIfNeeded()
            Event.TempHide -> {
                updateState { it.copy(isTempHide = true) }
            }
            Event.NightModeUpdate -> {
                adapter.updateViews()
            }
            is Event.FrameResized -> {
                when (event.which) {
                    Event.FrameResized.Side.LEFT -> {
                        params.width -= event.velocity
                        params.x += (event.velocity / 2)
                    }
                    Event.FrameResized.Side.TOP -> {
                        params.height -= event.velocity
                        params.y += (event.velocity / 2)
                    }
                    Event.FrameResized.Side.RIGHT -> {
                        params.width += event.velocity
                        params.x += (event.velocity / 2)
                    }
                    Event.FrameResized.Side.BOTTOM -> {
                        params.height += event.velocity
                        params.y += (event.velocity / 2)
                    }
                }

                prefManager.setCorrectFrameWidth(saveMode, pxAsDp(params.width))
                prefManager.setCorrectFrameHeight(saveMode, pxAsDp(params.height))

                updateOverlay()

                if (event.isUp) {
                    adapter.onResizeObservable.notifyObservers()
                    updateWallpaperLayerIfNeeded()
                }
            }
            //We only really want to be listening to widget changes
            //while the frame is on-screen. Otherwise, we're wasting battery.
            is Event.FrameAttachmentState -> {
                try {
                    if (event.attached) {
                        widgetHost.startListening()
                        //Even with the startListening() call above,
                        //it doesn't seem like pending updates always get
                        //dispatched. Rebinding all the widgets forces
                        //them to update.
                        mainHandler.postDelayed({
                            updateBlur()
                            updateWallpaperLayerIfNeeded()
                            adapter.updateViews()
                            gridLayoutManager.scrollToPosition(prefManager.currentPage)
                        }, 50)
                    } else {
                        widgetHost.stopListening()
                    }
                } catch (e: NullPointerException) {
                    //The stupid "Attempt to read from field 'com.android.server.appwidget.AppWidgetServiceImpl$ProviderId
                    //com.android.server.appwidget.AppWidgetServiceImpl$Provider.id' on a null object reference"
                    //Exception is thrown on stopListening() as well for some reason.
                }
            }
            is Event.FrameMoved -> {
                params.x += event.velX.toInt()
                params.y += event.velY.toInt()

                updateOverlay()

                prefManager.setCorrectFramePos(saveMode, params.x, params.y)
            }
            is Event.FrameIntercept -> forceWakelock(wm, event.down)
            is Event.RemoveWidgetConfirmed -> {
                if (event.remove) {
                    prefManager.currentWidgets = prefManager.currentWidgets.apply {
                        remove(event.item)
                        when (event.item?.safeType) {
                            WidgetType.WIDGET -> widgetHost.deleteAppWidgetId(event.item.id)
                            WidgetType.SHORTCUT -> shortcutIdManager.removeShortcutId(event.item.id)
                            else -> {}
                        }
                    }
                    adapter.currentEditingInterfacePosition = -1
                }
            }
            else -> {}
        }
    }

    fun onCreate() {
        sharedPreferencesChangeHandler.register(this)
        gridLayoutManager.spanSizeLookup = adapter.spanSizeLookup
        binding.widgetsPager.apply {
            adapter = this@WidgetFrameDelegate.adapter
            layoutManager = gridLayoutManager
            setHasFixedSize(true)
            ItemTouchHelper(touchHelperCallback).attachToRecyclerView(this)
        }

        updateRowColCount()
        adapter.updateWidgets(prefManager.currentWidgets.toList())

        binding.frame.informationCallback = this

        //Scroll to the stored page, making sure to catch a potential
        //out-of-bounds error.
        try {
            gridLayoutManager.scrollToPosition(prefManager.currentPage)
        } catch (_: Exception) {}

        eventManager.apply {
            addObserver(this@WidgetFrameDelegate)
        }
    }

    fun onDestroy() {
        sharedPreferencesChangeHandler.unregister(this)
        eventManager.apply {
            removeObserver(this@WidgetFrameDelegate)
        }
    }

    fun updateState(transform: (State) -> State) {
        state = transform(state)
    }

    /**
     * Make sure the number of rows/columns in the widget frame reflects the user-selected value.
     */
    fun updateRowColCount() {
        gridLayoutManager.apply {
            val rowCount = prefManager.frameRowCount
            val colCount = prefManager.frameColCount

            this.rowCount = rowCount
            this.columnCount = colCount
        }
    }

    fun addWindow(wm: WindowManager) {
        if (!binding.frame.isAttachedToWindow) {
            updateParamsIfNeeded()
        }
        binding.frame.addWindow(wm, params)
    }

    fun removeWindow(wm: WindowManager) {
        adapter.currentEditingInterfacePosition = -1

        binding.frame.removeWindow(wm)
    }

    fun setNewDebugIdItems(items: List<String>) {
        binding.frame.setNewDebugIdItems(items)
    }

    /**
     * Check if the widget frame should show onscreen. There are quite a few conditions for this.
     * This method attempts to check those conditions in increasing order of intensiveness (check simple
     * members first, then try SharedPreferences, then use IPC methods).
     *
     * The widget frame can only show if ALL of the following conditions are met:
     *
     * =======
     * - [saveMode] is [Mode.PREVIEW]
     * =======
     * OR
     * =======
     * - [State.isScreenOn] is true
     * - [State.isTempHide] is false
     * - [State.notificationsPanelFullyExpanded] is true AND [PrefManager.showInNotificationCenter] is true
     * - [State.hideForPresentIds] is false
     * - [State.hideForNonPresentIds] is false
     * - [PrefManager.widgetFrameEnabled] is true
     * - [PrefManager.hideInLandscape] is false OR [screenOrientation] represents a portrait rotation
     * =======
     * OR
     * =======
     * - [State.wasOnKeyguard] is true
     * - [State.isScreenOn] is true (i.e. the display is properly on: not in Doze or on the AOD)
     * - [State.isTempHide] is false
     * - [PrefManager.showOnMainLockScreen] is true OR [PrefManager.showInNotificationCenter] is false
     * - [PrefManager.hideOnFaceWidgets] is false OR [State.isOnFaceWidgets] is false
     * - [State.currentAppLayer] is less than 0 (i.e. doesn't exist)
     * - [State.isOnEdgePanel] is false
     * - [State.isOnScreenOffMemo] is false
     * - [State.onMainLockscreen] is true OR [State.showingNotificationsPanel] is true OR [PrefManager.hideOnSecurityPage] is false
     * - [State.showingNotificationsPanel] is false OR [PrefManager.hideOnNotificationShade] is false (OR [State.notificationsPanelFullyExpanded] is true AND [PrefManager.showInNotificationCenter] is true
     * - [State.notificationCount] is 0 (i.e. no notifications shown on lock screen, not necessarily no notifications at all) OR [PrefManager.hideOnNotifications] is false
     * - [State.hideForPresentIds] is false OR [PrefManager.presentIds] is empty
     * - [State.hideForNonPresentIds] is false OR [PrefManager.nonPresentIds] is empty
     * - [PrefManager.hideInLandscape] is false OR [screenOrientation] represents a portrait rotation
     * - [PrefManager.widgetFrameEnabled] is true (i.e. the widget frame is actually enabled)
     * =======
     */
    fun canShow(): Boolean {
        fun forPreview(): Boolean {
            return state.isPreview
        }

        fun forCommon(): Boolean {
            return state.isScreenOn
                    && !state.isTempHide
                    && !state.hideForPresentIds
                    && !state.hideForNonPresentIds
                    && prefManager.widgetFrameEnabled
                    && (!prefManager.hideInLandscape || state.screenOrientation == Surface.ROTATION_0 || state.screenOrientation == Surface.ROTATION_180)
        }

        fun forNotificationCenter(): Boolean {
            return (state.notificationsPanelFullyExpanded && prefManager.showInNotificationCenter)
                    && forCommon()
        }

        fun forLockscreen(): Boolean {
            return state.wasOnKeyguard
                    && (prefManager.showOnMainLockScreen || !prefManager.showInNotificationCenter)
                    && (!prefManager.hideOnFaceWidgets || !state.isOnFaceWidgets)
                    && state.currentAppLayer < 0
                    && !state.isOnEdgePanel
                    && !state.isOnScreenOffMemo
                    && (state.onMainLockscreen || state.showingNotificationsPanel || !prefManager.hideOnSecurityPage)
                    && (!state.showingNotificationsPanel || !prefManager.hideOnNotificationShade)
                    && (state.notificationCount == 0 || !prefManager.hideOnNotifications)
                    && forCommon()
        }

        return (forPreview() || forNotificationCenter() || forLockscreen()).also {
                logUtils.debugLog(
                    "canShow: $it\n" +
                            "state: $state\n " +
                            "showOnMainLockScreen: ${prefManager.showOnMainLockScreen}\n" +
                            "widgetFrameEnabled: ${prefManager.widgetFrameEnabled}\n" +
                            "hideOnSecurityPage: ${prefManager.hideOnSecurityPage}\n" +
                            "hideOnNotifications: ${prefManager.hideOnNotifications}\n" +
                            "hideOnNotificationShade: ${prefManager.hideOnNotificationShade}\n" +
                            "presentIds: ${prefManager.presentIds}\n" +
                            "nonPresentIds: ${prefManager.nonPresentIds}\n" +
                            "hideInLandscape: ${prefManager.hideInLandscape}\n" +
                            "showInNotificationCenter: ${prefManager.showInNotificationCenter}\n"
                )
            }
    }

    /**
     * Compute and draw the appropriate portion of the wallpaper as the widget background,
     * if masked mode is enabled.
     *
     * TODO: this doesn't work properly on a lot of devices. It seems to be something to do with the scale.
     * TODO: I don't know enough about [Matrix]es to fix it.
     *
     * TODO: There are also a lot of limitations related to wallpaper offsets. It doesn't seem to be
     * TODO: possible to retrieve those offsets unless you're the active wallpaper, so this method
     * TODO: just assumes that a wallpaper [Bitmap] that's larger than the screen is centered,
     * TODO: which isn't always true.
     *
     * TODO: You can only specifically retrieve the lock screen wallpaper on Nougat and up.
     */
    @SuppressLint("MissingPermission")
    fun updateWallpaperLayerIfNeeded() {
        logUtils.debugLog("updateWallpaperLayerIfNeeded() called $showWallpaperLayerCondition")

        if (showWallpaperLayerCondition) {
            val service = IWallpaperManager.Stub.asInterface(ServiceManager.getService("wallpaper"))

            logUtils.debugLog("Trying to retrieve wallpaper")

            @RequiresApi(Build.VERSION_CODES.N)
            fun getWallpaper(flag: Int): ParcelFileDescriptor? {
                val bundle = Bundle()

                //Even though this hidden method was added in Android Nougat,
                //some devices (SAMSUNG >_>) removed or changed it, so it won't
                //always work. Thus the try-catch.
                return try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        service.getWallpaperWithFeature(
                            packageName,
                            attributionTag,
                            null,
                            flag,
                            bundle,
                            UserHandle.getCallingUserId()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        service.getWallpaper(
                            packageName,
                            null,
                            flag,
                            bundle,
                            UserHandle.getCallingUserId()
                        )
                    }
                } catch (e: Exception) {
                    logUtils.debugLog("Error retrieving wallpaper", e)
                    null
                } catch (e: NoSuchMethodError) {
                    logUtils.debugLog("Error retrieving wallpaper", e)
                    null
                }
            }

            val w = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
                getWallpaper(WallpaperManager.FLAG_LOCK) ?: getWallpaper(WallpaperManager.FLAG_SYSTEM)
            } else null

            try {
                val fastW = run {
                    if (w == null) null
                    else {
                        val fd = w.fileDescriptor
                        if (fd == null) null
                        else {
                            val bmp = BitmapFactory.decodeFileDescriptor(fd)
                            if (bmp == null) null
                            else BitmapDrawable(resources, bmp)
                        }
                    }
                } ?: wallpaper.drawable

                logUtils.debugLog("Retrieved fast wallpaper w: $w, fastW: $fastW")

                fastW?.mutate()?.apply {
                    logUtils.debugLog("Setting wallpaper drawable.")

                    binding.wallpaperBackground.setImageDrawable(this)
                    binding.wallpaperBackground.colorFilter = PorterDuffColorFilter(Color.argb(((prefManager.wallpaperDimAmount / 100f) * 255).toInt(), 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
                    binding.wallpaperBackground.scaleType = ImageView.ScaleType.MATRIX
                    binding.wallpaperBackground.imageMatrix = Matrix().apply {
                        val realSize = realResolution
                        val loc = binding.root.locationOnScreen ?: intArrayOf(0, 0)

                        val dWidth: Int = intrinsicWidth
                        val dHeight: Int = intrinsicHeight

                        val wallpaperAdjustmentX = (dWidth - realSize.x) / 2f
                        val wallpaperAdjustmentY = (dHeight - realSize.y) / 2f

                        setTranslate(
                            (-loc[0].toFloat() - wallpaperAdjustmentX),
                            //TODO: a bunch of skins don't like this
                            (-loc[1].toFloat() - wallpaperAdjustmentY)
                        )
                    }
                } ?: binding.wallpaperBackground.setImageDrawable(null)
            } catch (e: Exception) {
                logUtils.debugLog("Error setting wallpaper", e)
                binding.wallpaperBackground.setImageDrawable(null)
            }
        } else {
            logUtils.debugLog("Removing wallpaper")

            binding.wallpaperBackground.setImageDrawable(null)
        }
    }

    /**
     * This is called by the Accessibility Service on an event,
     * after all other logic has finished (e.g. frame removal/addition
     * calls). Use this method for updating things that are conditional
     * upon the frame's state after an event.
     */
    fun updateAccessibilityPass() {
        if (binding.frame.animationState == WidgetFrameView.AnimationState.STATE_IDLE) {
            if (state.isPendingNotificationStateChange) {
                updateParamsIfNeeded()
                updateState { it.copy(isPendingNotificationStateChange = false) }
            }
        }
    }

    /**
     * Force the display to remain on, or remove that force.
     *
     * @param wm the WindowManager to use.
     * @param on whether to add or remove the force flag.
     */
    fun forceWakelock(wm: WindowManager, on: Boolean) {
        if (on) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }

        binding.frame.updateWindow(wm, params)
    }

    fun updateOverlay() {
        binding.frame.updateWindow(wm, params)
    }

    /**
     * Updates the params needed to blur or not blur the widget frame background.
     */
    private fun updateBlur() {
        val blur = prefManager.blurBackground

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (blur && !showWallpaperLayerCondition) {
                binding.blurBackground.background = binding.frame.blurDrawable?.wrapped
            } else {
                binding.blurBackground.background = null
            }
        } else {
            val f = try {
                params::class.java.getDeclaredField("samsungFlags")
            } catch (e: Exception) {
                null
            }

            if (blur && !showWallpaperLayerCondition) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND

                f?.set(params, f.get(params) as Int or 64)
                params.dimAmount = prefManager.backgroundBlurAmount / 1000f
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()

                f?.set(params, f.get(params) as Int and 64.inv())
                params.dimAmount = 0.0f
            }
        }

        binding.frame.updateWindow(wm, params)
    }

    /**
     * Update the frame's params for its current state (normal
     * or in expanded notification center).
     */
    private fun updateParamsIfNeeded() {
        logUtils.debugLog("Checking if params need to be updated")

        val newX = prefManager.getCorrectFrameX(saveMode)
        val newY = prefManager.getCorrectFrameY(saveMode)
        val newW = dpAsPx(prefManager.getCorrectFrameWidth(saveMode))
        val newH = dpAsPx(prefManager.getCorrectFrameHeight(saveMode))

        var changed = false

        if (params.x != newX) {
            logUtils.debugLog("x changed")

            changed = true
            params.x = newX
        }

        if (params.y != newY) {
            logUtils.debugLog("y changed")

            changed = true
            params.y = newY
        }

        if (params.width != newW) {
            logUtils.debugLog("w changed")

            changed = true
            params.width = newW
        }

        if (params.height != newH) {
            logUtils.debugLog("h changed")

            changed = true
            params.height = newH
        }

        if (changed) {
            logUtils.debugLog("Updating params")

            binding.frame.updateWindow(wm, params)
            mainHandler.post {
                updateWallpaperLayerIfNeeded()
                updateBlur()
                adapter.updateViews()
                gridLayoutManager.scrollToPosition(prefManager.currentPage)
            }
        }
    }

    //Parts based on https://stackoverflow.com/a/26445064/5496177
    inner class SpannedLayoutManager : SpannedGridLayoutManager(
        this@WidgetFrameDelegate,
        RecyclerView.HORIZONTAL,
        prefManager.frameRowCount,
        prefManager.frameColCount
    ), ISnappyLayoutManager {
        override fun canScrollHorizontally(): Boolean {
            return (adapter.currentEditingInterfacePosition == -1 || state.isHoldingItem) && super.canScrollHorizontally()
        }

        override fun getFixScrollPos(velocityX: Int, velocityY: Int): Int {
            if (childCount == 0) return 0

            return if (velocityX > 0) {
                firstVisiblePosition
            } else {
                lastVisiblePosition
            }.also {
                prefManager.currentPage = it
            }.run { if (this == -1) 0 else this }
        }

        override fun getPositionForVelocity(velocityX: Int, velocityY: Int): Int {
            if (childCount == 0) return 0

            return if (velocityX > 0) {
                lastVisiblePosition
            } else {
                firstVisiblePosition
            }.also {
                prefManager.currentPage = it
            }.run { if (this == -1) 0 else this }
        }

        override fun canSnap(): Boolean {
            return adapter.currentEditingInterfacePosition == -1
        }
    }

    data class State(
        val screenOrientation: Int = Surface.ROTATION_0,
        val wasOnKeyguard: Boolean = false,
        val isOnFaceWidgets: Boolean = false,
        val currentAppLayer: Int = 0,
        val isOnScreenOffMemo: Boolean = false,
        val onMainLockscreen: Boolean = false,
        val showingNotificationsPanel: Boolean = false,
        val notificationCount: Int = 0,
        val hideForPresentIds: Boolean = false,
        val hideForNonPresentIds: Boolean = false,
        val currentSysUiLayer: Int = 1,
        val currentSystemLayer: Int = 0,
        val currentAppPackage: String? = null,
        val isOnEdgePanel: Boolean = false,
        val isPreview: Boolean = false,
        val updatedForMove: Boolean = false,
        val isHoldingItem: Boolean = false,
        //This is used to track when the notification shade has
        //been expanded/collapsed or when the "show in NC" setting
        //has been changed. Since the params are different for the
        //widget frame depending on whether it's showing in the NC
        //or not, we need to update them. We could do it directly,
        //but that causes weird shifting and resizing since it happens
        //before the Accessibility Service hides or shows the frame.
        //So instead, we set this flag to true when the params should be
        //updated. The Accessibility Service takes care of calling
        //the update method after it starts a frame removal or addition.
        //The method itself checks whether it can run (i.e. the
        //animation state of the frame is IDLE) and then updates
        //the params.
        val isPendingNotificationStateChange: Boolean = false,
        val notificationsPanelFullyExpanded: Boolean = false,
        val isScreenOn: Boolean = false,
        val isTempHide: Boolean = false,
    )
}