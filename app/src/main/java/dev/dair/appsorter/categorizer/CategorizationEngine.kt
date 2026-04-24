package dev.dair.appsorter.categorizer

import dev.dair.appsorter.model.CategorizedApp
import dev.dair.appsorter.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CategorizerBackend(val label: String) {
    OFFLINE_RULES("Offline rules"),
    LOCAL_LLM("Local LLM"),
    CLAUDE_API("Claude API")
}

class CategorizationEngine(
    val backend: CategorizerBackend,
    private val categorizer: AppCategorizer
) {
    suspend fun categorizeInBatches(
        apps: List<InstalledApp>,
        batchSize: Int,
        onBatchComplete: (processed: Int, total: Int, batchIndex: Int) -> Unit
    ): List<CategorizedApp> = withContext(Dispatchers.Default) {
        val batches = apps.chunked(batchSize)
        val results = mutableListOf<CategorizedApp>()
        var processed = 0

        batches.forEachIndexed { index, batch ->
            val categorizedBatch = categorizer.categorize(batch)
            results += categorizedBatch
            processed += categorizedBatch.size
            onBatchComplete(processed, apps.size, index + 1)
        }

        results.sortedWith(compareBy<CategorizedApp> { it.category }.thenBy { it.appName.lowercase() })
    }
}
