package com.example.seniorshield.test

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/** Storage tests must not execute SeniorShieldApp's monitoring initialization. */
class M3TestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
