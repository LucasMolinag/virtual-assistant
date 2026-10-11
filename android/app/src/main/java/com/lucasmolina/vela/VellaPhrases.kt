package com.lucasmolina.vela

object VellaPhrases {
    private val greetings = arrayOf("Yes?", "I'm listening.", "What's up?")

    fun greeting(): String = greetings.random()
}
