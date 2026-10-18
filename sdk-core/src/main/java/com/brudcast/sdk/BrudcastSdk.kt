package com.brudcast.sdk

/**
 * Facts about this build of the SDK.
 */
public object BrudcastSdk {
    public const val VERSION: String = BuildConfig.SDK_VERSION

    public const val PLATFORM: String = "android"

    @Volatile
    private var wrapper: String? = null

    internal val userAgent: String
        get() = listOfNotNull("BrudcastSDK-Android/$VERSION", wrapper).joinToString(" ")

    /**
     * Names the framework SDK that wraps this one, such as the Flutter plugin, so requests carry it
     * in their `User-Agent` for support. Call it before [Brudcast.initialize].
     *
     * @param name The wrapper's name, such as `Flutter`.
     * @param version The wrapper's version.
     */
    @JvmStatic
    public fun setWrapper(name: String, version: String) {
        wrapper = "BrudcastSDK-$name/$version"
    }
}
