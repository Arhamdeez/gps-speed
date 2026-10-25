package com.watchspeed.live

internal const val FRESH_FIX_NANOS = 8_000_000_000L
internal const val USEFUL_SPEED_MPS = 1.2f

/** Both sources while the phone is here and sharing location. Otherwise the watch alone. */
internal fun locationMode(phoneConnected: Boolean, phoneLocationOn: Boolean): LocationMode {
    return if (phoneConnected && phoneLocationOn) LocationMode.BOTH else LocationMode.WATCH
}

internal data class SourceOffer(
    val ageNanos: Long,
    val speedMps: Float?,
)

/**
 * Watch and phone can both move the number. A real speed from either source wins over
 * a still reading from the other. When both are moving, the source already on screen stays,
 * so the digits do not flicker.
 */
internal fun preferredSource(
    mode: LocationMode,
    holding: FixSource?,
    watch: SourceOffer?,
    phone: SourceOffer?,
): FixSource? {
    if (mode != LocationMode.BOTH) return watch?.let { FixSource.WATCH }
    val watchUseful = watch.isUseful()
    val phoneUseful = phone.isUseful()
    return when {
        watchUseful && phoneUseful -> holding ?: fresher(watch, phone)
        phoneUseful -> FixSource.PHONE
        watch != null && watch.ageNanos <= FRESH_FIX_NANOS -> FixSource.WATCH
        phone != null && phone.ageNanos <= FRESH_FIX_NANOS -> FixSource.PHONE
        watch != null -> FixSource.WATCH
        phone != null -> FixSource.PHONE
        else -> null
    }
}

private fun SourceOffer?.isUseful(): Boolean {
    val offer = this ?: return false
    val speed = offer.speedMps ?: return false
    return speed >= USEFUL_SPEED_MPS && offer.ageNanos <= FRESH_FIX_NANOS
}

private fun fresher(watch: SourceOffer?, phone: SourceOffer?): FixSource {
    val phoneAge = phone?.ageNanos ?: Long.MAX_VALUE
    val watchAge = watch?.ageNanos ?: Long.MAX_VALUE
    return if (phoneAge < watchAge) FixSource.PHONE else FixSource.WATCH
}
