package dev.turnzen.app.ui.signin

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewStructure
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 *
 * A password field ends in the usual Android eye button: one tap shows the password, another
 * hides it again. Only the masking changes (the view's transformation method), not the input
 * type, so the field stays a password field for autofill and the keyboard.
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
    var revealed by remember { mutableStateOf(false) }
    val ink = colors.ink.toArgb()
    val accent = colors.accent
    val line = colors.line
    val textSizeSp = MaterialTheme.typography.titleMedium.fontSize.value
    val padding = with(LocalDensity.current) { 16.dp.roundToPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth().drawBehind {
                val stroke = (if (focused) 2.dp else 1.dp).toPx()
                val y = size.height - stroke / 2
                drawLine(if (focused) accent else line, Offset(0f, y), Offset(size.width, y), stroke)
            },
    ) {
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
                        if (kind ==
                            CredentialKind.PASSWORD
                        ) {
                            View.AUTOFILL_HINT_PASSWORD
                        } else {
                            View.AUTOFILL_HINT_USERNAME
                        },
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
                if (kind == CredentialKind.PASSWORD) view.reveal(revealed)
            },
            modifier = Modifier.weight(1f),
        )
        if (kind == CredentialKind.PASSWORD) {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    painter = painterResource(if (revealed) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                    contentDescription =
                        stringResource(if (revealed) R.string.signin_password_hide else R.string.signin_password_show),
                    tint = colors.ink2,
                )
            }
        }
    }
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

    /** Shows the password as typed, or masks it; the cursor and selection stay where they were. */
    fun reveal(shown: Boolean) {
        val method = if (shown) null else PasswordTransformationMethod.getInstance()
        if (transformationMethod === method) return
        val start = selectionStart
        val end = selectionEnd
        transformationMethod = method
        if (start >= 0 && end >= 0) setSelection(start, end)
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
