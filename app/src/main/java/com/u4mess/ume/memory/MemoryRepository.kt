package com.u4mess.ume.memory

import io.objectbox.Box
import io.objectbox.query.QueryBuilder
import io.objectbox.query.QueryCondition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MemoryRepository(private val box: Box<MemoryEntity>) {

    private val _revisions = MutableStateFlow(0)

    /** Bumped after every write so the UI knows when to re-query. */
    val revisions: StateFlow<Int> = _revisions.asStateFlow()

    fun insert(
        source: String,
        packageName: String,
        title: String,
        content: String,
        embedding: FloatArray? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Long {
        val id = box.put(
            MemoryEntity(
                source = source,
                packageName = packageName,
                title = title,
                content = content,
                timestamp = timestamp,
                embedding = embedding,
            ),
        )
        _revisions.update { it + 1 }
        return id
    }

    fun recent(limit: Int = 200): List<MemoryEntity> =
        box.query()
            .orderDesc(MemoryEntity_.timestamp)
            .build()
            .use { it.find(0L, limit.toLong()) }

    fun search(keyword: String = "", source: String? = null, limit: Int = 200): List<MemoryEntity> {
        var condition: QueryCondition<MemoryEntity>? = null
        if (!source.isNullOrBlank()) {
            condition = MemoryEntity_.source.equal(source)
        }
        if (keyword.isNotBlank()) {
            val term = keyword.trim()
            val match = MemoryEntity_.title
                .contains(term, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .or(MemoryEntity_.content.contains(term, QueryBuilder.StringOrder.CASE_INSENSITIVE))
            condition = condition?.and(match) ?: match
        }
        val builder = if (condition == null) box.query() else box.query(condition)
        return builder
            .orderDesc(MemoryEntity_.timestamp)
            .build()
            .use { it.find(0L, limit.toLong()) }
    }

    fun delete(id: Long) {
        box.remove(id)
        _revisions.update { it + 1 }
    }

    fun nearestNeighbors(vector: FloatArray, limit: Int = 10): List<MemoryEntity> {
        require(vector.size == 384) { "Expected a 384-dimension embedding, got ${vector.size}" }
        return box.query(MemoryEntity_.embedding.nearestNeighbors(vector, limit))
            .build()
            .use { query -> query.findWithScores().map { it.get() } }
    }
}
