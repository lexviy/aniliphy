package com.aniliphy.anilist

/**
 * Franchises: seasons (and, if wanted, side stories) of one show grouped together, from AniList's relations.
 */
object Franchises {
    /** Sequels and prequels only: the seasons of a show. */
    val SEASONS = setOf("PREQUEL", "SEQUEL")

    /** Seasons plus what hangs off them: side stories and the titles they belong to. */
    val WITH_SIDE_STORIES = SEASONS + setOf("PARENT", "SIDE_STORY")

    fun relations(sideStories: Boolean) = if (sideStories) WITH_SIDE_STORIES else SEASONS

    /** What the franchise page walks: everything told in the same story, recaps included (to mark them skippable). */
    val PAGE = WITH_SIDE_STORIES + setOf("SUMMARY", "COMPILATION")

    /** At most this many titles are fetched when walking a franchise (long-running ones stop here). */
    const val WALK_LIMIT = 60

    /**
     * Groups [entries] into franchises, keeping the order they came in (a group sits where its first entry was).
     * Titles not on the list still join two that are: a first and third season group through the second.
     */
    fun group(entries: List<ListEntry>, links: Map<Int, List<MediaLink>>, relations: Set<String>): List<List<ListEntry>> {
        val parent = HashMap<Int, Int>()
        fun find(x: Int): Int {
            var root = x
            while (true) {
                val up = parent[root] ?: return root.also { parent[x] = it }
                if (up == root) return root.also { parent[x] = it }
                root = up
            }
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        entries.forEach { entry ->
            val type = entry.media.type
            links[entry.media.id].orEmpty()
                .filter { it.relation in relations && (type == null || it.type == null || it.type == type) }
                .forEach { union(entry.media.id, it.id) }
        }
        val groups = LinkedHashMap<Int, MutableList<ListEntry>>()
        entries.forEach { groups.getOrPut(find(it.media.id)) { mutableListOf() }.add(it) }
        return groups.values.toList()
    }

    /**
     * Walks out from [startIds] through [relations], [fetch]ing up to 50 titles at a time, and returns the franchise in
     * watch order. Stays within the start titles' type (no manga in an anime franchise) and stops at [WALK_LIMIT].
     */
    suspend fun walk(startIds: List<Int>, relations: Set<String>, fetch: suspend (List<Int>) -> List<FranchiseNode>): List<FranchiseNode> {
        val found = LinkedHashMap<Int, FranchiseNode>()
        var queue = startIds.distinct()
        var type: String? = null
        while (queue.isNotEmpty() && found.size < WALK_LIMIT) {
            val batch = queue.take(minOf(50, WALK_LIMIT - found.size))
            val nodes = fetch(batch)
            if (type == null) type = nodes.firstOrNull { it.id in startIds }?.type ?: nodes.firstOrNull()?.type
            nodes.filter { type == null || it.type == type }.forEach { found[it.id] = it }
            queue = nodes
                .filter { it.id in found }
                .flatMap { it.links }
                .filter { it.relation in relations && (it.type == null || it.type == type) && it.id !in found && it.id !in batch }
                .map { it.id }
                .distinct()
        }
        return order(found.values.toList())
    }

    /** Watch order: by when each started, unknown dates last. */
    fun order(nodes: List<FranchiseNode>): List<FranchiseNode> = nodes.sortedWith(
        compareBy<FranchiseNode>(
            { it.startDate?.year ?: Int.MAX_VALUE },
            { it.startDate?.month ?: 13 },
            { it.startDate?.day ?: 32 },
            { it.id },
        ),
    )

    /** The franchise's status as one: watching if anything is, completed only when everything is. */
    fun combinedStatus(entries: List<ListEntry>): String? {
        val statuses = entries.mapNotNull { it.status }.toSet()
        return when {
            statuses.isEmpty() -> null
            statuses.size == 1 -> statuses.single()
            ListStatus.CURRENT in statuses || ListStatus.REPEATING in statuses -> ListStatus.CURRENT
            ListStatus.PAUSED in statuses -> ListStatus.PAUSED
            statuses == setOf(ListStatus.COMPLETED, ListStatus.PLANNING) -> ListStatus.CURRENT
            ListStatus.PLANNING in statuses -> ListStatus.CURRENT
            else -> ListStatus.COMPLETED
        }
    }

    /**
     * What each title of a franchise is to its story: the main line (seasons and the films that carry on from them),
     * recaps, side stories, alternative versions, and films or specials made only for the anime when the story comes
     * from a manga or a novel. [nodes] come in watch order.
     *
     * Canon goes by the source AniList gives each title (adapted from the manga or novel, or made for the anime), and
     * only means something when the franchise adapts one. [corrections] are the ones you set by hand, by title id:
     * true for canon, false for anime-only.
     */
    fun describe(nodes: List<FranchiseNode>, corrections: Map<Int, Boolean> = emptyMap()): List<FranchisePart> {
        val ids = nodes.map { it.id }.toSet()
        // How the other titles of the franchise point at each one.
        val incoming = HashMap<Int, MutableSet<String>>()
        nodes.forEach { from ->
            from.links.filter { it.id in ids && it.id != from.id }.forEach { incoming.getOrPut(it.id) { mutableSetOf() } += it.relation }
        }
        val story = storySource(nodes)
        return nodes.map { node ->
            val into = incoming[node.id].orEmpty()
            val out = node.links.filter { it.id in ids }.map { it.relation }.toSet()
            val inLine = "PREQUEL" in into || "SEQUEL" in into || "PREQUEL" in out || "SEQUEL" in out
            val adapted = story != null && story != "ORIGINAL"
            val side = !inLine && ("SIDE_STORY" in into || "PARENT" in out)
            val placed = when {
                "SUMMARY" in into || "COMPILATION" in into -> FranchiseRole.Recap
                "ALTERNATIVE" in into && !inLine -> FranchiseRole.Alternative
                side -> FranchiseRole.SideStory
                else -> FranchiseRole.Main
            }
            val corrected = corrections[node.id]
            val canon = corrected ?: when {
                placed == FranchiseRole.Recap || placed == FranchiseRole.Alternative -> null
                !adapted || node.source == null -> null
                else -> node.source != "ORIGINAL"
            }
            val role = if (canon == false) FranchiseRole.AnimeOnly else placed
            FranchisePart(node, role, canon, corrected != null)
        }
    }

    /**
     * The story's own order: AniList's prequel and sequel links put each title where it happens (Kizumonogatari before
     * Bakemonogatari, though it came out later). Titles they leave out (side stories, recaps, specials) go right after
     * the last title they hang off. Where the links say nothing, or go round in a circle, release order decides.
     * [nodes] come in release order.
     */
    fun storyOrder(nodes: List<FranchiseNode>): List<FranchiseNode> {
        val byId = nodes.associateBy { it.id }
        val released = nodes.withIndex().associate { it.value.id to it.index }
        val before = HashMap<Int, MutableSet<Int>>()
        nodes.forEach { node ->
            node.links.filter { it.id in byId && it.id != node.id }.forEach { link ->
                when (link.relation) {
                    "PREQUEL" -> before.getOrPut(node.id) { mutableSetOf() } += link.id
                    "SEQUEL" -> before.getOrPut(link.id) { mutableSetOf() } += node.id
                }
            }
        }
        val chained = before.keys + before.values.flatten()
        // The line: each title once everything before it is placed, the earliest released first; a circle is broken
        // at its earliest released title.
        val waiting = nodes.filter { it.id in chained }.associate { it.id to before[it.id].orEmpty().toMutableSet() }.toMutableMap()
        val order = mutableListOf<Int>()
        while (waiting.isNotEmpty()) {
            val ready = waiting.filterValues { it.isEmpty() }.keys
            val pick = ready.ifEmpty { waiting.keys }.minBy { released.getValue(it) }
            order += pick
            waiting.remove(pick)
            waiting.values.forEach { it.remove(pick) }
        }
        // The rest, in release order, after the last placed title linked to them (either way round).
        val lastAfter = HashMap<Int, Int>()
        nodes.filter { it.id !in chained }.forEach { extra ->
            val linked = extra.links.map { it.id }.toSet() + nodes.filter { n -> n.links.any { it.id == extra.id } }.map { it.id }
            val anchor = order.lastOrNull { it in linked }
            if (anchor == null) {
                order += extra.id
            } else {
                order.add(order.indexOf(lastAfter[anchor] ?: anchor) + 1, extra.id)
                lastAfter[anchor] = extra.id
            }
        }
        return order.map(byId::getValue)
    }

    /** Titles told in episodes, the ones that make up a show's seasons. */
    val EPISODIC = setOf("TV", "TV_SHORT", "ONA")

    /** Where the franchise's story comes from (MANGA, LIGHT_NOVEL, ORIGINAL...), going by its series. */
    fun storySource(nodes: List<FranchiseNode>): String? {
        val series = nodes.filter { it.format in EPISODIC }.ifEmpty { nodes }
        return series.mapNotNull { it.source }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /** Titles related to the franchise but outside it (spin-offs, other versions), counted by how they relate. */
    fun alsoRelated(nodes: List<FranchiseNode>): Map<String, Int> {
        val ids = nodes.map { it.id }.toSet()
        val type = nodes.firstOrNull()?.type
        return nodes.flatMap { node -> node.links.filter { it.id !in ids && (type == null || it.type == null || it.type == type) } }
            .filter { it.relation in setOf("SPIN_OFF", "ALTERNATIVE", "SIDE_STORY", "SUMMARY", "COMPILATION", "OTHER") }
            .distinctBy { it.id }
            .groupingBy { it.relation }.eachCount()
    }

    /** The title a group goes by: the one that came first (lowest id is close enough without dates). */
    fun lead(entries: List<ListEntry>): ListEntry = entries.minBy { it.media.id }
}

/** What a title is to its franchise's story. */
enum class FranchiseRole(val tag: String, val skippable: Boolean, val note: String?) {
    Main("", false, null),
    SideStory("SIDE STORY", false, "Set aside from the main story"),
    Recap("RECAP", true, "Retells episodes"),
    Alternative("OTHER VERSION", true, "The same story told again"),
    AnimeOnly("ANIME-ONLY", true, "Not in the original story"),
}

/**
 * A title in its franchise: what it is to the story, and whether it's canon (null where that means nothing: an
 * anime-original franchise, recaps, other versions, or no source known). [corrected] when you set canon by hand.
 */
data class FranchisePart(val node: FranchiseNode, val role: FranchiseRole, val canon: Boolean? = null, val corrected: Boolean = false) {
    /** Part of the story to follow: the main line, and side stories (which are canon). */
    val inStory: Boolean get() = !role.skippable
}
