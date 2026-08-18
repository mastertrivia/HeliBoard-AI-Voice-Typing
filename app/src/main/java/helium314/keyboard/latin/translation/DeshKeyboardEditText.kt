// Ported from Desh Keyboard v17.4.9 — com/deshkeyboard/common/ui/KeyboardEditText.java
// (smali: base_smali/smali/com/deshkeyboard/common/ui/KeyboardEditText.smali).
//
// Desh behavior reproduced exactly:
//  - isSuggestionsEnabled() -> false (no autocorrect/suggestion strip inside the box)
//  - onSelectionChanged -> callback to the IME (Desh: bg/g.e0 + mKeyboardSwitcher.p),
//    so the keyboard's shift/caps state follows the cursor inside the box.
//  - setText -> callback to the IME (Desh: gg/c.z(selStart, true, selEnd), the composer
//    flush), so programmatic text replacement never leaves stale composing text.
//  - setUpdateSelectionCallback (Desh: KeyboardEditText$setUpdateSelectionCallback).
//  - onCreateInputConnection is the source of the box's real InputConnection
//    (Desh bg/g.S is obtained exactly this way inside bg/g.p0).
package helium314.keyboard.latin.translation

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/** Selection / text callback contract (Desh: KeyboardEditText$a implemented by bg/g). */
interface DeshKeyboardEditTextCallback {
    /** Desh KeyboardEditText.onSelectionChanged -> bg/g.e0(old,old,new,new). */
    fun onSelectionChanged(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int)

    /** Desh KeyboardEditText.setText -> gg/c.z(selStart, true, selEnd) composer flush. */
    fun onTextSet(selectionStart: Int, selectionEnd: Int)
}

/** The translation panel input field — Desh's com.deshkeyboard.common.ui.KeyboardEditText. */
class DeshKeyboardEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : EditText(context, attrs, defStyleAttr) {

    /** Desh H — the update-selection callback (null until set by the IME). */
    private var selectionCallback: DeshKeyboardEditTextCallback? = null

    /** Desh F/G — the last reported selection start/end. */
    private var lastSelectionStart = 0
    private var lastSelectionEnd = 0

    /** Desh: `public final boolean isSuggestionsEnabled() { return false; }`. */
    override fun isSuggestionsEnabled(): Boolean = false

    /** Desh KeyboardEditText.onSelectionChanged(selStart, selEnd). */
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        val cb = selectionCallback
        if (cb != null) {
            // Desh calls bg/g.e0 unconditionally; e0 itself decides whether the change
            // is meaningful. We mirror that by forwarding the raw before/after values.
            cb.onSelectionChanged(lastSelectionStart, lastSelectionEnd, selStart, selEnd)
        }
        lastSelectionStart = selStart
        lastSelectionEnd = selEnd
    }

    /** Desh KeyboardEditText.setText(CharSequence, BufferType). */
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        selectionCallback?.onTextSet(selectionStart, selectionEnd)
        lastSelectionStart = selectionStart
        lastSelectionEnd = selectionEnd
    }

    /** Desh KeyboardEditText.setUpdateSelectionCallback(KeyboardEditText$a). */
    fun setUpdateSelectionCallback(callback: DeshKeyboardEditTextCallback?) {
        selectionCallback = callback
        lastSelectionStart = selectionStart
        lastSelectionEnd = selectionEnd
    }

    /** The IME builds the box's real InputConnection exactly like Desh bg/g.p0. */
    fun createInputConnection(outAttrs: EditorInfo): InputConnection? =
        onCreateInputConnection(outAttrs)
}
