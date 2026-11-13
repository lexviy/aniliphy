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

    /** The title a group goes by: the one that came first (lowest id is close enough without dates). */
    fun lead(entries: List<ListEntry>): ListEntry = entries.minBy { it.media.id }
}
