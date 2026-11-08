package com.jmti.budgetfy.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Keeps the profile picture and spending-card background in Firestore so they
 * survive uninstalling the app or switching phones.
 *
 * - Settings (display name, card size, which background) live on users/{uid}.
 * - Custom images live in user_images/{uid}_avatar and user_images/{uid}_card,
 *   readable only by their owner (see Firestore rules).
 * - Ready-made backgrounds are bundled in the app, so only their name is saved.
 *
 * Locally, images are kept as files in the app's storage and their file://
 * URIs are stored in the same prefs the screens already read.
 *
 * If a backup fails (offline, rules not published yet), the profile is marked
 * "dirty" and pushed again on the next launch; local images are never removed
 * because of a failed download.
 */
object ProfileSync {
    private const val TAG = "ProfileSync"
    private const val PREFS = "budgetfy_prefs"

    // Prefs keys the screens already use
    const val KEY_NAME = "display_name"
    const val KEY_AVATAR = "profile_avatar_uri"
    const val KEY_CARD_MODE = "card_mode"
    const val KEY_CARD_BG = "card_bg_uri"

    /** Which account the local profile settings belong to. */
    private const val KEY_OWNER = "profile_owner_uid"
    /** Account whose cloud profile is in sync with this install. */
    private const val KEY_SYNCED = "profile_synced_uid"
    /** Local changes that still need uploading. */
    private const val KEY_DIRTY = "profile_dirty"

    private const val AVATAR = "avatar"
    private const val CARD = "card"
    private const val PRESET_PREFIX = "preset:"
    private const val CUSTOM = "custom"

    private val db get() = FirebaseFirestore.getInstance()
    private fun imageDoc(uid: String, kind: String) =
        db.collection("user_images").document("${uid}_$kind")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun presetUri(context: Context, name: String) =
        "android.resource://${context.packageName}/drawable/$name"

    private fun presetName(uri: String): String? =
        if (uri.startsWith("android.resource://")) uri.substringAfterLast("/drawable/", "").ifEmpty { null }
        else null

    // ── Save (Edit profile → Save) ─────────────────────────────────────────

    /**
     * Saves the edited profile: copies newly picked images into the app's own
     * storage (gallery links can stop working), backs them up, and returns the
     * final avatar and card URIs to store locally.
     * Must run off the main thread.
     */
    suspend fun saveProfile(
        context: Context,
        uid: String?,
        displayName: String,
        avatarUri: String?, avatarChanged: Boolean,
        cardMode: String,
        cardUri: String?, cardChanged: Boolean
    ): Pair<String?, String?> {
        var finalAvatar = avatarUri
        var finalCard = cardUri
        var ok = uid != null

        if (avatarChanged) {
            finalAvatar = avatarUri?.let { storeLocal(context, Uri.parse(it), AVATAR) }
            if (uid != null) ok = uploadOrDelete(context, uid, AVATAR, finalAvatar) && ok
        }
        if (cardChanged) {
            finalCard = when {
                cardUri == null -> null
                presetName(cardUri) != null -> cardUri
                else -> storeLocal(context, Uri.parse(cardUri), CARD)
            }
            if (uid != null) {
                ok = uploadOrDelete(context, uid, CARD, finalCard?.takeIf { presetName(it) == null }) && ok
            }
        }
        // Settings go up only after the images, so the cloud never points at a
        // custom image that failed to upload.
        if (uid != null && ok) ok = saveSettings(uid, displayName, cardMode, finalAvatar, finalCard)

        val edit = prefs(context).edit()
        if (uid != null) edit.putString(KEY_OWNER, uid).putString(KEY_SYNCED, uid)
        edit.putBoolean(KEY_DIRTY, !ok).apply()
        return finalAvatar to finalCard
    }

    private suspend fun saveSettings(
        uid: String, displayName: String, cardMode: String, avatar: String?, card: String?
    ): Boolean {
        val background = when {
            card == null -> null
            presetName(card) != null -> PRESET_PREFIX + presetName(card)
            else -> CUSTOM
        }
        return try {
            db.collection("users").document(uid).set(
                mapOf(
                    "displayName" to displayName,
                    "cardMode" to cardMode,
                    "cardBackground" to background,
                    "hasAvatar" to (avatar != null),
                    "profileUpdatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveSettings: ${e.message}")
            false
        }
    }

    // ── Restore (app start) ────────────────────────────────────────────────

    /**
     * Runs on app start. Pushes local changes that failed to upload before;
     * otherwise, once per account per install, restores the cloud profile
     * (reinstall / new phone) or backs up what this phone already has.
     * Returns true when local settings changed and the screen should reload.
     * Must run off the main thread.
     */
    suspend fun syncOnLaunch(context: Context, uid: String): Boolean {
        // Home can load twice at startup; one sync at a time is enough.
        if (!syncing.compareAndSet(false, true)) return false
        try {
            return syncOnLaunchLocked(context, uid)
        } finally {
            syncing.set(false)
        }
    }

    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun syncOnLaunchLocked(context: Context, uid: String): Boolean {
        val prefs = prefs(context)
        val owner = prefs.getString(KEY_OWNER, null)
        val dirty = prefs.getBoolean(KEY_DIRTY, false)
        if (prefs.getString(KEY_SYNCED, null) == uid && !(dirty && owner == uid)) return false

        return try {
            if (dirty && owner == uid) {
                pushLocal(context, uid, fallbackName = "")
                return true
            }
            val doc = db.collection("users").document(uid).get().await()
            when {
                doc.contains("cardMode") -> restore(context, uid, doc.data.orEmpty())
                owner == null || owner == uid ->
                    pushLocal(context, uid, fallbackName = doc.getString("displayName") ?: "")
                else -> {
                    // Settings on this phone belong to another account: don't show them.
                    prefs.edit().remove(KEY_NAME).remove(KEY_AVATAR).remove(KEY_CARD_BG)
                        .remove(KEY_CARD_MODE).putString(KEY_OWNER, uid)
                        .putString(KEY_SYNCED, uid).putBoolean(KEY_DIRTY, false).apply()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "syncOnLaunch: ${e.message}")
            false
        }
    }

    /** Cloud profile exists → bring it onto this phone. */
    private suspend fun restore(context: Context, uid: String, data: Map<String, Any>) {
        val edit = prefs(context).edit()
        var complete = true
        (data["displayName"] as? String)?.let { edit.putString(KEY_NAME, it) }
        edit.putString(KEY_CARD_MODE, data["cardMode"] as? String ?: "standard")

        if (data["hasAvatar"] == true) {
            val avatar = download(context, uid, AVATAR)
            if (avatar != null) edit.putString(KEY_AVATAR, avatar) else complete = false
        } else {
            edit.remove(KEY_AVATAR)
        }

        val bg = data["cardBackground"] as? String
        when {
            bg == null -> edit.remove(KEY_CARD_BG)
            bg.startsWith(PRESET_PREFIX) ->
                edit.putString(KEY_CARD_BG, presetUri(context, bg.removePrefix(PRESET_PREFIX)))
            bg == CUSTOM -> {
                val card = download(context, uid, CARD)
                if (card != null) edit.putString(KEY_CARD_BG, card) else complete = false
            }
        }
        edit.putString(KEY_OWNER, uid).putBoolean(KEY_DIRTY, false)
        // A failed download is retried next launch.
        if (complete) edit.putString(KEY_SYNCED, uid) else edit.remove(KEY_SYNCED)
        edit.apply()
    }

    /** Uploads the profile currently on this phone. */
    private suspend fun pushLocal(context: Context, uid: String, fallbackName: String) {
        val prefs = prefs(context)
        val (avatar, card) = saveProfile(
            context, uid,
            displayName = prefs.getString(KEY_NAME, null) ?: fallbackName,
            avatarUri = prefs.getString(KEY_AVATAR, null), avatarChanged = true,
            cardMode = prefs.getString(KEY_CARD_MODE, "standard") ?: "standard",
            cardUri = prefs.getString(KEY_CARD_BG, null), cardChanged = true
        )
        prefs.edit().apply {
            if (avatar != null) putString(KEY_AVATAR, avatar) else remove(KEY_AVATAR)
            if (card != null) putString(KEY_CARD_BG, card) else remove(KEY_CARD_BG)
        }.apply()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun maxSide(kind: String) = if (kind == AVATAR) 512 else 1600
    private fun maxBytes(kind: String) = if (kind == AVATAR) 250_000 else 700_000

    private fun localDir(context: Context) = File(context.filesDir, "profile")

    /**
     * Copies an image into the app's storage as a compressed JPEG and returns
     * its file:// URI. Images already stored there are kept as they are.
     */
    private fun storeLocal(context: Context, source: Uri, kind: String): String? {
        val path = source.path
        if (source.scheme == "file" && path != null &&
            File(path).parentFile?.canonicalPath == localDir(context).canonicalPath &&
            File(path).exists()
        ) return source.toString()

        val bitmap = ImageLoader.load(context, source, maxSide(kind)) ?: return null
        val bytes = ImageCompress.toJpeg(bitmap, maxSide(kind), maxBytes(kind)) ?: return null
        return writeLocal(context, kind, bytes)
    }

    /** A new file name each time, so screens notice the image changed. */
    private fun writeLocal(context: Context, kind: String, bytes: ByteArray): String {
        val dir = localDir(context).apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("${kind}_") }?.forEach { it.delete() }
        val file = File(dir, "${kind}_${System.currentTimeMillis()}.jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private suspend fun uploadOrDelete(context: Context, uid: String, kind: String, localUri: String?): Boolean {
        return try {
            if (localUri == null) {
                imageDoc(uid, kind).delete().await()
            } else {
                val bytes = File(Uri.parse(localUri).path ?: return false).readBytes()
                imageDoc(uid, kind).set(
                    mapOf(
                        "userId" to uid,
                        "image" to Blob.fromBytes(bytes),
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "uploadOrDelete($kind): ${e.message}")
            false
        }
    }

    private suspend fun download(context: Context, uid: String, kind: String): String? {
        return try {
            val bytes = imageDoc(uid, kind).get().await().getBlob("image")?.toBytes() ?: return null
            writeLocal(context, kind, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "download($kind): ${e.message}")
            null
        }
    }
}
