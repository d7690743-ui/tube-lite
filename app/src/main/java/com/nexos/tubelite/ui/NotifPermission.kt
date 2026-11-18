package com.nexos.tubelite.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/** Permiso de notificaciones (Android 13+): sin él no hay segundo plano nativo. */
object NotifPermission {
    const val CODE = 3002

    fun has(a: Activity): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensure(a: Activity) {
        if (!has(a)) {
            a.requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                CODE
            )
        }
    }
}
