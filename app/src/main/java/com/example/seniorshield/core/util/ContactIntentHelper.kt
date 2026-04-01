package com.example.seniorshield.core.util

import android.content.Intent
import android.net.Uri

object ContactIntentHelper {
    fun dialIntent(phoneNumber: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))

    fun smsIntent(phoneNumber: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber"))
}
