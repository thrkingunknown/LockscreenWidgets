package tk.zwander.widgetdrawer.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.arasthel.spannedgridlayoutmanager.SpannedGridLayoutManager
import tk.zwander.common.util.appWidgetManager
import tk.zwander.lockscreenwidgets.R
import tk.zwander.common.activities.DismissOrUnlockActivity
import tk.zwander.common.data.WidgetData
import tk.zwander.common.data.WidgetType
import tk.zwander.lockscreenwidgets.databinding.DrawerLayoutBinding
import tk.zwander.common.host.WidgetHostCompat
import tk.zwander.common.host.widgetHostCompat
import tk.zwander.common.util.Event
import tk.zwander.common.util.EventObserver
import tk.zwander.common.util.HandlerRegistry
import tk.zwander.common.util.PrefManager
import tk.zwander.common.util.createTouchHelperCallback
import tk.zwander.common.util.dpAsPx
import tk.zwander.common.util.eventManager
import tk.zwander.common.util.handler
import tk.zwander.common.util.prefManager
import tk.zwander.common.util.screenSize
import tk.zwander.common.util.shortcutIdManager
import tk.zwander.common.util.statusBarHeight
import tk.zwander.lockscreenwidgets.util.*
import tk.zwander.widgetdrawer.adapters.DrawerAdapter

class Drawer : FrameLayout, EventObserver, WidgetHostCompat.OnClickCallback {
    companion object {
        const val ANIM_DURATION = 200L
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    val params: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            val displaySize = context.screenSize
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = displaySize.x
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.RGBA_8888
            gravity = Gravity.TOP
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    private val wm by lazy { context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val host by lazy { context.widgetHostCompat }
    private val adapter by lazy {
        DrawerAdapter(context.appWidgetManager, host) { widget, _ ->
            removeWidget(widget)
        }
    }

    private val gridLayoutManager = SpannedLayoutManager(context)
    private val touchHelperCallback = createTouchHelperCallback(
        adapter,
        widgetMoved = { moved ->
            if (moved) {
                updateState { it.copy(updatedForMove = true) }
                context.prefManager.drawerWidgets = LinkedHashSet(adapter.widgets)
                adapter.currentEditingInterfacePosition = -1
            }
        },
        onItemSelected = { selected ->
            updateState { it.copy(isHoldingItem = selected) }
            binding.widgetGrid.selectedItem = selected
        },
        frameLocked = {
            context.prefManager.lockWidgetDrawer
        }
    )
    private val itemTouchHelper = ItemTouchHelper(touchHelperCallback)

    private val preferenceHandler = HandlerRegistry {
        handler(PrefManager.KEY_DRAWER_WIDGETS) {
            if (!state.updatedForMove) {
                //Only run the update if it wasn't generated by a reorder event
                adapter.updateWidgets(context.prefManager.drawerWidgets.toList())
            } else {
                updateState { it.copy(updatedForMove = false) }
            }
        }
        handler(PrefManager.KEY_DRAWER_BACKGROUND_COLOR) {
            setBackgroundColor(context.prefManager.drawerBackgroundColor)
        }
        handler(PrefManager.KEY_DRAWER_COL_COUNT) {
            updateSpanCount()
        }
        handler(PrefManager.KEY_DRAWER_WIDGET_CORNER_RADIUS) {
            if (isAttachedToWindow) {
                adapter.updateViews()
            }
        }
        handler(PrefManager.KEY_LOCK_WIDGET_DRAWER) {
            adapter.currentEditingInterfacePosition = -1
        }
        handler(PrefManager.KEY_DRAWER_SIDE_PADDING) {
            updateSidePadding()
        }
    }

    @Suppress("DEPRECATION")
    private val globalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    hideDrawer()
                }
            }
        }
    }

    private val binding by lazy { DrawerLayoutBinding.bind(this) }

    var state: State = State()
        private set

    override fun onFinishInflate() {
        super.onFinishInflate()

        if (!isInEditMode) {
            binding.addWidget.setOnClickListener { pickWidget() }
            binding.closeDrawer.setOnClickListener { hideDrawer() }
            binding.widgetGrid.layoutManager = gridLayoutManager
            gridLayoutManager.spanSizeLookup = adapter.spanSizeLookup
            itemTouchHelper.attachToRecyclerView(binding.widgetGrid)
            binding.widgetGrid.nestedScrollingListener = {
                itemTouchHelper.attachToRecyclerView(
                    if (it) {
                        null
                    } else {
                        binding.widgetGrid
                    }
                )
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        host.addOnClickCallback(this)
        host.startListening()
        Handler(Looper.getMainLooper()).postDelayed({
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }, 50)

        setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)

        handler?.postDelayed({
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.interpolator = DecelerateInterpolator()
            anim.duration = ANIM_DURATION
            anim.addUpdateListener {
                alpha = it.animatedValue.toString().toFloat()
            }
            anim.doOnEnd {
                context.eventManager.sendEvent(Event.DrawerShown)
            }
            anim.start()
        }, 10)

        setBackgroundColor(context.prefManager.drawerBackgroundColor)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        host.removeOnClickCallback(this)

        try {
            host.stopListening()
        } catch (e: NullPointerException) {
            //AppWidgetServiceImpl$ProviderId NPE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
            hideDrawer()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onEvent(event: Event) {
        when (event) {
            is Event.RemoveWidgetConfirmed -> {
                if (event.remove && context.prefManager.drawerWidgets.contains(event.item)) {
                    context.prefManager.drawerWidgets = context.prefManager.drawerWidgets.apply {
                        remove(event.item)
                        when (event.item?.safeType) {
                            WidgetType.WIDGET -> host.deleteAppWidgetId(event.item.id)
                            WidgetType.SHORTCUT -> context.shortcutIdManager.removeShortcutId(event.item.id)
                            else -> {}
                        }
                    }
                }

                if (event.remove) {
                    adapter.currentEditingInterfacePosition = -1
                    adapter.updateWidgets(context.prefManager.drawerWidgets.toList())
                }
            }
            else -> {}
        }
    }

    override fun onWidgetClick(trigger: Boolean) {
        if (trigger && context.prefManager.requestUnlockDrawer) {
            DismissOrUnlockActivity.launch(context)
            context.eventManager.sendEvent(Event.CloseDrawer)
        } else {
            context.eventManager.sendEvent(Event.DrawerWidgetClick)
        }
    }

    fun onCreate() {
        context.registerReceiver(globalReceiver, IntentFilter().apply {
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        })

        preferenceHandler.register(context)
        binding.widgetGrid.adapter = adapter
        binding.widgetGrid.setHasFixedSize(true)
        updateSpanCount()
        adapter.updateWidgets(context.prefManager.drawerWidgets.toList())
        context.eventManager.addObserver(this)
        binding.removeWidgetConfirmation.root.updateLayoutParams<ViewGroup.LayoutParams> {
            height = (context.screenSize.y / 2f).toInt()
        }
        binding.removeWidgetConfirmation.confirmDeleteText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        context.dpAsPx(16).apply {
            binding.removeWidgetConfirmation.root.setContentPadding(this, this, this, this)
        }
        updateSidePadding()
    }

    fun onDestroy() {
        hideDrawer(false)
        context.prefManager.drawerWidgets = LinkedHashSet(adapter.widgets)

        context.unregisterReceiver(globalReceiver)
        preferenceHandler.unregister(context)
        context.eventManager.removeObserver(this)
    }

    fun showDrawer(wm: WindowManager = this.wm) {
        try {
            wm.addView(this, params)
        } catch (_: Exception) {}
    }

    fun updateDrawer(wm: WindowManager = this.wm) {
        try {
            wm.updateViewLayout(this, params)
        } catch (_: Exception) {}
    }

    fun hideDrawer(callListener: Boolean = true) {
        updateState { it.copy(handlingDrawerClick = false) }
        adapter.currentEditingInterfacePosition = -1

        val anim = ValueAnimator.ofFloat(1f, 0f)
        anim.interpolator = AccelerateInterpolator()
        anim.duration = ANIM_DURATION
        anim.addUpdateListener {
            alpha = it.animatedValue.toString().toFloat()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                handler?.postDelayed({
                    try {
                        wm.removeView(this@Drawer)
                        if (callListener) context.eventManager.sendEvent(Event.DrawerHidden)
                    } catch (_: Exception) {
                    }
                }, 10)
            }
        })
        anim.start()
    }

    fun updateState(transform: (State) -> State) {
        state = transform(state)
    }

    private fun updateSpanCount() {
        gridLayoutManager.columnCount = context.prefManager.drawerColCount
        gridLayoutManager.customHeight = context.resources.getDimensionPixelSize(R.dimen.drawer_row_height)
    }

    private fun pickWidget() {
        hideDrawer()
        context.eventManager.sendEvent(Event.LaunchAddDrawerWidget(true))
    }

    private fun removeWidget(info: WidgetData) {
        binding.removeWidgetConfirmation.root.show(info)
    }

    private fun updateSidePadding() {
        val padding = context.dpAsPx(context.prefManager.drawerSidePadding)

        binding.widgetGrid.updatePaddingRelative(
            start = padding,
            end = padding
        )
    }

    private inner class SpannedLayoutManager(context: Context) : SpannedGridLayoutManager(
        context,
        RecyclerView.VERTICAL,
        1,
        context.prefManager.drawerColCount
    ) {
        override fun canScrollVertically(): Boolean {
            return (adapter.currentEditingInterfacePosition == -1 || state.isHoldingItem) && super.canScrollVertically()
        }
    }

    data class State(
        val isHoldingItem: Boolean = false,
        val updatedForMove: Boolean = false,
        val handlingDrawerClick: Boolean = false
    )
}