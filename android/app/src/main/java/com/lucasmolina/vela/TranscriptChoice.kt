package com.lucasmolina.vela

object TranscriptChoice {
    private val choice = Regex(
        """(?i)\b(?:[1-9]|one|won|two|to|too|tu|three|tree|four|for|fore|five|first|second|third|fourth|fifth)\b""",
    )

    fun best(lines: List<String>): String {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val shortChoice = cleaned
            .filter { line -> choice.containsMatchIn(line) && line.split(Regex("\\s+")).size <= 4 }
            .minByOrNull { it.length }
        if (shortChoice != null) return shortChoice
        return cleaned.firstOrNull { it.startsWith("add", ignoreCase = true) }
            ?: cleaned.firstOrNull { it.startsWith("cancel", ignoreCase = true) }
            ?: cleaned.firstOrNull { !it.startsWith("at ", ignoreCase = true) }
            ?: cleaned.firstOrNull().orEmpty()
    }
}
