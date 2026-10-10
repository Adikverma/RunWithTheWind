package com.example.runwiththewind.wear.data

object RetentionPolicy {
    const val ROW_RETENTION_DAYS = 30     // summary rows shown in history
    const val BLOB_RETENTION_DAYS = 30    // track files (.jsonl.gz). Set to 7 to keep files for only a week.
    fun daysToMs(days: Int) = days * 24L * 60 * 60 * 1000
}
