/**
 * Copyright (c) 2024 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.ui.screen

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.amethyst.model.Account
import com.vitorpamplona.amethyst.model.LocalCache
import com.vitorpamplona.amethyst.service.checkNotInMainThread
import com.vitorpamplona.amethyst.ui.dal.FeedFilter
import com.vitorpamplona.amethyst.ui.dal.HiddenWordsFeedFilter
import com.vitorpamplona.ammolite.relays.BundledUpdate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NostrHiddenWordsFeedViewModel(val account: Account) :
    StringFeedViewModel(
        HiddenWordsFeedFilter(account),
    ) {
    class Factory(val account: Account) : ViewModelProvider.Factory {
        override fun <NostrHiddenWordsFeedViewModel : ViewModel> create(modelClass: Class<NostrHiddenWordsFeedViewModel>): NostrHiddenWordsFeedViewModel {
            return NostrHiddenWordsFeedViewModel(account) as NostrHiddenWordsFeedViewModel
        }
    }
}

@Stable
open class StringFeedViewModel(val dataSource: FeedFilter<String>) :
    ViewModel(), InvalidatableViewModel {
    private val _feedContent = MutableStateFlow<StringFeedState>(StringFeedState.Loading)
    val feedContent = _feedContent.asStateFlow()

    private fun refresh() {
        viewModelScope.launch(Dispatchers.Default) { refreshSuspended() }
    }

    private fun refreshSuspended() {
        checkNotInMainThread()

        val notes = dataSource.loadTop().toImmutableList()

        val oldNotesState = _feedContent.value
        if (oldNotesState is StringFeedState.Loaded) {
            // Using size as a proxy for has changed.
            if (!equalImmutableLists(notes, oldNotesState.feed.value)) {
                updateFeed(notes)
            }
        } else {
            updateFeed(notes)
        }
    }

    private fun updateFeed(notes: ImmutableList<String>) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentState = _feedContent.value
            if (notes.isEmpty()) {
                _feedContent.update { StringFeedState.Empty }
            } else if (currentState is StringFeedState.Loaded) {
                // updates the current list
                currentState.feed.value = notes
            } else {
                _feedContent.update { StringFeedState.Loaded(mutableStateOf(notes)) }
            }
        }
    }

    private val bundler = BundledUpdate(250, Dispatchers.IO)

    override fun invalidateData(ignoreIfDoing: Boolean) {
        bundler.invalidate(ignoreIfDoing) {
            // adds the time to perform the refresh into this delay
            // holding off new updates in case of heavy refresh routines.
            refreshSuspended()
        }
    }

    var collectorJob: Job? = null

    init {
        Log.d("Init", this.javaClass.simpleName)
        collectorJob =
            viewModelScope.launch(Dispatchers.IO) {
                checkNotInMainThread()

                LocalCache.live.newEventBundles.collect { newNotes ->
                    checkNotInMainThread()

                    invalidateData()
                }
            }
    }

    override fun onCleared() {
        Log.d("Init", "OnCleared: ${this.javaClass.simpleName}")
        bundler.cancel()
        collectorJob?.cancel()
        super.onCleared()
    }
}
