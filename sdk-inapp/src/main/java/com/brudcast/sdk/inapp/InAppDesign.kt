package com.brudcast.sdk.inapp

import org.json.JSONArray
import org.json.JSONObject

/** The highest design `schemaVersion` this SDK can draw. */
public const val BRUDCAST_SUPPORTED_SCHEMA_VERSION: Int = 1

public enum class InAppFormat(public val wire: String) {
    MODAL("modal"), BANNER("banner"), FULLSCREEN("fullscreen"), INBOX_ONLY("inbox_only");

    internal companion object {
        fun from(value: String?): InAppFormat? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class InAppPosition(val wire: String) {
    TOP("top"), CENTER("center"), BOTTOM("bottom");

    companion object {
        fun from(value: String?): InAppPosition? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class InAppTextRole(val wire: String) {
    TITLE("title"), BODY("body"), CAPTION("caption");

    companion object {
        fun from(value: String?): InAppTextRole? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class InAppTextWeight(val wire: String) {
    REGULAR("regular"), MEDIUM("medium"), BOLD("bold");

    companion object {
        fun from(value: String?): InAppTextWeight? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class InAppTextAlign(val wire: String) {
    START("start"), CENTER("center"), END("end"), LEFT("left"), RIGHT("right");

    companion object {
        fun from(value: String?): InAppTextAlign? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class InAppButtonVariant(val wire: String) {
    PRIMARY("primary"), SECONDARY("secondary"), TEXT("text");

    companion object {
        fun from(value: String?): InAppButtonVariant? = entries.firstOrNull { it.wire == value }
    }
}

internal enum class InAppActionType(val wire: String) {
    DEEPLINK("deeplink"), URL("url"), DISMISS("dismiss"), CUSTOM_EVENT("custom_event"), REQUEST_PUSH_PERMISSION("request_push_permission");

    companion object {
        fun from(value: String?): InAppActionType? = entries.firstOrNull { it.wire == value }
    }
}

internal data class InAppAction(val type: InAppActionType, val value: String?, val opensInApp: Boolean) {
    companion object {
        fun parse(raw: Any?): InAppAction? {
            val json = raw as? JSONObject ?: return null
            val type = InAppActionType.from(json.text("type")) ?: return null
            val value = json.text("value")
            if (type in setOf(InAppActionType.DEEPLINK, InAppActionType.URL, InAppActionType.CUSTOM_EVENT) && value == null) return null
            return InAppAction(type, value, type == InAppActionType.URL && json.text("target") != "browser")
        }
    }
}

internal data class InAppTextStyle(
    val size: Double?,
    val weight: InAppTextWeight?,
    val align: InAppTextAlign,
    val color: String?,
    val darkColor: String?,
)

internal data class InAppButtonStyle(
    val variant: InAppButtonVariant,
    val background: String?,
    val textColor: String?,
    val darkBackground: String?,
    val darkTextColor: String?,
)

internal sealed class InAppBlock {
    abstract val id: String

    data class Text(override val id: String, val role: InAppTextRole, val text: String, val style: InAppTextStyle) : InAppBlock()
    data class Image(override val id: String, val url: String, val aspectRatio: Double, val alt: String, val cornerRadius: Double?) : InAppBlock()
    data class Button(override val id: String, val label: String, val action: InAppAction, val style: InAppButtonStyle) : InAppBlock()
    data class Spacer(override val id: String, val height: Double) : InAppBlock()
    data class Divider(override val id: String, val color: String?, val thickness: Double) : InAppBlock()
    data class Columns(override val id: String, val children: List<InAppBlock>) : InAppBlock()

    val imageUrls: List<String>
        get() = when (this) {
            is Image -> listOf(url)
            is Columns -> children.flatMap { it.imageUrls }
            else -> emptyList()
        }
}

internal data class InAppLayout(
    val position: InAppPosition,
    val dismissible: Boolean,
    val overlayColor: String,
    val overlayOpacity: Double,
    val backgroundColor: String?,
    val backgroundImageUrl: String?,
    val darkBackgroundColor: String?,
    val cornerRadius: Double,
    val padding: Double,
    val autoDismissSeconds: Double?,
    val bannerAction: InAppAction?,
)

/** Why a design cannot be drawn. */
internal sealed class InAppDesignError(message: String) : Exception(message) {
    data class UnsupportedVersion(val version: Int) : InAppDesignError("schemaVersion $version is newer than this SDK supports")
    data class UnknownFormat(val format: String) : InAppDesignError("unknown format $format")
    data class UnknownBlock(val type: String) : InAppDesignError("unknown block type $type")
    data class InvalidBlock(val id: String) : InAppDesignError("block $id is missing what it needs")
    object Empty : InAppDesignError("the design has no blocks")
}

internal data class InAppDesign(
    val schemaVersion: Int,
    val format: InAppFormat,
    val layout: InAppLayout,
    val blocks: List<InAppBlock>,
) {
    val imageUrls: List<String> get() = blocks.flatMap { it.imageUrls } + listOfNotNull(layout.backgroundImageUrl)

    val title: String? get() = blocks.firstNotNullOfOrNull { (it as? InAppBlock.Text)?.takeIf { block -> block.role == InAppTextRole.TITLE }?.text }

    val bodyText: String? get() = blocks.firstNotNullOfOrNull { (it as? InAppBlock.Text)?.takeIf { block -> block.role == InAppTextRole.BODY }?.text }

    val firstImageUrl: String? get() = blocks.flatMap { it.imageUrls }.firstOrNull()

    companion object {
        /**
         * Reads a design document, refusing anything this SDK cannot draw faithfully.
         *
         * @param json The `design` object.
         * @return The design.
         * @throws InAppDesignError For a newer schema version, an unknown format or block, or a block
         *   missing what it needs.
         */
        fun parse(json: JSONObject?): InAppDesign {
            json ?: throw InAppDesignError.Empty
            val version = json.optInt("schemaVersion", 0)
            if (version < 1 || version > BRUDCAST_SUPPORTED_SCHEMA_VERSION) throw InAppDesignError.UnsupportedVersion(version)
            val formatRaw = json.text("format").orEmpty()
            val format = InAppFormat.from(formatRaw) ?: throw InAppDesignError.UnknownFormat(formatRaw)
            val layout = parseLayout(json.optJSONObject("layout") ?: JSONObject(), format)
            val raw = json.optJSONArray("blocks") ?: JSONArray()
            val blocks = (0 until raw.length()).map { parseBlock(raw.opt(it), it, nested = false) }
            if (blocks.isEmpty()) throw InAppDesignError.Empty
            return InAppDesign(version, format, layout, blocks)
        }

        private fun parseLayout(json: JSONObject, format: InAppFormat): InAppLayout {
            val overlay = json.optJSONObject("overlay") ?: JSONObject()
            val background = json.optJSONObject("background") ?: JSONObject()
            val dark = json.optJSONObject("darkMode") ?: JSONObject()
            val darkBackground = dark.text("backgroundColor") ?: dark.optJSONObject("background")?.text("color")
            val defaultPosition = if (format == InAppFormat.BANNER) InAppPosition.TOP else InAppPosition.CENTER
            val position = InAppPosition.from(json.text("position")) ?: defaultPosition
            return InAppLayout(
                position = if (format == InAppFormat.BANNER && position == InAppPosition.CENTER) InAppPosition.TOP else position,
                dismissible = if (json.has("dismissible") && !json.isNull("dismissible")) json.optBoolean("dismissible", true) else true,
                overlayColor = overlay.text("color") ?: "#000000",
                overlayOpacity = (overlay.number("opacity") ?: 0.5).coerceIn(0.0, 1.0),
                backgroundColor = background.text("color"),
                backgroundImageUrl = background.text("imageUrl")?.takeIf(::isWebUrl),
                darkBackgroundColor = darkBackground,
                cornerRadius = (json.number("cornerRadius") ?: 16.0).coerceIn(0.0, 48.0),
                padding = (json.number("padding") ?: 24.0).coerceIn(0.0, 64.0),
                autoDismissSeconds = json.number("autoDismissSeconds")?.takeIf { it > 0 }?.coerceAtMost(60.0),
                bannerAction = InAppAction.parse(json.opt("bannerAction")),
            )
        }

        private fun parseBlock(raw: Any?, index: Int, nested: Boolean): InAppBlock {
            val json = raw as? JSONObject ?: throw InAppDesignError.InvalidBlock("block $index")
            val type = json.text("type") ?: throw InAppDesignError.InvalidBlock("block $index")
            val id = json.text("id") ?: "block_$index"
            return when (type) {
                "text" -> {
                    val text = json.text("text") ?: throw InAppDesignError.InvalidBlock(id)
                    val style = json.optJSONObject("style") ?: JSONObject()
                    InAppBlock.Text(
                        id,
                        InAppTextRole.from(json.text("role")) ?: InAppTextRole.BODY,
                        text,
                        InAppTextStyle(
                            size = style.number("size")?.coerceIn(10.0, 64.0),
                            weight = InAppTextWeight.from(style.text("weight")),
                            align = InAppTextAlign.from(style.text("align")) ?: InAppTextAlign.START,
                            color = style.text("color"),
                            darkColor = style.text("darkColor"),
                        ),
                    )
                }
                "image" -> {
                    val url = json.text("url")?.takeIf(::isWebUrl) ?: throw InAppDesignError.InvalidBlock(id)
                    InAppBlock.Image(id, url, (json.number("aspectRatio") ?: 1.6).coerceIn(0.25, 4.0), json.text("alt").orEmpty(), json.number("cornerRadius"))
                }
                "button" -> {
                    val label = json.text("label") ?: throw InAppDesignError.InvalidBlock(id)
                    val action = InAppAction.parse(json.opt("action")) ?: throw InAppDesignError.InvalidBlock(id)
                    val style = json.optJSONObject("style") ?: JSONObject()
                    InAppBlock.Button(
                        id, label, action,
                        InAppButtonStyle(
                            InAppButtonVariant.from(style.text("variant")) ?: InAppButtonVariant.PRIMARY,
                            style.text("background"), style.text("textColor"), style.text("darkBackground"), style.text("darkTextColor"),
                        ),
                    )
                }
                "spacer" -> InAppBlock.Spacer(id, (json.number("height") ?: 16.0).coerceIn(0.0, 120.0))
                "divider" -> InAppBlock.Divider(id, json.text("color"), (json.number("thickness") ?: 1.0).coerceIn(0.5, 8.0))
                "columns" -> {
                    if (nested) throw InAppDesignError.InvalidBlock(id)
                    val raw = json.optJSONArray("children") ?: JSONArray()
                    val children = (0 until minOf(raw.length(), 2)).map { parseBlock(raw.opt(it), it, nested = true) }
                    if (children.isEmpty()) throw InAppDesignError.InvalidBlock(id)
                    InAppBlock.Columns(id, children)
                }
                else -> throw InAppDesignError.UnknownBlock(type)
            }
        }

        private fun isWebUrl(value: String): Boolean =
            value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
    }
}

internal fun JSONObject.text(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.number(key: String): Double? =
    if (!has(key) || isNull(key)) null else (opt(key) as? Number)?.toDouble()
