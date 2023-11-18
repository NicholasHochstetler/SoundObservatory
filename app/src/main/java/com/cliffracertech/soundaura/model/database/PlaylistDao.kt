/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model.database

import android.net.Uri
import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

typealias LibraryPlaylist = com.cliffracertech.soundaura.library.Playlist

private const val librarySelect =
    "SELECT name, isActive, volume, hasError, " +
           "COUNT(playlistName) = 1 AS isSingleTrack " +
    "FROM playlist " +
    "JOIN playlistTrack ON playlist.name = playlistTrack.playlistName " +
    "WHERE name LIKE :filter " +
    "GROUP BY playlistTrack.playlistName"

@Dao abstract class PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlaylistName(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlaylistNames(playlists: List<Playlist>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTracks(tracks: List<Track>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlaylistTrack(playlistTrack: PlaylistTrack)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlaylistTracks(playlistTracks: List<PlaylistTrack>)

    /** Insert a single [Playlist] whose [Playlist.name] and [Playlist.shuffle]
     * vales will be equal to [playlistName] and [shuffle], respectively. The
     * [Track]s in [tracks] will be added as the contents of the playlist. */
    @Transaction
    open suspend fun insertPlaylist(
        playlistName: String,
        shuffle: Boolean,
        tracks: List<Track>
    ) {
        insertPlaylistName(Playlist(playlistName, shuffle))
        insertTracks(tracks)
        insertPlaylistTracks(tracks.mapIndexed { index, track ->
            PlaylistTrack(playlistName, track.uri, index)
        })
    }

    /** Insert a collection of new single-track [Playlist]s. The entries of
     * [uriNameMap] will define the [Uri] of the single track and the name
     * for each new playlist. The [Playlist.shuffle] value for the new
     * playlists will be the default value (i.e. false) due to shuffle having
     * no meaning for single-track playlists. If the order of tracks in the
     * playlist must be preserved, a [Map] implementation that ensures a
     * certain iteration order (e.g. [LinkedHashMap]) should be used. */
    @Transaction
    open suspend fun insertSingleTrackPlaylists(uriNameMap: Map<Uri, String>) {
        insertPlaylistNames(uriNameMap.values.map(::Playlist))
        insertTracks(uriNameMap.keys.map(::Track))
        insertPlaylistTracks(
            uriNameMap.entries.mapIndexed { index, (uri, name) ->
                PlaylistTrack(name, uri, index)
            })
    }

    /** Delete the playlist whose name matches [name] from the database. */
    @Query("DELETE FROM playlist WHERE name = :name")
    protected abstract suspend fun deletePlaylistName(name: String)

    @Query("DELETE FROM playlistTrack WHERE playlistName = :playlistName")
    protected abstract suspend fun deletePlaylistTracks(playlistName: String)

    @Query("DELETE FROM track WHERE uri IN (:uris)")
    protected abstract suspend fun deleteTracks(uris: List<Uri>)

    /** Delete the [Playlist] whose name matches [name] along with its contents.
     * @return the [List] of [Uri]s that are no longer a part of any playlist */
    @Transaction
    open suspend fun deletePlaylist(name: String): List<Uri> {
        val removableTracks = getUniqueUris(name)
        deletePlaylistName(name)
        // playlistTrack.playlistName has an 'on delete: cascade' policy,
        // so the playlistTrack rows don't need to be deleted manually
        deleteTracks(removableTracks)
        return removableTracks
    }

    @Query("SELECT shuffle FROM playlist " +
           "WHERE playlist.name = :playlistName")
    abstract suspend fun getPlaylistShuffle(playlistName: String): Boolean

    @Query("UPDATE playlist SET shuffle = :enabled WHERE name = :playlistName")
    abstract suspend fun setPlaylistShuffle(playlistName: String, enabled: Boolean)

    /**
     * Set the playlist whose [Playlist.name] property matches [playlistName] to
     * have a [Playlist.shuffle] value equal to [shuffleEnabled], and overwrite
     * its tracks to be equal to [newTracks]. If the list of tracks that can be
     * removed (i.e. that aren't in [newTracks] and are not a part of any other
     * playlist) has already been obtained, it can be passed as [removableUris]
     * to prevent the database from needing to recalculate this.
     *
     * @return The [List] of [Uri]s that are no longer in any [Playlist] after the change.
     */
    @Transaction
    open suspend fun setPlaylistShuffleAndContents(
        playlistName: String,
        shuffleEnabled: Boolean,
        newTracks: List<Track>,
        removableUris: List<Uri>? = null,
    ): List<Uri> {
        val removedUris = removableUris ?: run {
            val newUris = newTracks.map(Track::uri)
            getUniqueUrisNotIn(newUris, playlistName)
        }
        deleteTracks(removedUris)
        insertTracks(newTracks)

        deletePlaylistTracks(playlistName)
        insertPlaylistTracks(newTracks.mapIndexed { index, track ->
            PlaylistTrack(playlistName, track.uri, index)
        })

        setPlaylistShuffle(playlistName, shuffleEnabled)
        return removedUris
    }

    /** Return the track uris of the [Playlist] identified by
     * [playlistName] that are not in any other [Playlist]s. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "GROUP BY trackUri HAVING COUNT(playlistName) = 1 " +
                             "AND playlistName = :playlistName")
    protected abstract suspend fun getUniqueUris(playlistName: String): List<Uri>

    /** Return the track uris of the [Playlist] identified by [playlistName]
     * that are not in any other [Playlist]s and are not in [exceptions]. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "WHERE trackUri NOT IN (:exceptions) " +
           "GROUP BY trackUri HAVING COUNT(playlistName) = 1 " +
                             "AND playlistName = :playlistName")
    abstract suspend fun getUniqueUrisNotIn(
        exceptions: List<Uri>,
        playlistName: String
    ): List<Uri>

    @RawQuery
    protected abstract suspend fun filterNewTracks(query: SupportSQLiteQuery): List<Uri>

    suspend fun filterNewTracks(tracks: List<Uri>): List<Uri> {
        // The following query requires parentheses around each argument. This
        // is not supported by Room, so the query must be made manually.
        val query = StringBuilder()
            .append("WITH newTrack(uri) AS (VALUES ")
            .apply {
                for (i in 0 until tracks.lastIndex)
                    append("(?), ")
            }.append("(?)) ")
            .append("SELECT newTrack.uri FROM newTrack ")
            .append("LEFT JOIN track ON track.uri = newTrack.uri ")
            .append("WHERE track.uri IS NULL;")
            .toString()
        val args = Array(tracks.size) { tracks[it].toString() }
        return filterNewTracks(SimpleSQLiteQuery(query, args))
    }

    /** Return whether or not a [Playlist] whose name matches [name] exists. */
    @Query("SELECT EXISTS(SELECT name FROM playlist WHERE name = :name)")
    abstract suspend fun exists(name: String?): Boolean

    @Query("$librarySelect ORDER BY name COLLATE NOCASE ASC")
    abstract fun getAllPlaylistsSortedByNameAsc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY name COLLATE NOCASE DESC")
    abstract fun getAllPlaylistsSortedByNameDesc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, name COLLATE NOCASE ASC")
    abstract fun getAllPlaylistsSortedByActiveThenNameAsc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, name COLLATE NOCASE DESC")
    abstract fun getAllPlaylistsSortedByActiveThenNameDesc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist WHERE isActive)")
    abstract fun getAtLeastOnePlaylistIsActive(): Flow<Boolean>

    /** Return a [Flow] that updates with a [Map] of each
     * active [Playlist] mapped to its list of track [Uri]s. */
    @MapInfo(valueColumn = "trackUri")
    @Query("SELECT name, shuffle, isActive, volume, hasError, trackUri " +
           "FROM playlist " +
           "JOIN playlistTrack ON playlist.name = playlistTrack.playlistName " +
           "WHERE isActive ORDER by playlistOrder")
    abstract fun getActivePlaylistsAndTracks(): Flow<Map<Playlist, List<Uri>>>

    @Query("SELECT name FROM playlist")
    abstract fun getPlaylistNames(): Flow<List<String>>

    @Query("SELECT uri, hasError FROM playlistTrack " +
           "JOIN track on playlistTrack.trackUri = track.uri " +
           "WHERE playlistName = :name ORDER by playlistOrder")
    abstract suspend fun getPlaylistTracks(name: String): List<Track>

    /** Rename the [Playlist] whose name matches [oldName] to [newName]. */
    @Query("UPDATE playlist SET name = :newName WHERE name = :oldName")
    abstract suspend fun rename(oldName: String, newName: String)

    /** Toggle the [Playlist.isActive] field of the [Playlist] identified by [name]. */
    @Query("UPDATE playlist set isActive = 1 - isActive WHERE name = :name")
    abstract suspend fun toggleIsActive(name: String)

    /** Set the [Playlist.volume] field of the [Playlist] identified by [name]. */
    @Query("UPDATE playlist SET volume = :volume WHERE name = :name")
    abstract suspend fun setVolume(name: String, volume: Float)

    @Query("UPDATE track SET hasError = 1 WHERE uri in (:uris)")
    protected abstract suspend fun setTracksHaveError(uris: List<Uri>)

    @Query("SELECT NOT EXISTS(SELECT playlistName FROM playlistTrack " +
                             "JOIN track ON playlistTrack.trackUri = track.uri " +
                             "WHERE playlistName = :playlistName AND NOT track.hasError)")
    protected abstract suspend fun playlistHasNoValidTracks(playlistName: String): Boolean

    @Query("UPDATE playlist SET hasError = 1 WHERE name = :playlistName")
    protected abstract suspend fun setPlaylistHasError(playlistName: String)

    @Transaction
    open suspend fun setPlaylistTrackHasError(
        playlistName: String,
        trackUris: List<Uri>
    ) {
        setTracksHaveError(trackUris)
        if (playlistHasNoValidTracks(playlistName))
            setPlaylistHasError(playlistName)
    }
}