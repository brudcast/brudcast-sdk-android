package com.brudcast.sample.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brudcast.sdk.Brudcast

/**
 * Records a screen view when the screen appears, the way an app tells Brudcast where the user is
 * so screen-triggered campaigns can show.
 *
 * @param name The screen name campaigns match on.
 * @param properties Extra detail, such as the product on a product page.
 */
@Composable
fun TrackScreen(name: String, properties: Map<String, Any?> = emptyMap()) {
    LaunchedEffect(name) { Brudcast.screen(name, properties) }
}

/** A hairline rule between pieces of content. */
@Composable
fun Rule(strong: Boolean = false) {
    HorizontalDivider(thickness = 1.dp, color = if (strong) MaterialTheme.tokens.borderStrong else MaterialTheme.tokens.border)
}

/**
 * A group of content under a heading, bounded by type and whitespace rather than a box.
 *
 * @param title The heading.
 * @param description A line under the heading.
 * @param content The section's content.
 */
@Composable
fun Section(title: String, description: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (description != null) {
            Text(description, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/**
 * A filled primary button.
 *
 * @param text The label.
 * @param onClick What it does.
 * @param modifier The modifier.
 * @param enabled Whether it can be tapped.
 * @param tag A test tag.
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tag: String? = null) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp).tagged(tag),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * A hairline-bordered button on the card surface.
 *
 * @param text The label.
 * @param onClick What it does.
 * @param modifier The modifier.
 * @param enabled Whether it can be tapped.
 * @param destructive Whether it signs out or deletes, which paints the label red.
 * @param tag A test tag.
 */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    tag: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp).tagged(tag),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.tokens.border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (destructive) MaterialTheme.tokens.destructiveForeground else MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * A text field with its label above it. Hover and focus settle on a neutral border rather than a
 * brand ring.
 *
 * @param label The label.
 * @param value The text.
 * @param onValueChange Called with new text.
 * @param placeholder Hint text shown while empty.
 * @param keyboardType The keyboard to show.
 * @param tag A test tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    tag: String? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.tokens.inputActive,
        unfocusedBorderColor = MaterialTheme.tokens.border,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        cursorColor = MaterialTheme.colorScheme.onSurface,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).tagged(tag),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            singleLine = true,
            interactionSource = interactions,
        ) { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactions,
                placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) } },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactions,
                        colors = colors,
                        shape = MaterialTheme.shapes.medium,
                        focusedBorderThickness = 1.dp,
                        unfocusedBorderThickness = 1.dp,
                    )
                },
            )
        }
    }
}

/**
 * A row of large numerals sitting on a rule, for counts.
 *
 * @param metrics Each metric's label and value.
 */
@Composable
fun MetricLedger(vararg metrics: Pair<String, Int>) {
    Column {
        Rule(strong = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            metrics.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value.toString(), style = MaterialTheme.typography.displaySmall)
                }
            }
        }
        Rule()
    }
}

/**
 * A tappable list row with an icon chip, separated from its neighbours by a rule.
 *
 * @param icon The glyph in the chip.
 * @param title The row's title.
 * @param subtitle A line under the title.
 * @param onClick What tapping it does.
 * @param trailing Content at the end, such as a price.
 */
@Composable
fun ListRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/**
 * A brand-tinted icon chip.
 *
 * @param icon The glyph.
 */
@Composable
fun IconChip(icon: ImageVector) {
    Box(
        Modifier.size(36.dp).background(MaterialTheme.tokens.brandSubtle, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.tokens.brand) }
}

/**
 * A tinted notice: a success, warning or error the reader should see without a dialog.
 *
 * @param text The message.
 * @param background The fill, a pale status token.
 * @param foreground The text colour, its paired status ink.
 */
@Composable
fun Notice(text: String, background: Color, foreground: Color) {
    Text(
        text,
        Modifier.fillMaxWidth().background(background, MaterialTheme.shapes.medium).padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = foreground,
    )
}

/**
 * A label and value on one line, for read-only details.
 *
 * @param label The label.
 * @param value The value.
 */
@Composable
fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}

private fun Modifier.tagged(tag: String?): Modifier = if (tag == null) this else testTag(tag)
