package com.minimalphone.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SimAccount(
    val handle: PhoneAccountHandle,
    val slot: Int,
    val operatorName: String?,
)

object SimSelection {
    private val _accounts = MutableStateFlow<List<SimAccount>>(emptyList())
    val accounts: StateFlow<List<SimAccount>> = _accounts.asStateFlow()

    var selected: SimAccount? = null
        private set

    fun reset(context: Context) {
        load(context)
        val default = defaultAccount(context)
        selected = default ?: _accounts.value.firstOrNull()
    }

    fun ensure(context: Context) {
        load(context)
        val list = _accounts.value
        if (selected == null || list.none { it.handle == selected?.handle }) {
            selected = defaultAccount(context) ?: list.firstOrNull()
        }
    }

    fun cycle(context: Context): Boolean {
        if (_accounts.value.isEmpty()) load(context)
        val list = _accounts.value
        if (list.size < 2) {
            if (selected == null) selected = list.firstOrNull()
            return false
        }
        val current = selected?.let { account ->
            list.indexOfFirst { it.handle == account.handle }.takeIf { it >= 0 }
        } ?: -1
        selected = list[(current + 1) % list.size]
        return true
    }

    fun label(): String? {
        val account = selected ?: return null
        val list = _accounts.value
        val operator = account.operatorName?.takeIf { it.isNotBlank() }
        val sameOperator = operator != null && list.count { it.operatorName == operator } > 1
        return if (operator != null && !sameOperator) {
            "$operator · SIM ${account.slot + 1}"
        } else {
            "SIM ${account.slot + 1}"
        }
    }

    private fun defaultAccount(context: Context): SimAccount? {
        val handles = _accounts.value
        if (handles.isEmpty()) return null
        val telecom = context.getSystemService(TelecomManager::class.java)
        val defaultHandle = runCatching {
            telecom?.getDefaultOutgoingPhoneAccount("tel")
        }.getOrNull()
        return handles.firstOrNull { it.handle == defaultHandle } ?: handles.firstOrNull()
    }

    private fun load(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _accounts.value = emptyList()
            return
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        val allHandles = runCatching { telecom?.callCapablePhoneAccounts.orEmpty() }.getOrDefault(emptyList())
        val simHandles = allHandles.filter { handle ->
            runCatching {
                telecom?.getPhoneAccount(handle)
                    ?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
            }.getOrDefault(false)
        }
        val handles = simHandles.ifEmpty { allHandles }
        val subscriptions = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                .orEmpty()
                .associateBy { it.subscriptionId }
        }.getOrDefault(emptyMap())
        _accounts.value = handles.mapIndexed { index, handle ->
            val subscriptionId = handle.id?.toIntOrNull()
            val info = subscriptionId?.let { subscriptions[it] }
                ?: handle.id?.let { id -> subscriptions.values.firstOrNull { it.iccId == id } }
            SimAccount(
                handle = handle,
                slot = info?.simSlotIndex?.takeIf { it >= 0 } ?: index,
                operatorName = info?.carrierName?.toString()?.takeIf { it.isNotBlank() },
            )
        }
    }
}
