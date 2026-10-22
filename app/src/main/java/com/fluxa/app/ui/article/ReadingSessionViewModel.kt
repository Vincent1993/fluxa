package com.fluxa.app.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.fluxa.app.ui.feedlist.FeedListState
import com.fluxa.app.data.local.ReaderPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReadingSessionViewModel @Inject constructor(val reader: ReaderPreferences) : ViewModel() {
    private var retainedList: Job? = null

    /** Keep the list's WhileSubscribed projection current while its back-stack entry owns
     * a reader session. Otherwise a long read can replay an obsolete unread list on back,
     * consuming the saved anchor before the fresh projection arrives. No frozen order changes.
     */
    fun retainList(state: StateFlow<FeedListState>) {
        if (retainedList == null) retainedList = viewModelScope.launch { state.collect { } }
    }
}
