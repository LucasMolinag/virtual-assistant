package com.lucasmolina.vela

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WebAnswers {
    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "was", "were", "what", "whats", "who", "how", "why", "when", "where",
    )

    private val placeQuestion = Regex(
        """^(?:please\s+)?(?:where(?:'s| is)|what(?:'s| is) the (?:address|phone number) (?:of|for)|find(?: the (?:address|phone number) (?:of|for))?)\s+(?:the\s+|a\s+)?(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun answer(context: Context, question: String): String {
        val asked = question.trim().replace(Regex("""[?.!]+$"""), "")
        if (asked.isEmpty()) return reply("Ask me a question.")
        if (isPlaceQuestion(asked)) {
            val place = placeName(asked)
            if (place.isNotEmpty()) {
                val found = JSONObject(PlaceLookup.lookup(context, place))
                if (!found.has("error")) {
                    val address = found.optString("address")
                    val phone = found.optString("phone")
                    val sentence = buildString {
                        append(place.replaceFirstChar { it.uppercase() })
                        if (address.isNotBlank()) append(" is at ").append(address).append('.')
                        if (phone.isNotBlank()) append(" Phone ").append(phone).append('.')
                    }
                    return reply(sentence)
                }
            }
            return reply("I couldn't find that near you.")
        }
        val focused = focus(asked)
        val instant = wikipediaSearch(focused).ifBlank { duckSnippet(focused) }.ifBlank { duckDuckGo(focused) }
        return reply(instant.ifBlank { "I couldn't find an answer for that." })
    }

    private fun focus(question: String): String {
        return question
            .replace(
                Regex(
                    """^(?:please\s+)?(?:can you\s+|could you\s+)?(?:tell me\s+)?(?:what(?:'s|s| is| are)|who(?:'s| is| was| were)|where(?:'s| is)|when(?:'s| is| was| did)|why(?:'s| is| are| did)|how(?:'s| do| does| did| is| are)|google|search(?:\s+for)?|look up)\s+""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("""^(?:the|a|an)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun isPlaceQuestion(asked: String): Boolean {
        if (Regex("""^(?:who|why|how|when)\b""", RegexOption.IGNORE_CASE).containsMatchIn(asked)) return false
        if (Regex("""\b(?:nearest|closest|nearby|near me)\b""", RegexOption.IGNORE_CASE).containsMatchIn(asked)) return true
        if (Regex("""\b(?:capital|president|weather|meaning|define|population|history)\b""", RegexOption.IGNORE_CASE).containsMatchIn(asked)
            && !Regex("""\b(?:address|phone)\b""", RegexOption.IGNORE_CASE).containsMatchIn(asked)
        ) {
            return false
        }
        return placeQuestion.containsMatchIn(asked)
    }

    private fun placeName(asked: String): String {
        val captured = placeQuestion.find(asked)?.groupValues?.get(1)?.trim().orEmpty()
        return captured.ifBlank { asked }
            .removeSuffix(" appointment")
            .replace(Regex("""^(?:the\s+)?(?:nearest|closest|nearby)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:near me|nearby|around here|close to me)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun reply(text: String): String {
        return JSONObject().put("reply", text).put("spoken", text).toString()
    }

    private fun duckDuckGo(question: String): String {
        val encoded = URLEncoder.encode(question, Charsets.UTF_8.name())
        val json = getJson("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1") ?: return ""
        return json.optString("Answer").ifBlank {
            json.optString("AbstractText").ifBlank { json.optString("Definition") }
        }.trim()
    }

    private fun wikipediaSearch(question: String): String {
        if (question.length < 3) return ""
        val encoded = URLEncoder.encode(question, Charsets.UTF_8.name())
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=8&srprop=snippet&format=json"
        val json = getJson(url) ?: return ""
        val rows = json.optJSONObject("query")?.optJSONArray("search") ?: return ""
        val tokens = tokens(question)
        var bestScore = 0
        var best = ""
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val title = decode(row.optString("title"))
            val snippet = decode(row.optString("snippet"))
            val score = score(title, snippet, question, tokens)
            if (score > bestScore) {
                bestScore = score
                best = snippet
            }
        }
        if (bestScore < 12) return ""
        return shorten(best)
    }

    private fun tokens(query: String): List<String> {
        return query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP_WORDS }
    }

    private fun score(title: String, snippet: String, query: String, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0
        val hayTitle = title.lowercase()
        val hay = "$hayTitle ${snippet.lowercase()}"
        var score = 0
        var hits = 0
        for (token in tokens) {
            if (hayTitle.contains(token)) {
                score += 12
                hits += 1
            } else if (hay.contains(token)) {
                score += 6
                hits += 1
            }
        }
        if (hits == 0) return 0
        if (tokens.size >= 2 && hits < tokens.size) score -= 18
        if (hay.contains(query.lowercase())) score += 24
        return score
    }

    private fun decode(value: String): String {
        return value
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
    }

    private fun duckSnippet(question: String): String {
        val encoded = URLEncoder.encode(question, Charsets.UTF_8.name())
        val html = getText("https://lite.duckduckgo.com/lite/?q=$encoded") ?: return ""
        val match = Regex("(?is)<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>").find(html) ?: return ""
        val text = match.groupValues[1]
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        return shorten(text)
    }

    private fun shorten(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        val sentences = Regex("[^.!?]+[.!?]").findAll(clean).map { it.value.trim() }.take(2).joinToString(" ")
        val brief = sentences.ifBlank { clean }
        return if (brief.length > 420) brief.take(420).trimEnd() + "." else brief
    }

    private fun getJson(url: String): JSONObject? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Vella/1.0 (personal calendar assistant)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: Exception) {
            null
        }
    }

    private fun getText(url: String): String? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection)
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Vella/1.0 (personal calendar assistant)")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
