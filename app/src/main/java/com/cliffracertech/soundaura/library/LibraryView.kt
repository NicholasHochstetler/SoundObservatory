/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.library

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.collectAsState
import com.cliffracertech.soundaura.dialog.NamingState
import com.cliffracertech.soundaura.dialog.RenameDialog
import com.cliffracertech.soundaura.dialog.ValidatedNamingState
import com.cliffracertech.soundaura.enumPreferenceFlow
import com.cliffracertech.soundaura.model.MessageHandler
import com.cliffracertech.soundaura.model.PlaybackState
import com.cliffracertech.soundaura.model.SearchQueryState
import com.cliffracertech.soundaura.model.StringResource
import com.cliffracertech.soundaura.model.UriPermissionHandler
import com.cliffracertech.soundaura.model.database.PlaylistDao
import com.cliffracertech.soundaura.model.database.PlaylistNameValidator
import com.cliffracertech.soundaura.preferenceFlow
import com.cliffracertech.soundaura.settings.PrefKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

private typealias PlaylistSort = com.cliffracertech.soundaura.model.database.Playlist.Sort

sealed class PlaylistDialog(
    val target: Playlist,
    val onDismissRequest: () -> Unit,
) {
    /** The rename dialog for a playlist */
    class Rename(
        target: Playlist,
        validator: PlaylistNameValidator,
        coroutineScope: CoroutineScope,
        onDismissRequest: () -> Unit,
        private val onNameValidated: suspend (String) -> Unit,
    ): PlaylistDialog(target, onDismissRequest),
       NamingState by ValidatedNamingState(validator, coroutineScope, onNameValidated)

    /** The 'playlist options' dialog for a playlist. */
    class PlaylistOptions(
        target: Playlist,
        val playlistShuffleEnabled: Boolean,
        val playlistTracks: ImmutableList<Uri>,
        onDismissRequest: () -> Unit,
        val onConfirmClick: (
                shuffleEnabled: Boolean,
                newTrackList: List<Uri>,
            ) -> Unit,
    ): PlaylistDialog(target, onDismissRequest)

    /** The remove dialog for a playlist */
    class Remove(
        target: Playlist,
        onDismissRequest: () -> Unit,
        val onConfirmClick: () -> Unit,
    ): PlaylistDialog(target, onDismissRequest)
}

/**
 * A [LazyColumn] to display all of the provided [Playlist]s with instances of [PlaylistView].
 *
 * @param modifier The [Modifier] that will be used for the TrackList.
 * @param state The [LazyListState] used for the TrackList's scrolling state.
 * @param contentPadding The [PaddingValues] instance that will be used as
 *     the content padding for the TrackList's items.
 * @param libraryContents The [ImmutableList] of [Playlist]s that will be
 *     displayed by the LibraryView. If the list is empty, an empty list
 *     message will be displayed instead. A null value is interpreted as
 *     a loading state.
 * @param playlistViewCallback The instance of [PlaylistViewCallback] that will
 *     be used for responses to individual TrackView interactions.
 */
@Composable fun LibraryView(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues,
    libraryContents: ImmutableList<Playlist>?,
    shownDialog: PlaylistDialog?,
    playlistViewCallback: PlaylistViewCallback
) {
    Crossfade(libraryContents?.isEmpty()) {
        when(it) {
            null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(50.dp))
            } true -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.empty_track_list_message),
                     modifier = Modifier.width(300.dp),
                     textAlign = TextAlign.Justify)
            } else -> LazyColumn(
                modifier, state, contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val items = libraryContents ?: emptyList()
                items(items, key = Playlist::name::get) { playlist ->
                    PlaylistView(playlist, playlistViewCallback,
                                 Modifier.animateItemPlacement())
                }
            }
        }
    }
    when (shownDialog) {
        null -> {}
        is PlaylistDialog.Rename ->
            RenameDialog(
                title = stringResource(R.string.default_rename_dialog_title),
                state = shownDialog,
                onDismissRequest = shownDialog.onDismissRequest)
        is PlaylistDialog.PlaylistOptions ->
            PlaylistOptionsDialog(
                playlist = shownDialog.target,
                shuffleEnabled = shownDialog.playlistShuffleEnabled,
                tracks = shownDialog.playlistTracks,
                onDismissRequest = shownDialog.onDismissRequest,
                onConfirmClick = shownDialog.onConfirmClick)
        is PlaylistDialog.Remove ->
            ConfirmRemoveDialog(
                itemName = shownDialog.target.name,
                onDismissRequest = shownDialog.onDismissRequest,
                onConfirmClick = shownDialog.onConfirmClick)
    }
}

@HiltViewModel class LibraryViewModel(
    dataStore: DataStore<Preferences>,
    private val permissionHandler: UriPermissionHandler,
    private val playlistDao: PlaylistDao,
    private val messageHandler: MessageHandler,
    private val playbackState: PlaybackState,
    searchQueryState: SearchQueryState,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    @Inject constructor(
        dataStore: DataStore<Preferences>,
        permissionHandler: UriPermissionHandler,
        playlistDao: PlaylistDao,
        messageHandler: MessageHandler,
        playbackState: PlaybackState,
        searchQueryState: SearchQueryState,
    ) : this(dataStore, permissionHandler, playlistDao, messageHandler,
             playbackState, searchQueryState, null)

    private val scope = coroutineScope ?: viewModelScope
    private val showActiveTracksFirstKey = booleanPreferencesKey(PrefKeys.showActivePlaylistsFirst)
    private val showActiveTracksFirst = dataStore.preferenceFlow(showActiveTracksFirstKey, false)
    private val playlistSortKey = intPreferencesKey(PrefKeys.playlistSort)
    private val playlistSort = dataStore.enumPreferenceFlow<PlaylistSort>(playlistSortKey)

    private val searchQueryFlow = snapshotFlow { searchQueryState.query.value }
    val playlists by combine(playlistSort, showActiveTracksFirst, searchQueryFlow, playlistDao::getAllPlaylists)
        .transformLatest { emitAll(it) }
        .map(List<Playlist>::toImmutableList)
        .collectAsState(null, scope)

    var shownDialog by mutableStateOf<PlaylistDialog?>(null)

    fun onPlaylistAddRemoveButtonClick(playlist: Playlist) {
        scope.launch { playlistDao.toggleIsActive(playlist.name) }
    }

    fun onPlaylistVolumeChange(playlist: Playlist, volume: Float) =
        playbackState.setPlaylistVolume(playlist.name, volume)

    fun onPlaylistVolumeChangeFinished(playlist: Playlist, volume: Float) {
        scope.launch { playlistDao.setVolume(playlist.name, volume) }
    }

    fun onPlaylistRenameClick(playlist: Playlist) {
        shownDialog = PlaylistDialog.Rename(
            target = playlist,
            validator = PlaylistNameValidator(playlistDao, scope, playlist.name),
            coroutineScope = scope,
            onDismissRequest = { shownDialog = null },
            onNameValidated = { validatedName ->
                playlistDao.rename(playlist.name, validatedName)
                shownDialog = null
            })
    }

    fun onPlaylistOptionsClick(playlist: Playlist) {
        scope.launch {
            val shuffleEnabled = playlistDao.getPlaylistShuffle(playlist.name)
            val tracks = playlistDao.getPlaylistTracks(playlist.name)
            shownDialog = PlaylistDialog.PlaylistOptions(
                target = playlist,
                playlistShuffleEnabled = shuffleEnabled,
                playlistTracks = tracks.toImmutableList(),
                onDismissRequest = { shownDialog = null },
                onConfirmClick = { newShuffle, newTracks ->
                    shownDialog = null
                    savePlaylistTracksAndShuffle(playlist.name, newShuffle, tracks, newTracks)
                })
        }
    }

    fun onPlaylistRemoveClick(playlist: Playlist) {
        shownDialog = PlaylistDialog.Remove(
            target = playlist,
            onDismissRequest = { shownDialog = null },
            onConfirmClick = {
                scope.launch { playlistDao.delete(playlist.name) }
                shownDialog = null
            })
    }

    private fun savePlaylistTracksAndShuffle(
        playlistName: String,
        shuffle: Boolean,
        originalTracks: List<Uri>,
        newTracks: List<Uri>
    ) {
        scope.launch {
            val validatedTrackList =
                // If no new tracks were added, we can simply save the
                // new track order. Otherwise, we have to check if there
                // is enough permission space to add all of the tracks
                if (newTracks.size == originalTracks.size)
                    newTracks
                else permissionHandler.takeUriPermissions(
                    newTracks, insertPartial = false)
            if (validatedTrackList.isEmpty()) {
                messageHandler.postMessage(StringResource(
                    R.string.cant_add_playlist_tracks_warning,
                    permissionHandler.permissionAllowance))
                // If there isn't enough space, we ignore the new
                // track list and only save the new shuffle value
                playlistDao.setPlaylistShuffle(playlistName, shuffle)
            } else playlistDao.setPlaylistShuffleAndTracks(
                playlistName, shuffle, validatedTrackList)
        }
    }
}

/**
 * Compose a [LibraryView], using an instance of [LibraryViewModel] to
 * obtain the list of tracks and to respond to item related callbacks.
 *
 * @param modifier The [Modifier] that will be used for the TrackList.
 * @param padding A [PaddingValues] instance whose values will be
 *     as the contentPadding for the TrackList
*  @param state The [LazyListState] used for the TrackList. state
 *     defaults to an instance of LazyListState returned from a
 *     [rememberLazyListState] call, but can be overridden here in
 *     case, e.g., the scrolling position needs to be remembered
 *     even when the SoundAuraTrackList leaves the composition.
 */
@Composable fun SoundAuraLibraryView(
    modifier: Modifier = Modifier,
    padding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
) = Surface(modifier, color = MaterialTheme.colors.background) {
    val viewModel: LibraryViewModel = viewModel()
    val itemCallback = rememberPlaylistViewCallback(
        onAddRemoveButtonClick = viewModel::onPlaylistAddRemoveButtonClick,
        onVolumeChange = viewModel::onPlaylistVolumeChange,
        onVolumeChangeFinished = viewModel::onPlaylistVolumeChangeFinished,
        onRenameClick = viewModel::onPlaylistRenameClick,
        onExtraOptionsClick = viewModel::onPlaylistOptionsClick,
        onRemoveClick = viewModel::onPlaylistRemoveClick)
    LibraryView(
        modifier = modifier,
        state = state,
        contentPadding = padding,
        libraryContents = viewModel.playlists,
        shownDialog = viewModel.shownDialog,
        playlistViewCallback = itemCallback)
}