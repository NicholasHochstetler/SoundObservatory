/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model.database

import android.content.Context
import android.net.Uri
import androidx.annotation.FloatRange
import androidx.core.net.toUri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.model.ListValidator
import com.cliffracertech.soundaura.model.StringResource
import com.cliffracertech.soundaura.model.Validator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Entity(tableName = "track")
data class Track(
    @ColumnInfo(typeAffinity = ColumnInfo.TEXT)
    @PrimaryKey val uri: Uri,

    @ColumnInfo(defaultValue = "0")
    val hasError: Boolean = false
) {
    class UriStringConverter {
        @TypeConverter fun fromString(string: String) = string.toUri()
        @TypeConverter fun toString(uri: Uri) = uri.toString()
    }
}

@Entity(tableName = "playlistTrack",
        primaryKeys = ["playlistName", "trackUri"],
        foreignKeys = [
            ForeignKey(
                entity = Playlist::class,
                parentColumns=["name"],
                childColumns=["playlistName"],
                onUpdate=ForeignKey.CASCADE,
                onDelete=ForeignKey.CASCADE),
            ForeignKey(
                entity = Track::class,
                parentColumns=["uri"],
                childColumns=["trackUri"],
                onUpdate=ForeignKey.CASCADE,
                onDelete=ForeignKey.CASCADE)])
data class PlaylistTrack(
    val playlistName: String,
    val trackUri: Uri,
    val playlistOrder: Int)

@Entity(tableName = "playlist")
data class Playlist(
    /** The name of the [Playlist]. */
    @PrimaryKey
    val name: String,

    /** Whether or not shuffle is enabled for the [Playlist]. */
    @ColumnInfo(defaultValue = "0")
    val shuffle: Boolean = false,

    /** Whether or not the [Playlist] is active (i.e. part of the current sound mix). */
    @ColumnInfo(defaultValue = "0")
    val isActive: Boolean = false,

    /** The volume (in the range `[0f, 1f]`) of the [Playlist] during playback. */
    @FloatRange(from = 0.0, to = 1.0)
    @ColumnInfo(defaultValue = "1.0")
    val volume: Float = 1f,

    /** Whether or not the entire playlist has an error. This should be
     * true if every track in the playlist has an error, but is stored
     * as a separate column instead of being computed to prevent needing
     * a subquery to determine its value. */
    @ColumnInfo(defaultValue = "0")
    val hasError: Boolean = false,
) {
    enum class Sort { NameAsc, NameDesc, OrderAdded;
        fun name(context: Context) = when (this) {
            NameAsc -> context.getString(R.string.name_ascending)
            NameDesc -> context.getString(R.string.name_descending)
            OrderAdded -> context.getString(R.string.order_added)
        }
    }
}

/** A [ListValidator] that validates a list of new single-track [Playlist] names. */
class TrackNamesValidator(
    private val playlistDao: PlaylistDao,
    coroutineScope: CoroutineScope,
    names: List<String>,
) : ListValidator<String>(names, coroutineScope, allowDuplicates = false) {

    private var existingNames: Set<String>? = null
    init { coroutineScope.launch {
        existingNames = playlistDao.getPlaylistNames().first().toSet()
    }}

    override fun isInvalid(value: String) =
        value.isBlank() || existingNames?.contains(value) == true

    override val errorMessage = Validator.Message.Error(
        StringResource(R.string.add_multiple_tracks_name_error_message))

    override suspend fun validate(): List<String>? {
        val existingNames = playlistDao.getPlaylistNames().first().toSet()
        return when {
            values.intersect(existingNames).isNotEmpty() -> null
            values.containsBlanks() -> null
            else -> super.validate()
        }
    }

    /** Return whether the list contains any strings that are blank
     * (i.e. are either empty or consist of only whitespace characters). */
    private fun List<String>.containsBlanks() = find { it.isBlank() } != null
}

/**
 * Return a [Validator] that validates names for a new [Playlist].
 *
 * Names that match an existing [Playlist] are not permitted. Blank names
 * are also not permitted, although no error message will be shown for
 * blank names unless [Validator.value] has been changed at least once.
 * This is to prevent showing a 'no blank names' error message before
 * the user has had a chance to change the name.
 */
fun newPlaylistNameValidator(
    dao: PlaylistDao,
    coroutineScope: CoroutineScope,
    initialName: String = "",
) = Validator(
    initialValue = initialName,
    coroutineScope = coroutineScope,
    messageFor = { name, hasBeenChanged -> when {
        name.isBlank() && hasBeenChanged ->
            Validator.Message.Error(R.string.name_dialog_blank_name_error_message)
        dao.exists(name) ->
            Validator.Message.Error(R.string.name_dialog_duplicate_name_error_message)
        else -> null
    }})

/**
 * Return a [Validator] that validates the renaming of an existing [Playlist].
 *
 * Blank names are not permitted. Names that match an existing [Playlist]
 * are also not permitted, unless it is equal to the provided [oldName].
 * This is to prevent a 'no duplicate names' error message from being shown
 * immediately when the dialog is opened.
 */
fun playlistRenameValidator(
    dao: PlaylistDao,
    oldName: String,
    coroutineScope: CoroutineScope,
) = Validator(
    initialValue = oldName,
    coroutineScope = coroutineScope,
    messageFor = { name, _ -> when {
        name == oldName ->  null
        name.isBlank() ->   Validator.Message.Error(R.string.name_dialog_blank_name_error_message)
        dao.exists(name) -> Validator.Message.Error(R.string.name_dialog_duplicate_name_error_message)
        else ->             null
    }})