package org.jni_zero

import java.util.Collections

/**
 * Stub required by Chromium / WebRTC jni_zero initialization in modern Discord media engine.
 */
@Suppress("unused")
object JniInit {
    @JvmStatic
    fun init(): Array<Any?> {
        return arrayOf(
            Collections.emptyList<Any>(),
            Collections.emptyMap<Any, Any>(),
            JniInit::class.java.classLoader,
        )
    }
}
