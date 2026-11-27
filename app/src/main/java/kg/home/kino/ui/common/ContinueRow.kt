package kg.home.kino.ui.common

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kg.home.kino.R
import kg.home.kino.data.Progress

/**
 * Card presenter for the "Продолжить просмотр" rows: draws cards with [inner] and adds a
 * long-press of OK on a card, which asks whether to remove that entry from the row.
 */
class ContinueCardPresenter(
    private val inner: Presenter,
    private val onRemove: (Progress) -> Unit,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = inner.onCreateViewHolder(parent)

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        inner.onBindViewHolder(viewHolder, item)
        val p = item as? Progress ?: return
        // A held OK arrives as repeated key-downs (repeatCount > 0 / FLAG_LONG_PRESS). Catch the
        // first repeat, and swallow the rest plus the key-up so the normal click doesn't fire.
        var longPressed = false
        viewHolder.view.setOnKeyListener { v, keyCode, e ->
            if (!isConfirmKey(keyCode)) return@setOnKeyListener false
            when (e.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (e.repeatCount == 0 && !e.isLongPress) {
                        longPressed = false
                        false
                    } else {
                        if (!longPressed) {
                            longPressed = true
                            v.isPressed = false
                            confirmRemove(v.context, p)
                        }
                        true
                    }
                }
                KeyEvent.ACTION_UP -> if (longPressed) {
                    longPressed = false
                    v.isPressed = false
                    true
                } else false
                else -> false
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.setOnKeyListener(null)
        inner.onUnbindViewHolder(viewHolder)
    }

    private fun isConfirmKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun confirmRemove(context: Context, p: Progress) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.continue_remove_title)
            .setMessage(p.name)
            .setPositiveButton(R.string.continue_remove_yes) { _, _ -> onRemove(p) }
            .setNegativeButton(R.string.continue_remove_no, null)
            .show()
    }
}

/** Row header with a quiet hint on the right (see [HintRowHeaderPresenter]). */
class HintHeaderItem(name: String, val hint: String) : HeaderItem(name)

/**
 * Row header that shows [HintHeaderItem.hint] small, faded and right-aligned on the title's line,
 * fading in only while that row is selected. Plain [HeaderItem]s look exactly as before.
 */
class HintRowHeaderPresenter : RowHeaderPresenter() {

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density
        val title = super.onCreateViewHolder(parent).view
        val frame = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        frame.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val hint = TextView(ctx).apply {
            tag = HINT_TAG
            textSize = 14f
            setTextColor(Color.WHITE)
            alpha = 0f
            visibility = View.GONE
        }
        frame.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        ).apply { marginEnd = (48 * dp).toInt() })
        return ViewHolder(frame)
    }

    /** Hint views currently bound, with the row each one belongs to. */
    private val bound = HashMap<TextView, Row>()

    /** The row whose hint is shown (the focused row), or null to hide all hints. */
    var activeRow: Row? = null
        set(value) {
            if (field === value) return
            field = value
            bound.forEach { (hint, row) -> fade(hint, row) }
        }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        super.onBindViewHolder(viewHolder, item)
        val hint = viewHolder.view.findViewWithTag<TextView>(HINT_TAG) ?: return
        val row = item as? Row
        val text = (row?.headerItem as? HintHeaderItem)?.hint
        hint.text = text
        if (row == null || text.isNullOrEmpty()) {
            hint.visibility = View.GONE
            bound.remove(hint)
            return
        }
        hint.visibility = View.VISIBLE
        hint.animate().cancel()
        hint.alpha = if (row === activeRow) HINT_ALPHA else 0f
        bound[hint] = row
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        viewHolder.view.findViewWithTag<TextView>(HINT_TAG)?.let { bound.remove(it) }
        super.onUnbindViewHolder(viewHolder)
    }

    private fun fade(hint: TextView, row: Row) {
        hint.animate().alpha(if (row === activeRow) HINT_ALPHA else 0f).setDuration(150).start()
    }

    private companion object {
        const val HINT_TAG = "row_hint"
        const val HINT_ALPHA = 0.4f
    }
}

/**
 * Shows a row's hint (see [HintRowHeaderPresenter]) only while focus is inside that row - not when
 * another row is selected, focus is up on the tabs, or the tab is hidden. Call from onViewCreated.
 */
fun RowsSupportFragment.showRowHintsWhenFocused(presenter: HintRowHeaderPresenter) {
    var selected: Row? = null
    val update = { presenter.activeRow = if (view?.hasFocus() == true) selected else null }
    setOnItemViewSelectedListener { _, _, _, row ->
        selected = row as? Row
        update()
    }
    val root = requireView()
    val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> update() }
    root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            presenter.activeRow = null
        }
    })
}
