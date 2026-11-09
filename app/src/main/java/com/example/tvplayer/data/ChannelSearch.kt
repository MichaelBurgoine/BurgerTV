package com.example.tvplayer.data

import java.text.Normalizer

/**
 * Forgiving channel search, built once per channel list and fast enough to run on every keystroke.
 * Ranking: exact channel number, name starts with query, name starts with query ignoring spaces
 * ("bbcone"), every typed word starts a word in the name ("sky news", "news sky"),
 * query appears anywhere, initials ("fn" -> Fox News).
 */
class ChannelSearch(channels: List<IndexedValue<Channel>>) {

    private class Entry(
        val index: Int,
        val channel: Channel,
        val norm: String,
        val words: List<String>,
        val compact: String,
        val initials: String,
    )

    private val entries = channels.map { (i, c) ->
        val norm = normalize(c.name)
        val words = norm.split(' ').filter { it.isNotEmpty() }
        Entry(i, c, norm, words, words.joinToString(""), words.joinToString("") { it.take(1) })
    }

    fun search(query: String): List<IndexedValue<Channel>> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qWords = q.split(' ')
        val qCompact = qWords.joinToString("")
        val qNumber = qCompact.toIntOrNull()

        return entries.asSequence()
            .mapNotNull { e ->
                val score = when {
                    qNumber != null && e.channel.number == qNumber -> 0
                    e.norm.startsWith(q) -> 1
                    e.compact.startsWith(qCompact) -> 2
                    qWords.all { w -> e.words.any { it.startsWith(w) } } -> 3
                    e.compact.contains(qCompact) -> 4
                    qCompact.length >= 2 && e.initials.startsWith(qCompact) -> 5
                    else -> null
                }
                score?.let { it to e }
            }
            .sortedWith(compareBy({ it.first }, { it.second.channel.name.length }, { it.second.index }))
            .take(LIMIT)
            .map { (_, e) -> IndexedValue(e.index, e.channel) }
            .toList()
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
