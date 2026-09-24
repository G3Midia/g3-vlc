/*****************************************************************************
 * G3Sync.kt
 *****************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/
package org.videolan.vlc.g3

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.resources.util.getFromMl
import org.videolan.vlc.media.OnlineMediaResolvers
import java.net.URLEncoder
import java.util.Date

/**
 * Two-way bridge between VLC's own native library (SQLite playlists/favorites/history via
 * [Medialibrary]) and the Firestore data the G3Sounds web app already reads/writes
 * (`musicUsers/{uid}/playlists`, `/tracks`), so a signed-in account sees the same playlists and
 * favorites in both places.
 *
 * Sync isn't a live realtime listener: it runs at clear moments (right after sign-in, whenever
 * the Account screen is opened, or on demand) rather than reacting to every local edit, so it
 * stays simple to reason about and to test.
 */
object G3Sync {

    private const val ROOT_COLLECTION = "musicUsers"
    private const val LIKED_PLAYLIST_ID = "playlist_liked"
    private const val G3_PLAYLIST_PREFIX = "g3:"

    private fun docId(value: String): String = URLEncoder.encode(value, "UTF-8").replace(".", "%2E")

    private fun userDoc(userId: String) = FirebaseFirestore.getInstance()
            .collection(ROOT_COLLECTION)
            .document(docId(userId))

    private fun nowIso() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date())

    /**
     * Fire-and-forget: upserts this resolved online track into the signed-in user's Firestore
     * `tracks` collection (title/artist/thumbnail/direct URL), so it's visible in the account
     * even before it's ever added to a playlist or favorited. No-ops silently when signed out.
     */
    fun recordTrackPlayed(context: Context, mw: MediaWrapper) {
        if (!G3Account.isConfigured(context) || !G3Account.isSignedIn(context)) return
        val userId = G3Account.currentUserId(context)
        val (sourceType, sourceId) = OnlineMediaResolvers.sourceTypeAndId(mw)
        if (sourceType == "local") return
        val trackId = "$sourceType:$sourceId"
        val track = hashMapOf(
                "id" to trackId,
                "title" to (mw.title ?: ""),
                "artist" to (mw.artistName ?: ""),
                "sourceType" to sourceType,
                "sourceId" to sourceId,
                "duration" to (mw.length / 1000),
                "thumbnail" to (mw.artworkMrl ?: ""),
                "streamUrl" to mw.uri.toString(),
                "sourceUrl" to mw.uri.toString(),
                "mimeType" to "",
                "updatedAt" to nowIso()
        )
        org.videolan.tools.AppScope.launch {
            try {
                userDoc(userId).collection("tracks").document(docId(trackId))
                        .set(track, com.google.firebase.firestore.SetOptions.merge())
                        .await()
            } catch (_: Exception) {
                // best-effort; the track still plays locally either way
            }
        }
    }

    /**
     * Fire-and-forget: saves a followed YouTube channel/artist to the signed-in user's Firestore
     * `artists` collection, mirroring the G3Sounds web app's artist-follow feature. No-ops silently
     * when signed out.
     */
    fun followChannel(context: Context, channelId: String, name: String, thumbnail: String): Boolean {
        if (!G3Account.isConfigured(context) || !G3Account.isSignedIn(context)) return false
        val userId = G3Account.currentUserId(context)
        val artist = hashMapOf(
                "id" to channelId,
                "channelId" to channelId,
                "name" to name,
                "thumbnail" to thumbnail,
                "updatedAt" to nowIso()
        )
        org.videolan.tools.AppScope.launch {
            try {
                userDoc(userId).collection("artists").document(docId(channelId))
                        .set(artist, com.google.firebase.firestore.SetOptions.merge())
                        .await()
            } catch (_: Exception) {
                // best-effort
            }
        }
        return true
    }

    /**
     * Pulls the signed-in user's Firestore playlists/favorites and merges them into the local
     * medialibrary: online tracks (Drive/Dropbox/YouTube) get registered as external media via
     * [Medialibrary.addStream] if not already known locally, native playlists are created or
     * reused by name, and `playlist_liked` members get VLC's own favorite flag turned on.
     */
    suspend fun pullAndMerge(context: Context) {
        if (!G3Account.isConfigured(context) || !G3Account.isSignedIn(context)) return
        val userId = G3Account.currentUserId(context)
        val playlistsSnap = userDoc(userId).collection("playlists").get().await()

        for (playlistDoc in playlistsSnap.documents) {
            val name = playlistDoc.getString("name") ?: continue
            val trackIds = (playlistDoc.get("trackIds") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            if (trackIds.isEmpty()) continue

            val tracksSnap = userDoc(userId).collection("tracks").get().await()
            val tracksById = tracksSnap.documents.associateBy { it.id }

            val isFavoritesPlaylist = playlistDoc.id == docId(LIKED_PLAYLIST_ID)
            val nativePlaylist: Playlist? = if (isFavoritesPlaylist) null else G3Library.findOrCreateNativePlaylist(context, name)

            for (trackId in trackIds) {
                val trackDoc = tracksById[docId(trackId)] ?: continue
                val sourceType = trackDoc.getString("sourceType") ?: continue
                if (sourceType == "local") continue // not fetchable from another device

                val mw = G3Library.resolveOrCreateLocalMedia(
                        context,
                        sourceType = sourceType,
                        sourceId = trackDoc.getString("sourceId") ?: continue,
                        title = trackDoc.getString("title") ?: "?",
                        artist = trackDoc.getString("artist") ?: "",
                        thumbnail = trackDoc.getString("thumbnail") ?: ""
                ) ?: continue
                if (isFavoritesPlaylist) {
                    context.getFromMl { mw.setFavorite(true) }
                } else if (nativePlaylist != null) {
                    context.getFromMl {
                        val existingIds = nativePlaylist.getTracks(true, false).map { it.id }
                        if (mw.id !in existingIds) nativePlaylist.append(mw.id)
                    }
                }
            }
        }
    }

    /**
     * Deletes this native playlist's mirrored Firestore document too, so deleting a playlist on
     * this device (or replacing one by name in [org.videolan.vlc.gui.dialogs.SavePlaylistDialog])
     * doesn't leave an orphaned doc behind forever - [push] only ever adds/updates existing native
     * playlists, it has no way to notice one that no longer exists locally should be removed
     * remotely too. Fire-and-forget; no-ops silently when signed out.
     */
    fun deletePlaylistRemote(context: Context, nativePlaylistId: Long) {
        if (!G3Account.isConfigured(context) || !G3Account.isSignedIn(context)) return
        val userId = G3Account.currentUserId(context)
        val playlistId = "$G3_PLAYLIST_PREFIX$nativePlaylistId"
        org.videolan.tools.AppScope.launch {
            try {
                userDoc(userId).collection("playlists").document(docId(playlistId)).delete().await()
            } catch (_: Exception) {
                // best-effort; the local deletion already happened either way
            }
        }
    }

    /**
     * Pushes the local native playlists and favorited audio up to Firestore, so they show up in
     * the G3Sounds web app / other signed-in devices too. Local-only files are included as
     * `sourceType: "local"` metadata (title/artist, no usable stream) purely so they're visible;
     * only Drive/Dropbox/YouTube tracks are actually playable remotely.
     */
    suspend fun push(context: Context) {
        if (!G3Account.isConfigured(context) || !G3Account.isSignedIn(context)) return
        val userId = G3Account.currentUserId(context)
        val stamp = nowIso()

        val favoriteAudio = context.getFromMl { getAudio(Medialibrary.SORT_DEFAULT, false, true, true) }
        pushPlaylist(userId, LIKED_PLAYLIST_ID, "Favoritas", favoriteAudio.toList(), stamp)

        val nativePlaylists = context.getFromMl { getPlaylists(Playlist.Type.Audio, false) }
        for (playlist in nativePlaylists) {
            val tracks = context.getFromMl { playlist.getTracks(true, false) }
            pushPlaylist(userId, "$G3_PLAYLIST_PREFIX${playlist.id}", playlist.title, tracks.toList(), stamp)
        }
    }

    private suspend fun pushPlaylist(userId: String, playlistId: String, name: String, tracks: List<MediaWrapper>, stamp: String) {
        if (tracks.isEmpty()) return
        val trackIds = mutableListOf<String>()
        for (mw in tracks) {
            val (sourceType, sourceId) = OnlineMediaResolvers.sourceTypeAndId(mw)
            val trackId = "$sourceType:$sourceId"
            trackIds.add(trackId)
            val track = hashMapOf(
                    "id" to trackId,
                    "title" to (mw.title ?: ""),
                    "artist" to (mw.artistName ?: ""),
                    "sourceType" to sourceType,
                    "sourceId" to sourceId,
                    "duration" to (mw.length / 1000),
                    "thumbnail" to (mw.artworkMrl ?: ""),
                    "streamUrl" to (if (sourceType == "local") "" else mw.uri.toString()),
                    "sourceUrl" to mw.uri.toString(),
                    "mimeType" to "",
                    "updatedAt" to stamp
            )
            userDoc(userId).collection("tracks").document(docId(trackId)).set(track, com.google.firebase.firestore.SetOptions.merge()).await()
        }
        val playlist = hashMapOf(
                "id" to playlistId,
                "userId" to userId,
                "name" to name,
                "description" to "",
                "trackIds" to trackIds,
                "updatedAt" to stamp
        )
        userDoc(userId).collection("playlists").document(docId(playlistId)).set(playlist, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    /**
     * Drive-backed counterpart to [push]/[pullAndMerge] - a second, independent backup channel the
     * user owns directly (their own Drive storage/quota, not this app's Firestore project),
     * requested explicitly alongside the existing Firestore sync rather than replacing it (that one
     * keeps feeding the separate G3Sounds web app, which reads the same Firestore collections).
     * Single JSON blob in the hidden `appDataFolder` (see [G3Drive]) - simpler than mirroring
     * Firestore's per-document collections, and appDataFolder has no shared-document concept anyway.
     */
    suspend fun pushToDrive(context: Context) {
        if (!G3Account.isConfigured(context) || !G3Account.isSignedIn(context)) return
        val stamp = nowIso()
        val tracks = JSONObject()
        val playlists = JSONArray()

        fun addPlaylistJson(id: String, name: String, mediaList: List<MediaWrapper>) {
            if (mediaList.isEmpty()) return
            val trackIds = JSONArray()
            for (mw in mediaList) {
                val (sourceType, sourceId) = OnlineMediaResolvers.sourceTypeAndId(mw)
                val trackId = "$sourceType:$sourceId"
                trackIds.put(trackId)
                tracks.put(trackId, JSONObject().apply {
                    put("title", mw.title ?: "")
                    put("artist", mw.artistName ?: "")
                    put("sourceType", sourceType)
                    put("sourceId", sourceId)
                    put("thumbnail", mw.artworkMrl ?: "")
                })
            }
            playlists.put(JSONObject().apply {
                put("id", id)
                put("name", name)
                put("trackIds", trackIds)
            })
        }

        val favoriteAudio = context.getFromMl { getAudio(Medialibrary.SORT_DEFAULT, false, true, true) }
        addPlaylistJson(LIKED_PLAYLIST_ID, "Favoritas", favoriteAudio.toList())

        val nativePlaylists = context.getFromMl { getPlaylists(Playlist.Type.Audio, false) }
        for (playlist in nativePlaylists) {
            val playlistTracks = context.getFromMl { playlist.getTracks(true, false) }
            addPlaylistJson("$G3_PLAYLIST_PREFIX${playlist.id}", playlist.title, playlistTracks.toList())
        }

        val payload = JSONObject().apply {
            put("updatedAt", stamp)
            put("tracks", tracks)
            put("playlists", playlists)
        }
        G3Drive.upload(context, payload.toString())
    }

    /** See [pushToDrive]. Same merge semantics as [pullAndMerge]: only ever adds/reuses, never
     * deletes or overwrites what's already local. */
    suspend fun pullFromDriveAndMerge(context: Context) {
        if (!G3Account.isConfigured(context) || !G3Account.isSignedIn(context)) return
        val json = G3Drive.download(context) ?: return
        val payload = JSONObject(json)
        val tracks = payload.optJSONObject("tracks") ?: JSONObject()
        val playlists = payload.optJSONArray("playlists") ?: JSONArray()

        for (i in 0 until playlists.length()) {
            val playlistJson = playlists.getJSONObject(i)
            val id = playlistJson.optString("id")
            val name = playlistJson.optString("name")
            val trackIds = playlistJson.optJSONArray("trackIds") ?: JSONArray()
            if (trackIds.length() == 0) continue

            val isFavoritesPlaylist = id == LIKED_PLAYLIST_ID
            val nativePlaylist: Playlist? = if (isFavoritesPlaylist) null else G3Library.findOrCreateNativePlaylist(context, name)

            for (j in 0 until trackIds.length()) {
                val trackId = trackIds.getString(j)
                val trackJson = tracks.optJSONObject(trackId) ?: continue
                val sourceType = trackJson.optString("sourceType")
                if (sourceType.isEmpty() || sourceType == "local") continue

                val mw = G3Library.resolveOrCreateLocalMedia(
                        context,
                        sourceType = sourceType,
                        sourceId = trackJson.optString("sourceId"),
                        title = trackJson.optString("title", "?"),
                        artist = trackJson.optString("artist"),
                        thumbnail = trackJson.optString("thumbnail")
                ) ?: continue

                if (isFavoritesPlaylist) {
                    context.getFromMl { mw.setFavorite(true) }
                } else if (nativePlaylist != null) {
                    context.getFromMl {
                        val existingIds = nativePlaylist.getTracks(true, false).map { it.id }
                        if (mw.id !in existingIds) nativePlaylist.append(mw.id)
                    }
                }
            }
        }
    }
}
