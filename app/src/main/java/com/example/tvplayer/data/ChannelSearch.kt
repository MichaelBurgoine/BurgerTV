package com.example.tvplayer.data

import java.text.Normalizer

/**
 * Forgiving channel search, built once per channel list and fast enough to run on every keystroke.
 * Matches the channel name and its alternative names (old names, short forms) from the iptv-org database.
 * Ranking: exact channel number, name starts with query, starts with query ignoring spaces ("bbcone"),
 * every typed word starts a word in the name ("news sky"), query anywhere, initials ("fn" -> Fox News).
 */
class ChannelSearch(channels: List<IndexedValue<Channel>>) {

    private class Name(val norm: String, val words: List<String>, val compact: String, val initials: String)
    private class Entry(val index: Int, val channel: Channel, val names: List<Name>)

    private val entries = channels.map { (i, c) ->
        Entry(i, c, (listOf(c.name) + c.altNames).distinct().map { toName(it) })
    }

    private fun toName(s: String): Name {
        val norm = normalize(s)
        val words = norm.split(' ').filter { it.isNotEmpty() }
        return Name(norm, words, words.joinToString(""), words.joinToString("") { it.take(1) })
    }

    fun search(query: String): List<IndexedValue<Channel>> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qWords = q.split(' ')
        val qCompact = qWords.joinToString("")
        val qNumber = qCompact.toIntOrNull()

        return entries.asSequence()
            .mapNotNull { e ->
                val score = if (qNumber != null && e.channel.number == qNumber) 0
                else e.names.mapIndexedNotNull { i, n ->
                    // Main name ranks slightly above an alternative name with the same match.
                    score(n, q, qWords, qCompact)?.let { it * 2 + if (i == 0) 0 else 1 }
                }.minOrNull()
                score?.let { it to e }
            }
            .sortedWith(compareBy({ it.first }, { it.second.channel.name.length }, { it.second.index }))
            .take(LIMIT)
            .map { (_, e) -> IndexedValue(e.index, e.channel) }
            .toList()
    }

    private fun score(n: Name, q: String, qWords: List<String>, qCompact: String): Int? = when {
        n.norm.startsWith(q) -> 1
        n.compact.startsWith(qCompact) -> 2
        qWords.all { w -> n.words.any { it.startsWith(w) } } -> 3
        n.compact.contains(qCompact) -> 4
        qCompact.length >= 2 && n.initials.startsWith(qCompact) -> 5
        else -> null
    }

    companion object {
        const val LIMIT = 60
        private val MARKS = Regex("\\p{Mn}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase()
                .replace("&", " and ")
                .replace(NON_ALNUM, " ")
                .trim()
    }
}
