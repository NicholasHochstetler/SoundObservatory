/* This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliffracertech.soundaura.SoundAura.pref_key_showActiveTracksFirst
import com.cliffracertech.soundaura.SoundAura.pref_key_trackSort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A [LazyColumn] to display all of the provided [Track]s with instances of [TrackView].
 *
 * @param modifier The [Modifier] that will be used for the TrackList.
 * @param state The [LazyListState] used for the TrackList's scrolling state.
 * @param contentPadding The [PaddingValues] instance that will be used as
 *     the content padding for the TrackList's items.
 * @param tracks The list of Tracks that will be displayed by the TrackList.
 * @param trackViewCallback The instance of [TrackViewCallback] that will
 *     be used for responses to individual TrackView interactions.
 */
@Composable fun TrackList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues,
    tracks: List<Track>,
    trackViewCallback: TrackViewCallback
) = LazyColumn(
    modifier = modifier,
    state = state,
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    items(items = tracks, key = { it.uriString }) {
        TrackView(it, trackViewCallback, Modifier.animateItemPlacement())
    }
}

@HiltViewModel
class TrackListViewModel(
    dataStore: DataStore<Preferences>,
    private val trackDao: TrackDao,
    searchQueryState: SearchQueryState,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    @Inject constructor(
        dataStore: DataStore<Preferences>,
        trackDao: TrackDao,
        searchQueryState: SearchQueryState,
    ) : this(dataStore, trackDao, searchQueryState, null)

    private val scope = coroutineScope ?: viewModelScope
    private val showActiveTracksFirstKey = booleanPreferencesKey(pref_key_showActiveTracksFirst)
    private val showActiveTracksFirst = dataStore.preferenceFlow(showActiveTracksFirstKey, false)
    private val trackSortKey = intPreferencesKey(pref_key_trackSort)
    private val trackSort = dataStore.enumPreferenceFlow<Track.Sort>(trackSortKey)

    var tracks by mutableStateOf<List<Track>>(emptyList())
        private set

    init {
        val searchQueryFlow = snapshotFlow { searchQueryState.query.value }
        combine(trackSort, showActiveTracksFirst, searchQueryFlow, trackDao::getAllTracks)
            .transformLatest { emitAll(it) }
            .onEach { tracks = it }
            .launchIn(scope)
    }

    fun onDeleteTrackDialogConfirm(context: Context, uriString: String) {
        scope.launch {
            val uri = Uri.parse(uriString)
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri, FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {}
            trackDao.delete(uriString)
        }
    }

    fun onTrackAddRemoveButtonClick(uriString: String) {
        scope.launch { trackDao.toggleIsActive(uriString) }
    }

    fun onTrackVolumeChangeRequest(uriString: String, volume: Float) {
        scope.launch { trackDao.setVolume(uriString, volume) }
    }

    fun onTrackRenameDialogConfirm(uriString: String, name: String) {
        scope.launch { trackDao.setName(uriString, name) }
    }
}

/**
 * Compose a [TrackList], using an instance of [TrackListViewModel] to
 * obtain the list of tracks and to respond to item related callbacks.
 *
 * @param modifier The [Modifier] that will be used for the TrackList.
 * @param padding A [PaddingValues] instance whose values will be
 *     as the contentPadding for the TrackList
*  @param state The [LazyListState] used for the TrackList. state
 *     defaults to an instance of LazyListState returned from a
 *     [rememberLazyListState] call, but can be overridden here in
 *     case, e.g., the scrolling position needs to be remembered
 *     even when the StatefulTrackList leaves the composition.
 * @param onVolumeChange The callback that will be invoked when
 *     a TrackView's volume slider is moved.
 */
@Composable fun StatefulTrackList(
    modifier: Modifier = Modifier,
    padding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
    onVolumeChange: (String, Float) -> Unit,
) = Surface(modifier, color = MaterialTheme.colors.background) {
    val viewModel: TrackListViewModel = viewModel()
    val context = LocalContext.current
    val itemCallback = rememberTrackViewCallback(
        onAddRemoveButtonClick = viewModel::onTrackAddRemoveButtonClick,
        onVolumeChange = onVolumeChange,
        onVolumeChangeFinished = viewModel::onTrackVolumeChangeRequest,
        onRenameRequest = viewModel::onTrackRenameDialogConfirm,
        onDeleteRequest = {
            viewModel.onDeleteTrackDialogConfirm(context, it)
        })
    TrackList(
        modifier = modifier,
        tracks = viewModel.tracks,
        state = state,
        contentPadding = padding,
        trackViewCallback = itemCallback)
}