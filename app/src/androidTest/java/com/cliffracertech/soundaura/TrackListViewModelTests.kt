/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackListViewModelTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var searchQueryState: SearchQueryState
    private lateinit var db: SoundAuraDatabase
    private lateinit var dao: TrackDao
    private lateinit var instance: TrackListViewModel
    private lateinit var tracksFlow: Flow<ImmutableList<Track>?>
    private val testTracks = List(5) {
        Track(uriString = "uri$it", name = "track $it")
    }

    @Before fun init() {
        db = Room.inMemoryDatabaseBuilder(context, SoundAuraDatabase::class.java).build()
        dao = db.trackDao()
        searchQueryState = SearchQueryState()
        instance = TrackListViewModel(context.dataStore, dao, searchQueryState)
        tracksFlow = snapshotFlow { instance.tracks }
    }
    @After fun cleanUp() = db.close()

    @Test fun tracks_property_updates() = runTest {
        var tracks = tracksFlow.first()
        assertThat(tracks?.isEmpty() != false).isTrue()
        dao.insert(testTracks)
        tracks = tracksFlow.waitUntil { !it.isNullOrEmpty() }
        assertThat(tracks).containsExactlyElementsIn(testTracks).inOrder()
    }

    @Test fun delete_track_dialog_confirm() = runTest {
        dao.insert(testTracks)
        var tracks = tracksFlow.waitUntil { !it.isNullOrEmpty() }
        val track3 = testTracks[2]
        instance.onDeleteTrackDialogConfirm(context, track3.uriString)
        tracks = tracksFlow.waitUntil {
            it?.size == (tracks!!.size - 1)
        }
        assertThat(tracks).containsExactlyElementsIn(testTracks.minus(track3)).inOrder()
    }

    @Test fun track_add_remove_click() = runTest {
        dao.insert(testTracks)
        var tracks = tracksFlow.waitUntil { !it.isNullOrEmpty() }
        assertThat(tracks?.map(Track::isActive)).doesNotContain(true)
        instance.onTrackAddRemoveButtonClick(testTracks[3].uriString)
        tracks = tracksFlow.waitUntil {
            it?.map(Track::isActive)?.get(3) == true
        }
        assertThat(tracks?.map(Track::isActive))
            .containsExactlyElementsIn(listOf(false, false, false, true, false))
            .inOrder()
        instance.onTrackAddRemoveButtonClick(testTracks[1].uriString)
        instance.onTrackAddRemoveButtonClick(testTracks[3].uriString)
        tracks = tracksFlow.waitUntil {
            it?.map(Track::isActive)?.get(1) == true
        }
        assertThat(tracks?.map(Track::isActive))
            .containsExactlyElementsIn(listOf(false, true, false, false, false))
            .inOrder()
    }

    @Test fun track_volume_change_request() = runTest {
        dao.insert(testTracks)
        var tracks = tracksFlow.waitUntil { !it.isNullOrEmpty() }
        val expectedVolumes = mutableListOf(1f, 1f, 1f, 1f, 1f)
        assertThat(tracks?.map(Track::volume))
            .containsExactlyElementsIn(expectedVolumes)
        instance.onTrackVolumeChangeRequest(testTracks[2].uriString, 0.5f)
        expectedVolumes[2] = 0.5f
        tracks = tracksFlow.waitUntil {
            it?.map(Track::volume)?.get(2) == 0.5f
        }
        assertThat(tracks?.map(Track::volume))
            .containsExactlyElementsIn(expectedVolumes).inOrder()
        instance.onTrackVolumeChangeRequest(testTracks[2].uriString, 1f)
        instance.onTrackVolumeChangeRequest(testTracks[1].uriString, 0.25f)
        instance.onTrackVolumeChangeRequest(testTracks[4].uriString, 0.75f)
        expectedVolumes[2] = 1f
        expectedVolumes[1] = 0.25f
        expectedVolumes[4] = 0.75f
        tracks = tracksFlow.waitUntil {
            it?.map(Track::volume)?.get(2) == 1f
        }
        assertThat(tracks?.map { it.volume })
            .containsExactlyElementsIn(expectedVolumes).inOrder()
    }

    @Test fun track_rename_dialog_confirm() = runTest {
        dao.insert(testTracks)
        var tracks = tracksFlow.waitUntil { !it.isNullOrEmpty() }
        assertThat(tracks?.get(3)?.name).isEqualTo(testTracks[3].name)
        val newTrack3Name = "new ${testTracks[3].name}"
        instance.onTrackRenameDialogConfirm(testTracks[3].uriString, newTrack3Name)
        tracks = tracksFlow.waitUntil {
            it?.map(Track::name)?.contains(newTrack3Name) == true
        }
        val expectedTracks = testTracks
            .minus(testTracks[3])
            .plus(testTracks[3].copy(name = newTrack3Name))
        assertThat(tracks).containsExactlyElementsIn(expectedTracks)
    }

    @Test fun search_queries_work() = runTest {
        dao.insert(testTracks)
        searchQueryState.query.value = "3"
        var tracks = tracksFlow.waitUntil { it?.size == 1 }
        assertThat(tracks).containsExactlyElementsIn(listOf(testTracks[3]))
        searchQueryState.query.value = "track "
        tracks = tracksFlow.waitUntil { it?.size == testTracks.size }
        assertThat(tracks).containsExactlyElementsIn(testTracks).inOrder()
    }
}