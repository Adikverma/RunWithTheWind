package com.example.runwiththewind.wear.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.runwiththewind.wear.MainApplication
import com.example.runwiththewind.wear.data.RetentionPolicy
import com.example.runwiththewind.wear.data.SportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as MainApplication).repository
    private val sinceMs = System.currentTimeMillis() - RetentionPolicy.daysToMs(RetentionPolicy.ROW_RETENTION_DAYS)

    val items: StateFlow<List<HistoryItemUi>?> = repository
        .observeHistory(SportType.RUN, sinceMs)
        .map { list -> list.map { it.toHistoryItemUi() } }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun onSyncClick(id: String) {
        /* TODO(sync): manual sync is built in a later phase. Intentionally does nothing. */
    }
}
