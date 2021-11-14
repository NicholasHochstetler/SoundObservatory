/* This file is part of SoundObservatory, which is released under the Apache License 2.0. See
 * license.md in the project's root directory or use an internet search engine to see the full license. */
package com.cliffracertech.soundobservatory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliffracertech.soundobservatory.ui.theme.SoundObservatoryTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalAnimationApi
@ExperimentalComposeUiApi
@ExperimentalCoroutinesApi
@ExperimentalAnimationGraphicsApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: TrackViewModel = viewModel()
            val tracks by viewModel.tracks.collectAsState()
            val trackSort by viewModel.trackSort.collectAsState()
            val itemCallback = TrackViewCallback(
                onPlayPauseButtonClick = { id, playing -> viewModel.updatePlaying(id, playing) },
                onVolumeChangeRequest = { id, volume -> viewModel.updateVolume(id, volume) },
                onRenameRequest = { id, name -> viewModel.updateName(id, name) },
                onDeleteRequest = { id: Long -> viewModel.delete(id) })
            MainActivityContent(tracks = tracks,
                                trackSort = trackSort,
                                itemCallback = itemCallback,
                                onSortingChanged = { viewModel.trackSort.value = it },
                                onAddItemRequest = { viewModel.add(it) })
        }
    }
}

@ExperimentalCoroutinesApi
@ExperimentalComposeUiApi
@ExperimentalAnimationGraphicsApi
@ExperimentalAnimationApi
@Preview(showBackground = true)
@Composable fun MainActivityPreview() = MainActivityContent(
    listOf(Track(path = "", name = "Audio clip 1", volume = 0.3f),
           Track(path = "", name = "Audio clip 2", volume = 0.8f)))

@ExperimentalCoroutinesApi
@ExperimentalComposeUiApi
@ExperimentalAnimationGraphicsApi
@ExperimentalAnimationApi
@Composable fun MainActivityContent(
    tracks: List<Track>,
    trackSort: Track.Sort = Track.Sort.NameAsc,
    itemCallback: TrackViewCallback = TrackViewCallback(),
    onSortingChanged: (Track.Sort) -> Unit = { },
    onAddItemRequest: (Track) -> Unit = { },
) {
    val title = stringResource(R.string.app_name)

    SoundObservatoryTheme {
        Surface(
            color = MaterialTheme.colors.background,
            modifier = Modifier.fillMaxSize(1f)
        ) {
            var actionModeTitle by remember { mutableStateOf<String?>(null) }
            var searchQuery by remember { mutableStateOf<String?>(null) }
            var showingAddLocalFileDialog by remember { mutableStateOf(false) }
            //var showingDownloadFileDialog by remember { mutableStateOf(false) }
            Column {
                var addButtonExpanded by remember { mutableStateOf(false) }
                ListActionBar(
                    backButtonVisible = false,
                    onBackButtonClick = { },
                    title, actionModeTitle, searchQuery,
                    onSearchQueryChanged = { searchQuery = it },
                    sortOption = trackSort,
                    onSortOptionChanged = onSortingChanged,
                    sortOptionNameFunc = { string(it) },
                    onSearchButtonClicked = {
                        searchQuery = if (searchQuery == null) "" else null
                    })
                Box(Modifier.fillMaxSize(1f)) {
                    TrackList(tracks, itemCallback)
                    DownloadOrAddLocalFileButton(
                        expanded = addButtonExpanded,
                        onClick = { addButtonExpanded = !addButtonExpanded },
                        onAddDownloadClick = { addButtonExpanded = false },
                                                //showingDownloadFileDialog = true },
                        onAddLocalFileClick = { addButtonExpanded = false
                                                showingAddLocalFileDialog = true },
                        modifier = Modifier.padding(8.dp).align(Alignment.BottomEnd))
                }
                //if (showingDownloadFileDialog)
                if (showingAddLocalFileDialog)
                    AddTrackFromLocalFileDialog(
                        onDismissRequest = { showingAddLocalFileDialog = false },
                        onConfirmRequest = { onAddItemRequest(it)
                                             showingAddLocalFileDialog = false })
            }
        }
    }
}

@ExperimentalComposeUiApi
@ExperimentalAnimationGraphicsApi
@Composable fun TrackList(tracks: List<Track>, trackViewCallback: TrackViewCallback) =
    LazyColumn(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tracks) { TrackView(it, trackViewCallback) }
    }




