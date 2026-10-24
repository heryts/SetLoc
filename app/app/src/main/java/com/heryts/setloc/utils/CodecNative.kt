package com.heryts.setloc.utils

import com.heryts.setloc.route.RouteNative

internal object CodecNative {
    @JvmStatic
    private external fun nativeTag(logical: String): String?

    @JvmStatic
    private external fun nativeEnc(plain: String): String?

    @JvmStatic
    private external fun nativeDec(hex: String): String?

    private fun call(block: () -> String?): String? {
        if (!RouteNative.ready) return null
        return try {
            block()
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }

    fun tag(logical: String): String? = call { nativeTag(logical) }

    fun enc(plain: String): String? = call { nativeEnc(plain) }

    fun dec(hex: String): String? = call { nativeDec(hex) }
}
