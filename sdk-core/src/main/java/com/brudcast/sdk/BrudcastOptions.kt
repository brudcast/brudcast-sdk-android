package com.brudcast.sdk

import android.app.Activity
import org.json.JSONObject

/**
 * The Brudcast environment the SDK talks to.
 */
public sealed class BrudcastEnvironment {
    public abstract val baseUrl: String

    public object Production : BrudcastEnvironment() {
        override val baseUrl: String = "https://in-app-delivery-service.brudcast.com/sdk/v1"
    }

    public object Staging : BrudcastEnvironment() {
        override val baseUrl: String = "https://in-app-delivery-service.stg.brudcast.com/sdk/v1"
    }

    public object Uat : BrudcastEnvironment() {
        override val baseUrl: String = "https://in-app-delivery-service.uat.brudcast.com/sdk/v1"
    }

    public object Development : BrudcastEnvironment() {
        override val baseUrl: String = "https://in-app-delivery-service.dev.brudcast.com/sdk/v1"
    }

    /**
     * The service on the development machine. The emulator reaches the host at `10.0.2.2`.
     */
    public data class Local(val host: String = "10.0.2.2", val port: Int = 8191) : BrudcastEnvironment() {
        override val baseUrl: String get() = "http://$host:$port/sdk/v1"
    }

    public data class Custom(override val baseUrl: String) : BrudcastEnvironment()
}

public enum class BrudcastLogLevel {
    VERBOSE, DEBUG, INFO, WARNING, ERROR, NONE
}

public enum class BrudcastAppearance {
    SYSTEM, LIGHT, DARK
}

/**
 * Optional traits sent with `identify`. Core uses them to fill a new contact; they never overwrite
 * values a contact already has.
 */
public class BrudcastTraits(
    public val email: String? = null,
    public val phone: String? = null,
    public val firstName: String? = null,
    public val lastName: String? = null,
    public val language: String? = null,
    public val timezone: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is BrudcastTraits &&
            email == other.email &&
            phone == other.phone &&
            firstName == other.firstName &&
            lastName == other.lastName &&
            language == other.language &&
            timezone == other.timezone

    override fun hashCode(): Int = listOf<Any?>(email, phone, firstName, lastName, language, timezone).hashCode()

    override fun toString(): String = "BrudcastTraits(email=$email, phone=$phone, firstName=$firstName, lastName=$lastName, language=$language, timezone=$timezone)"

    internal fun toJson(): JSONObject {
        val json = JSONObject()
        listOf(
            "email" to email,
            "phone" to phone,
            "firstName" to firstName,
            "lastName" to lastName,
            "language" to language,
            "timezone" to timezone,
        ).forEach { (key, value) ->
            if (!value.isNullOrBlank()) json.put(key, value.trim())
        }
        return json
    }

    internal companion object {
        fun fromJson(json: JSONObject?): BrudcastTraits? {
            if (json == null) return null
            fun field(key: String): String? = if (json.isNull(key)) null else json.optString(key).ifEmpty { null }
            return BrudcastTraits(field("email"), field("phone"), field("firstName"), field("lastName"), field("language"), field("timezone"))
        }
    }
}

/**
 * Configuration passed to [Brudcast.initialize].
 *
 * @property environment The environment to talk to.
 * @property logLevel The lowest level that is logged.
 * @property appearance Light, dark, or following the device, for in-app messages.
 * @property modules The feature modules to run, such as `InAppModule` and `PushModule`.
 * @property accentColor The default ARGB colour of primary buttons. The theme's primary colour is
 *   used when null.
 * @property presentationFilter Decides which activities may show in-app messages, such as to keep
 *   them off a login screen.
 */
public class BrudcastOptions(
    public val environment: BrudcastEnvironment = BrudcastEnvironment.Production,
    public val logLevel: BrudcastLogLevel = BrudcastLogLevel.WARNING,
    public val appearance: BrudcastAppearance = BrudcastAppearance.SYSTEM,
    public val modules: List<BrudcastModule> = emptyList(),
    public val accentColor: Int? = null,
    public val presentationFilter: ((Activity) -> Boolean)? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is BrudcastOptions &&
            environment == other.environment &&
            logLevel == other.logLevel &&
            appearance == other.appearance &&
            modules == other.modules &&
            accentColor == other.accentColor &&
            presentationFilter == other.presentationFilter

    override fun hashCode(): Int = listOf<Any?>(environment, logLevel, appearance, modules, accentColor, presentationFilter).hashCode()

    override fun toString(): String = "BrudcastOptions(environment=$environment, logLevel=$logLevel, appearance=$appearance, modules=$modules, accentColor=$accentColor, presentationFilter=$presentationFilter)"
}
