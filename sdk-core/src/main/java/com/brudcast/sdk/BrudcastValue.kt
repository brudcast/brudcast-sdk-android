package com.brudcast.sdk

import org.json.JSONObject

/**
 * A property value the Brudcast API accepts: a string, a number, a boolean or null.
 */
public sealed class BrudcastValue {
    public data class Text(val value: String) : BrudcastValue()
    public data class Number(val value: Double) : BrudcastValue()
    public data class Bool(val value: Boolean) : BrudcastValue()
    public object Null : BrudcastValue() {
        override fun toString(): String = "Null"
    }

    /**
     * Returns the value as a number when it is a number or a string that parses as one.
     *
     * @return The number, or null.
     */
    public fun numberValue(): Double? = when (this) {
        is Number -> value
        is Text -> value.toDoubleOrNull()
        else -> null
    }

    /**
     * Returns the value in the form `org.json` writes.
     *
     * @return A String, a Long, a Double, a Boolean or `JSONObject.NULL`.
     */
    public fun toJson(): Any = when (this) {
        is Text -> value
        is Number -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 9.007199254740992E15) value.toLong() else value
        is Bool -> value
        Null -> JSONObject.NULL
    }

    public companion object {
        /**
         * Converts a host or JSON value into a property value.
         *
         * @param raw The value.
         * @return The matching value, or null when it is a collection or another type.
         */
        @JvmStatic
        public fun from(raw: Any?): BrudcastValue? = when (raw) {
            null, JSONObject.NULL -> Null
            is BrudcastValue -> raw
            is String -> Text(raw)
            is Boolean -> Bool(raw)
            is kotlin.Number -> Number(raw.toDouble())
            else -> null
        }

        /**
         * Converts a map of host values, dropping and logging values the API cannot carry.
         *
         * @param raw The host's properties.
         * @return The properties.
         */
        @JvmStatic
        public fun properties(raw: Map<String, Any?>?): Map<String, BrudcastValue> {
            if (raw == null) return emptyMap()
            val result = LinkedHashMap<String, BrudcastValue>()
            for ((key, value) in raw) {
                val converted = from(value)
                if (converted == null) {
                    BrudcastLogger.warning("Property \"$key\" is not a string, number, boolean or null; it is dropped")
                } else {
                    result[key] = converted
                }
            }
            return result
        }

        internal fun propertiesFromJson(json: JSONObject?): Map<String, BrudcastValue> {
            if (json == null) return emptyMap()
            val result = LinkedHashMap<String, BrudcastValue>()
            for (key in json.keys()) {
                from(json.opt(key))?.let { result[key] = it }
            }
            return result
        }

        internal fun propertiesToJson(properties: Map<String, BrudcastValue>): JSONObject {
            val json = JSONObject()
            for ((key, value) in properties) {
                json.put(key, value.toJson())
            }
            return json
        }
    }
}
