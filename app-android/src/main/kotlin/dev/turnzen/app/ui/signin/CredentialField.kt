package dev.turnzen.app.ui.signin

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewStructure
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.turnzen.app.R
import dev.turnzen.app.ui.theme.colors

enum class CredentialKind { USERNAME, PASSWORD }

/**
 * A username or password field that tells the device's password manager which website the
 * credential belongs to, so a DGS password the user saved from the browser is offered here
 * too, instead of being looked up under Turnzen's own package name.
 *
 * A classic [EditText] rather than a Compose text field, because only a View can report a web
 * domain: Compose describes its fields to autofill itself and has no way to add one. Password
 * managers read the domain from the field (or at most its direct parent), so it has to sit on
 * the field. Drawn to look like the Compose fields around it: no box, an underline that
 * thickens and turns accent on focus.
 */
@Composable
fun CredentialField(
    value: String,
    onChange: (String) -> Unit,
    kind: CredentialKind,
    webDomain: String?,
    onDone: (() -> Unit)? = null,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnDone by rememberUpdatedState(onDone)
    var focused by remember { mutableStateOf(false) }
    val ink = colors.ink.toArgb()
    val accent = colors.accent
    val line = colors.line
    val textSizeSp = MaterialTheme.typography.titleMedium.fontSize.value
    val padding = with(LocalDensity.current) { 16.dp.roundToPx() }
    AndroidView(
        factory = { context ->
            WebDomainEditText(context).apply {
                background = null
                setPadding(padding, padding, padding, padding)
                inputType =
                    when (kind) {
                        CredentialKind.USERNAME -> {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }

                        CredentialKind.PASSWORD -> {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                    }
                // After inputType: a password input type swaps in a monospace typeface.
                typeface = context.resources.getFont(R.font.ibm_plex_sans_regular)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                imeOptions =
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    if (kind == CredentialKind.PASSWORD) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                setAutofillHints(
                    if (kind == CredentialKind.PASSWORD) View.AUTOFILL_HINT_PASSWORD else View.AUTOFILL_HINT_USERNAME,
                )
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                setText(value)
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) = Unit

                        override fun afterTextChanged(s: Editable?) = currentOnChange(s?.toString().orEmpty())
                    },
                )
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        currentOnDone?.invoke()
                        true
                    } else {
                        false
                    }
                }
                setOnFocusChangeListener { _, hasFocus -> focused = hasFocus }
            }
        },
        update = { view ->
            view.webDomain = webDomain
            if (view.text.toString() != value) view.setText(value)
            view.setTextColor(ink)
            view.tint(accent.toArgb())
        },
        modifier =
            Modifier.fillMaxWidth().drawBehind {
                val stroke = (if (focused) 2.dp else 1.dp).toPx()
                val y = size.height - stroke / 2
                drawLine(if (focused) accent else line, Offset(0f, y), Offset(size.width, y), stroke)
            },
    )
}

private class WebDomainEditText(
    context: Context,
) : EditText(context) {
    var webDomain: String? = null

    override fun onProvideAutofillStructure(
        structure: ViewStructure,
        flags: Int,
    ) {
        super.onProvideAutofillStructure(structure, flags)
        // With the scheme, or the framework records the domain without one.
        webDomain?.let { structure.setWebDomain("https://$it") }
    }

    /** Cursor and selection handles in the accent; before Android 10 they keep the platform colour. */
    fun tint(color: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        highlightColor = (color and 0x00FFFFFF) or 0x40000000
        textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(color) }
        textSelectHandle?.mutate()?.let { setTextSelectHandle(it.apply { setTint(color) }) }
        textSelectHandleLeft?.mutate()?.let { setTextSelectHandleLeft(it.apply { setTint(color) }) }
        textSelectHandleRight?.mutate()?.let { setTextSelectHandleRight(it.apply { setTint(color) }) }
    }
}
