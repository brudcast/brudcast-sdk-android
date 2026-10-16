package com.brudcast.sdk.inapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class InAppTheme(val accent: Color, val isDark: Boolean)

@Composable
internal fun InAppBlockList(
    blocks: List<InAppBlock>,
    theme: InAppTheme,
    onButton: (String?, InAppAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(spacing(blocks[index - 1], block).dp))
            InAppBlockView(block, theme, onButton)
        }
    }
}

internal fun spacing(previous: InAppBlock, block: InAppBlock): Int = when {
    previous is InAppBlock.Text && previous.role == InAppTextRole.TITLE && block is InAppBlock.Text && block.role == InAppTextRole.BODY -> 8
    previous is InAppBlock.Button && block is InAppBlock.Button -> 8
    else -> 12
}

@Composable
internal fun InAppBlockView(block: InAppBlock, theme: InAppTheme, onButton: (String?, InAppAction) -> Unit) {
    when (block) {
        is InAppBlock.Text -> InAppText(block, theme)
        is InAppBlock.Image -> InAppImage(block.url, block.alt, (block.cornerRadius ?: 8.0).toFloat(), Modifier.fillMaxWidth().aspectRatio(block.aspectRatio.toFloat()))
        is InAppBlock.Button -> InAppButton(block, theme) { onButton(block.id, block.action) }
        is InAppBlock.Spacer -> Spacer(Modifier.height(block.height.dp))
        is InAppBlock.Divider -> HorizontalDivider(
            thickness = block.thickness.dp,
            color = InAppHexColor.parse(block.color) ?: MaterialTheme.colorScheme.outlineVariant,
        )
        is InAppBlock.Columns -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 280.dp || block.children.size < 2) {
                InAppBlockList(block.children, theme, onButton)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    block.children.forEach { child ->
                        Box(Modifier.weight(1f)) { InAppBlockView(child, theme, onButton) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InAppText(block: InAppBlock.Text, theme: InAppTheme, maxLines: Int = Int.MAX_VALUE) {
    val typography = MaterialTheme.typography
    val base: TextStyle = when (block.role) {
        InAppTextRole.TITLE -> typography.headlineSmall
        InAppTextRole.BODY -> typography.bodyLarge
        InAppTextRole.CAPTION -> typography.bodySmall
    }
    val weight = when (block.style.weight ?: if (block.role == InAppTextRole.TITLE) InAppTextWeight.BOLD else InAppTextWeight.REGULAR) {
        InAppTextWeight.REGULAR -> FontWeight.Normal
        InAppTextWeight.MEDIUM -> FontWeight.Medium
        InAppTextWeight.BOLD -> FontWeight.Bold
    }
    val defaultColor = if (block.role == InAppTextRole.CAPTION) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val align = when (block.style.align) {
        InAppTextAlign.START, InAppTextAlign.LEFT -> TextAlign.Start
        InAppTextAlign.CENTER -> TextAlign.Center
        InAppTextAlign.END, InAppTextAlign.RIGHT -> TextAlign.End
    }
    Text(
        text = block.text,
        style = base.copy(fontWeight = weight, fontSize = block.style.size?.sp ?: base.fontSize),
        color = InAppHexColor.resolve(block.style.color, block.style.darkColor, theme.isDark) ?: defaultColor,
        textAlign = align,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (block.role == InAppTextRole.TITLE) heading() },
    )
}

@Composable
internal fun InAppImage(url: String, alt: String, cornerRadius: Float, modifier: Modifier) {
    val bitmap = InAppImageLoader.image(url)
    val shape = RoundedCornerShape(cornerRadius.dp)
    val described = modifier
        .clip(shape)
        .semantics {
            if (alt.isEmpty()) invisibleToUser() else contentDescription = alt
        }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = alt.ifEmpty { null }, contentScale = ContentScale.Crop, modifier = described)
    } else {
        Box(described.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
internal fun InAppButton(block: InAppBlock.Button, theme: InAppTheme, onClick: () -> Unit) {
    val style = block.style
    val fill = InAppHexColor.resolve(style.background, style.darkBackground, theme.isDark) ?: theme.accent
    val custom = InAppHexColor.resolve(style.textColor, style.darkTextColor, theme.isDark)
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val shape = RoundedCornerShape(24.dp)
    val label: @Composable () -> Unit = {
        Text(block.label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    when (style.variant) {
        InAppButtonVariant.PRIMARY -> Button(
            onClick = onClick, modifier = modifier, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = custom ?: InAppHexColor.contrasting(fill)),
        ) { label() }
        InAppButtonVariant.SECONDARY -> OutlinedButton(
            onClick = onClick, modifier = modifier, shape = shape,
            border = BorderStroke(1.dp, custom ?: fill),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = custom ?: fill),
        ) { label() }
        InAppButtonVariant.TEXT -> TextButton(
            onClick = onClick, modifier = modifier, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = custom ?: fill),
        ) { label() }
    }
}

@Composable
internal fun InAppCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(44.dp).semantics { contentDescription = "Close" }) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.semantics { invisibleToUser() })
        }
    }
}

@Composable
internal fun InAppBackground(layout: InAppLayout, theme: InAppTheme, default: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val color = if (theme.isDark && layout.darkBackgroundColor == null) {
        default
    } else {
        InAppHexColor.resolve(layout.backgroundColor, layout.darkBackgroundColor, theme.isDark) ?: default
    }
    Box(modifier.background(color)) {
        val image = layout.backgroundImageUrl?.let(InAppImageLoader::image)
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)))
        }
        content()
    }
}
