package com.nightmare.offlinemusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma playlist: nome + ids das músicas (o "_ID" do MediaStore). */
class Playlist(var name: String, val ids: MutableList<Long>)

/** Guarda as escolhas do usuário (tema, aleatório, repetir, playlists, imagem de fundo). */
object Prefs {
    private const val NAME = "settings"
    const val WALLPAPER_FILE = "wallpaper.jpg"

    private fun p(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // Tema: o padrão é ESCURO.
    fun isDark(ctx: Context): Boolean = p(ctx).getBoolean("dark", true)
    fun setDark(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("dark", value).apply()

    fun shuffle(ctx: Context): Boolean = p(ctx).getBoolean("shuffle", false)
    fun setShuffle(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("shuffle", value).apply()

    /** 0 = desligado, 1 = repetir lista, 2 = repetir uma faixa */
    fun repeatMode(ctx: Context): Int = p(ctx).getInt("repeat", 0)
    fun setRepeatMode(ctx: Context, value: Int) = p(ctx).edit().putInt("repeat", value).apply()

    fun wallpaperFile(ctx: Context): File = File(ctx.filesDir, WALLPAPER_FILE)
    fun hasWallpaper(ctx: Context): Boolean = wallpaperFile(ctx).exists()

    // ---- Playlists ----

    fun loadPlaylists(ctx: Context): MutableList<Playlist> {
        val result = mutableListOf<Playlist>()
        try {
            val array = JSONArray(p(ctx).getString("playlists", "[]"))
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val idsJson = obj.getJSONArray("ids")
                val ids = mutableListOf<Long>()
                for (j in 0 until idsJson.length()) ids.add(idsJson.getLong(j))
                result.add(Playlist(obj.getString("name"), ids))
            }
        } catch (e: Exception) {
            // Dado corrompido: começa com a lista vazia em vez de travar o app.
        }
        return result
    }

    fun savePlaylists(ctx: Context, lists: List<Playlist>) {
        val array = JSONArray()
        for (pl in lists) {
            val ids = JSONArray()
            for (id in pl.ids) ids.put(id)
            array.put(JSONObject().put("name", pl.name).put("ids", ids))
        }
        p(ctx).edit().putString("playlists", array.toString()).apply()
    }
}
