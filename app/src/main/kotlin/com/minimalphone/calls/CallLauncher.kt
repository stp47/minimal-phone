package com.minimalphone.calls

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager

object CallLauncher {
    const val EXTRA_NUMBER = "com.minimalphone.calls.NUMBER"

    fun place(context: Context, number: String): Result<Unit> {
        SimSelection.ensure(context)
        return runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java)
                ?: error("no telecom")
            val extras = Bundle()
            SimSelection.selected?.let {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it.handle)
            }
            telecom.placeCall(Uri.parse("tel:" + Uri.encode(number)), extras)
        }
    }

    fun openScreen(context: Context, number: String? = null) {
        runCatching {
            val intent = Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (number != null) intent.putExtra(EXTRA_NUMBER, number)
            context.startActivity(intent)
        }
    }
}
