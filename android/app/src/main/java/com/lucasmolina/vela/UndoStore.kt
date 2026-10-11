package com.lucasmolina.vela

import android.content.Context

object UndoStore {
    private const val PREFS = "vella_undo"
    private const val RECORD = "record"

    fun save(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECORD, json).commit()
    }

    fun load(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECORD, "").orEmpty()
    }
}
