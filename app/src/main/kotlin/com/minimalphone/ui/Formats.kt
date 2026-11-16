package com.minimalphone.ui

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formats {
    private val util = PhoneNumberUtil.getInstance()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val listDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.US)
    private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.US)

    fun region(context: Context): String {
        val sim = runCatching {
            context.getSystemService(TelephonyManager::class.java).simCountryIso
        }.getOrNull()
        val country = sim?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country
        return country.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "RU"
    }

    fun parse(context: Context, number: String): Phonenumber.PhoneNumber? =
        runCatching { util.parse(number, region(context)) }.getOrNull()

    fun isValid(context: Context, number: String): Boolean = isValid(number, region(context))

    fun isValid(number: String, region: String): Boolean {
        val parsed = runCatching { util.parse(number, region) }.getOrNull() ?: return false
        return runCatching { util.isValidNumber(parsed) }.getOrDefault(false)
    }

    fun format(context: Context, number: String): String = format(number, region(context))

    fun format(number: String, region: String): String {
        val parsed = runCatching { util.parse(number, region) }.getOrNull() ?: return number
        return runCatching {
            val numberRegion = util.getRegionCodeForNumber(parsed)
            val style = if (numberRegion == null || numberRegion.equals(region, ignoreCase = true)) {
                PhoneNumberUtil.PhoneNumberFormat.NATIONAL
            } else {
                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
            }
            util.format(parsed, style)
        }.getOrDefault(number)
    }

    fun canonicalKey(context: Context, number: String): String =
        canonicalKey(number, region(context))

    fun canonicalKey(number: String, region: String): String {
        val parsed = runCatching { util.parse(number, region) }.getOrNull()
        if (parsed != null && runCatching { util.isValidNumber(parsed) }.getOrDefault(false)) {
            return runCatching {
                util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }.getOrDefault(fallbackKey(number))
        }
        return fallbackKey(number)
    }

    private fun fallbackKey(number: String): String =
        PhoneNumberUtils.normalizeNumber(number).ifEmpty { number }

    fun asYouType(context: Context, raw: String): String = asYouType(raw, region(context))

    fun asYouType(raw: String, region: String): String {
        if (raw.isBlank()) return ""
        if (raw.any { it == '*' || it == '#' }) return raw
        return runCatching {
            val formatter = util.getAsYouTypeFormatter(region)
            var formatted = ""
            raw.forEach { formatted = formatter.inputDigit(it) }
            formatted
        }.getOrDefault(raw)
    }

    fun digitsOnly(text: String): String {
        val builder = StringBuilder()
        text.forEachIndexed { index, char ->
            when {
                char.isDigit() -> builder.append(char)
                char == '+' && index == 0 -> builder.append(char)
            }
        }
        return builder.toString()
    }

    fun listTime(millis: Long): String {
        val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val formatter = if (zoned.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) {
            timeFormatter
        } else {
            listDateTimeFormatter
        }
        return formatter.format(zoned)
    }

    fun fullTime(millis: Long): String =
        fullDateTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun duration(seconds: Long): String {
        if (seconds <= 0L) return "—"
        val minutes = seconds / 60
        val rest = seconds % 60
        return "$minutes:${rest.toString().padStart(2, '0')}"
    }

    fun letter(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "#"
        return String(Character.toChars(trimmed.codePointAt(0))).lowercase(Locale.ROOT)
    }
}
