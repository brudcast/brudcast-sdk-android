package com.brudcast.sdk

/**
 * Facts about this build of the SDK.
 */
public object BrudcastSdk {
    public const val VERSION: String = BuildConfig.SDK_VERSION

    public const val PLATFORM: String = "android"

    internal val userAgent: String = "BrudcastSDK-Android/$VERSION"
}
