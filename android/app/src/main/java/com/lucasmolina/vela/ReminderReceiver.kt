package com.lucasmolina.vela

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(AppointmentReminders.EXTRA_TITLE).orEmpty()
        val id = intent.getLongExtra(AppointmentReminders.EXTRA_ID, 0L)
        AppointmentReminders.notify(context, id, title)
    }
}
