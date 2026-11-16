package com.minimalphone

import android.content.Context
import android.telephony.PhoneNumberUtils
import com.minimalphone.ui.Formats

object AppState {
    @Volatile
    var isForeground: Boolean = false

    @Volatile
    var openChatAddress: String? = null

    fun sameAddress(context: Context, first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        if (first == second) return true
        if (PhoneNumberUtils.areSamePhoneNumber(first, second, Formats.region(context))) return true
        val firstNormalized = PhoneNumberUtils.normalizeNumber(first)
        val secondNormalized = PhoneNumberUtils.normalizeNumber(second)
        return firstNormalized.isNotEmpty() && firstNormalized == secondNormalized
    }
}
