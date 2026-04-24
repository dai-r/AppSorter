package dev.dair.appsorter.model

data class CategorizedApp(
    val appName: String,
    val packageName: String,
    val category: String,
    val source: String
)
