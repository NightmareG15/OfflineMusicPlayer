package com.nightmare.offlinemusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Uma playlist: nome + ids das músicas (o "_ID" do MediaStore). */
class Playlist(var name: String, val ids: MutableList<Long>)

/** Guarda as escolhas do usuário (tema, aleatório, repetir, playlists, imagem de fundo). */
object Prefs {
    private const val NAME = "settings"
    const val SORT_NAME = 0
    const val SORT_ARTIST = 1
    const val SORT_RECENT = 2

    private fun p(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // Tema: o padrão é ESCURO.
    fun isDark(ctx: Context): Boolean = p(ctx).getBoolean("dark", true)
    fun setDark(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("dark", value).apply()

    // Pausar a música quando o fone for desconectado: o padrão é LIGADO.
    fun pauseOnUnplug(ctx: Context): Boolean = p(ctx).getBoolean("pause_on_unplug", true)
    fun setPauseOnUnplug(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("pause_on_unplug", value).apply()

    /**
     * Volta as configurações para o padrão (tema escuro, pausar ao desconectar o fone, ordem da lista).
     * Playlists, nomes personalizados e a última música tocada NÃO são apagados.
     */
    fun resetSettings(ctx: Context) {
        p(ctx).edit().remove("dark").remove("pause_on_unplug").remove("sort").apply()
    }

    fun shuffle(ctx: Context): Boolean = p(ctx).getBoolean("shuffle", false)
    fun setShuffle(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("shuffle", value).apply()

    /** 0 = desligado, 1 = repetir lista, 2 = repetir uma faixa */
    fun repeatMode(ctx: Context): Int = p(ctx).getInt("repeat", 0)
    fun setRepeatMode(ctx: Context, value: Int) = p(ctx).edit().putInt("repeat", value).apply()

    /** Ordem da lista: nome (padrão), artista ou mais recentes. */
    fun sortMode(ctx: Context): Int = p(ctx).getInt("sort", SORT_NAME)
    fun setSortMode(ctx: Context, value: Int) = p(ctx).edit().putInt("sort", value).apply()

    // ---- Continuar de onde parou ----

    /** Número (id) da última música tocada, ou -1. */
    fun lastId(ctx: Context): Long = p(ctx).getLong("last_id", -1L)
    fun lastPosition(ctx: Context): Int = p(ctx).getInt("last_pos", 0)

    /** Nome da playlist de onde a música estava tocando (nulo = "Todas as músicas"). */
    fun lastContext(ctx: Context): String? = p(ctx).getString("last_ctx", null)

    fun setLast(ctx: Context, id: Long, positionMs: Int, context: String?) {
        p(ctx).edit().putLong("last_id", id).putInt("last_pos", positionMs).putString("last_ctx", context).apply()
    }

    /** A música terminou sozinha: a próxima vez começa do início. */
    fun clearLastPosition(ctx: Context) = p(ctx).edit().putInt("last_pos", 0).apply()

    // ---- Nomes personalizados (só dentro do app; o arquivo da música não é alterado) ----

    fun loadCustomTitles(ctx: Context): HashMap<Long, String> {
        val result = HashMap<Long, String>()
        try {
            val obj = JSONObject(p(ctx).getString("custom_titles", "{}") ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toLongOrNull() ?: continue
                result[id] = obj.getString(key)
            }
        } catch (e: Exception) {
            // Dado corrompido: ignora e segue com os nomes originais.
        }
        return result
    }

    /** Guarda o nome escolhido para a música [id]; com [title] nulo, volta ao nome original. */
    fun setCustomTitle(ctx: Context, id: Long, title: String?) {
        val map = loadCustomTitles(ctx)
        if (title == null) map.remove(id) else map[id] = title
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key.toString(), value)
        p(ctx).edit().putString("custom_titles", obj.toString()).apply()
    }

    // ---- Playlists ----

    fun loadPlaylists(ctx: Context): MutableList<Playlist> {
        val result = mutableListOf<Playlist>()
        try {
            val array = JSONArray(p(ctx).getString("playlists", "[]") ?: "[]")
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
