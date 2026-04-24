package dev.dair.appsorter.categorizer

import dev.dair.appsorter.model.CategorizedApp
import dev.dair.appsorter.model.InstalledApp

interface AppCategorizer {
    suspend fun categorize(batch: List<InstalledApp>): List<CategorizedApp>
}
