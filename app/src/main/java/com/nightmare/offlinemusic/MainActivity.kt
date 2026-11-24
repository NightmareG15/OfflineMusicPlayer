package com.nightmare.offlinemusic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Normalizer

/**
 * Uma música da lista. [title] é o nome mostrado (pode ter sido renomeado dentro do app);
 * [original] é o título gravado no arquivo (nunca é alterado); [added] é a data em que foi adicionada ao aparelho.
 */
private data class Track(val id: Long, val title: String, val artist: String, val original: String, val added: Long) {
    override fun toString(): String =
        if (artist.isBlank() || artist == "<unknown>") title else "$title  —  $artist"
}

class MainActivity : Activity() {
    private val allTracks = ArrayList<Track>()         // todas as músicas do aparelho
    private var base: List<Track> = allTracks          // "Todas as músicas" ou as músicas da playlist escolhida
    private val shown = ArrayList<Track>()             // o que aparece na tela (base + pesquisa)
    private var currentPlaylist: String? = null        // null = "Todas as músicas"
    private var playingId = -1L
    private var loadedOnce = false
    private var loading = false

    // Pesquisa
    private var query = ""
    private var queryTokens: List<String> = emptyList()

    private lateinit var p: Ui.Palette
    private var dark = true

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var adapter: ArrayAdapter<Track>
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var searchInput: EditText
    private lateinit var clearSearch: ImageButton
    private lateinit var timerButton: ImageButton
    private lateinit var nowPlaying: TextView
    private lateinit var viewLabel: TextView
    private lateinit var panel: PlayerPanel
    private lateinit var wallpaperView: ImageView

    private var musicService: MusicService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (musicService?.isPlayingNow() == true) panel.updateProgress()
            SleepTimerDialog.refreshIcon(timerButton, p, musicService)
            handler.postDelayed(this, 1000)
        }
    }

    private val filterRunnable = Runnable {
        queryTokens = fold(query).split(' ').filter { it.isNotEmpty() }
        applyFilter()
    }

    private val stateListener: (String?, Boolean) -> Unit = { title, playing ->
        runOnUiThread { render(title, playing) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as MusicService.LocalBinder).getService()
            musicService = service
            bound = true
            // Sempre que a música/estado mudar no serviço, atualiza a tela.
            service.onStateChanged = stateListener
            render(service.currentTitle(), service.isPlayingNow())
            panel.refreshModeButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            bound = false
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Prefs.isDark(this)
        p = Ui.palette(this)
        buildInterface()
        Ui.applySystemBars(this, p)
        if (!hasAudioPermission()) requestPermissions(arrayOf(audioPermission()), REQ_AUDIO)
    }

    override fun onStart() {
        super.onStart()
        // Se o tema foi trocado nas configurações, reconstrói a tela com as novas cores.
        if (Prefs.isDark(this) != dark) { recreate(); return }
        applyWallpaper()   // a imagem de fundo só fica na memória enquanto o app está visível
        Intent(this, MusicService::class.java).also { bindService(it, connection, Context.BIND_AUTO_CREATE) }
        if (hasAudioPermission()) loadTracks()   // a biblioteca se atualiza sozinha ao abrir o app
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(filterRunnable)
        freeWallpaper()
        if (bound) {
            musicService?.clearListener(stateListener)
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                loadTracks()
                requestNotificationPermissionIfNeeded()
            } else {
                Ui.toast(this, "Permissão necessária para ler suas músicas.", long = true)
                refreshEmptyText()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------ tela

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun buildInterface() {
        val baseLeft = dp(16)
        val baseTop = dp(12)
        val baseRight = dp(16)
        val baseBottom = dp(12)

        root = FrameLayout(this)
        root.setBackgroundColor(p.bg)

        wallpaperView = ImageView(this)
        wallpaperView.scaleType = ImageView.ScaleType.CENTER_CROP
        wallpaperView.visibility = View.GONE
        root.addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(baseLeft, baseTop, baseRight, baseBottom)
        // Permite tirar o foco da caixa de pesquisa (e esconder o teclado) sem "pular" para ela de novo.
        content.isFocusable = true
        content.isFocusableInTouchMode = true
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        Ui.padForSystemBars(root, content, baseLeft, baseTop, baseRight, baseBottom)

        // ---- Topo: título + botões (listas, timer, configurações)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val heading = TextView(this)
        heading.text = getString(R.string.app_name)
        heading.textSize = 22f
        heading.setTextColor(p.text)
        heading.setTypeface(null, Typeface.BOLD)
        heading.maxLines = 1
        heading.ellipsize = TextUtils.TruncateAt.END
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val playlistsButton = Ui.iconButton(this, R.drawable.ic_playlist, p.text, "Listas")
        playlistsButton.setOnClickListener { showPlaylistsDialog() }
        timerButton = Ui.iconButton(this, R.drawable.ic_timer, p.text, "Timer para dormir")
        timerButton.setOnClickListener {
            SleepTimerDialog.show(this, p, musicService) { SleepTimerDialog.refreshIcon(timerButton, p, musicService) }
        }
        val settingsButton = Ui.iconButton(this, R.drawable.ic_settings, p.text, "Configurações")
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        header.addView(playlistsButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(timerButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(42), dp(42)))

        viewLabel = TextView(this)
        viewLabel.textSize = 13f
        viewLabel.setTextColor(p.sub)
        viewLabel.setPadding(0, dp(2), 0, dp(8))

        // ---- Barra de pesquisa (com botão de ordenar)
        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchRow.setPadding(dp(12), 0, dp(2), 0)
        val searchShape = GradientDrawable()
        searchShape.cornerRadius = dp(12).toFloat()
        searchShape.setColor(p.surface)
        searchRow.background = searchShape
        val searchIcon = ImageView(this)
        searchIcon.setImageResource(R.drawable.ic_search)
        searchIcon.setColorFilter(p.sub)
        searchInput = EditText(this)
        searchInput.background = null
        searchInput.hint = "Pesquisar músicas"
        searchInput.setHintTextColor(p.sub)
        searchInput.setTextColor(p.text)
        searchInput.textSize = 15f
        searchInput.setSingleLine()
        searchInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        searchInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                // Espera um instante após a última tecla antes de filtrar (poupa processamento).
                handler.removeCallbacks(filterRunnable)
                handler.postDelayed(filterRunnable, 180)
            }
        })
        clearSearch = Ui.iconButton(this, R.drawable.ic_close, p.sub, "Limpar pesquisa")
        clearSearch.visibility = View.GONE
        clearSearch.setOnClickListener { searchInput.setText("") }
        val sortButton = Ui.iconButton(this, R.drawable.ic_sort, p.sub, "Ordenar")
        sortButton.setOnClickListener { showSortDialog() }
        searchRow.addView(searchIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        searchRow.addView(clearSearch, LinearLayout.LayoutParams(dp(40), dp(40)))
        searchRow.addView(sortButton, LinearLayout.LayoutParams(dp(40), dp(40)))

        nowPlaying = TextView(this)
        nowPlaying.text = "Escolha uma música para começar"
        nowPlaying.textSize = 15f
        nowPlaying.setTextColor(p.text)
        nowPlaying.setPadding(dp(12), dp(12), dp(12), dp(12))
        val cardShape = GradientDrawable()
        cardShape.cornerRadius = dp(12).toFloat()
        cardShape.setColor(p.surface)
        nowPlaying.background = cardShape
        nowPlaying.setOnClickListener {
            if (musicService?.currentTitle() != null) startActivity(Intent(this, NowPlayingActivity::class.java))
        }

        // ---- Lista de músicas (com mensagem para quando estiver vazia)
        adapter = object : ArrayAdapter<Track>(this, android.R.layout.simple_list_item_1, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as TextView
                val track = getItem(position)
                val isCurrent = track != null && track.id == playingId
                row.setTextColor(if (isCurrent) p.highlight else p.text)
                row.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                row.setBackgroundColor(Color.TRANSPARENT)
                return row
            }
        }
        list = ListView(this)
        list.adapter = adapter
        list.divider = ColorDrawable(p.surface)
        list.dividerHeight = dp(1)
        list.setOnItemClickListener { _, _, position, _ ->
            hideKeyboard()
            playTrack(position)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position in shown.indices) showTrackMenu(shown[position])
            true
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {}
        })
        emptyText = TextView(this)
        emptyText.textSize = 14f
        emptyText.gravity = Gravity.CENTER
        emptyText.setTextColor(p.sub)
        emptyText.setPadding(dp(24), dp(24), dp(24), dp(24))
        list.emptyView = emptyText
        val listFrame = FrameLayout(this)
        listFrame.addView(list, FrameLayout.LayoutParams(-1, -1))
        listFrame.addView(emptyText, FrameLayout.LayoutParams(-1, -1))

        panel = PlayerPanel(this, p, { musicService }, { onPlayPressed() })

        content.addView(header)
        content.addView(viewLabel)
        content.addView(searchRow, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        content.addView(nowPlaying, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(panel.seekRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(panel.controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setContentView(root)
        content.requestFocus()   // o foco começa fora da caixa de pesquisa (o teclado não abre sozinho)
        refreshEmptyText()
    }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(searchInput.windowToken, 0)
        content.requestFocus()
    }

    /** Texto do cartão quando nada está tocando: oferece continuar a última música, se houver. */
    private fun idleText(): String {
        val lastId = Prefs.lastId(this)
        if (loadedOnce && lastId >= 0) {
            val track = allTracks.firstOrNull { it.id == lastId }
            if (track != null) {
                val seconds = Prefs.lastPosition(this) / 1000
                return if (seconds > 0) "Continuar: ${track.title} (${Ui.formatTime(seconds)})" else "Continuar: ${track.title}"
            }
        }
        return "Escolha uma música para começar"
    }

    /** Atualiza o texto da música atual, o botão tocar/pausar e o destaque na lista. */
    private fun render(title: String?, playing: Boolean) {
        nowPlaying.text = when {
            title == null -> idleText()
            playing -> "Tocando: $title"
            else -> "Pausado: $title"
        }
        panel.render(playing)
        val newId = musicService?.currentId() ?: -1L
        if (newId != playingId) {
            playingId = newId
            adapter.notifyDataSetChanged()   // só redesenha a lista quando a música destacada muda
        }
        SleepTimerDialog.refreshIcon(timerButton, p, musicService)
    }

    private fun refreshEmptyText() {
        emptyText.text = when {
            !hasAudioPermission() -> "Permita o acesso às músicas para ver sua biblioteca."
            !loadedOnce -> "Carregando músicas..."
            queryTokens.isNotEmpty() -> "Nenhuma música encontrada para \"$query\"."
            currentPlaylist != null -> "Esta lista está vazia. Segure o dedo numa música para adicioná-la."
            else -> "Nenhuma música encontrada no aparelho."
        }
    }


    // ------------------------------------------------------------------ tocar, continuar e controles

    private fun onPlayPressed() {
        val service = musicService ?: return
        if (service.currentTitle() != null) service.togglePlayback() else resumeLast()
    }

    /** Monta a fila com [list] e começa a tocar a música [index], a partir de [positionMs]. */
    private fun startQueue(list: List<Track>, index: Int, positionMs: Int, context: String?) {
        val service = musicService
        if (service == null) {
            Ui.toast(this, "Aguarde um instante e tente de novo.")
            return
        }
        val count = list.size
        val ids = LongArray(count)
        val titles = ArrayList<String>(count)
        val artists = ArrayList<String>(count)
        for (i in 0 until count) {
            val t = list[i]
            ids[i] = t.id
            titles.add(t.title)
            artists.add(t.artist)
        }
        service.setQueue(ids, titles, artists, index, positionMs, context)
        nowPlaying.text = "Carregando: ${list[index].title}"
    }

    private fun playTrack(position: Int) {
        if (position !in shown.indices) return
        startQueue(shown, position, 0, currentPlaylist)
    }

    /** "Continuar de onde parou": volta à última música, no ponto em que estava. */
    private fun resumeLast() {
        val lastId = Prefs.lastId(this)
        var context = Prefs.lastContext(this)
        var list: List<Track> = tracksOf(context) ?: allTracks
        var index = if (lastId >= 0) list.indexOfFirst { it.id == lastId } else -1
        var position = Prefs.lastPosition(this)
        if (index < 0 && list !== allTracks) {
            list = allTracks
            context = null
            index = allTracks.indexOfFirst { it.id == lastId }
        }
        if (index < 0) {
            // Nada salvo (ou a música foi apagada do aparelho): começa pela primeira da lista na tela.
            if (shown.isEmpty()) return
            list = shown
            context = currentPlaylist
            index = 0
            position = 0
        }
        startQueue(list, index, position, context)
    }



    // ------------------------------------------------------------------ biblioteca e ordem da lista

    private fun currentOrder(): Comparator<Track> {
        val byTitle = Comparator<Track> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }
        return when (Prefs.sortMode(this)) {
            Prefs.SORT_ARTIST -> Comparator { a, b ->
                val aUnknown = a.artist.isBlank() || a.artist == "<unknown>"
                val bUnknown = b.artist.isBlank() || b.artist == "<unknown>"
                if (aUnknown != bUnknown) {
                    if (aUnknown) 1 else -1   // músicas sem artista ficam no fim
                } else {
                    val byArtist = String.CASE_INSENSITIVE_ORDER.compare(a.artist, b.artist)
                    if (byArtist != 0) byArtist else byTitle.compare(a, b)
                }
            }
            Prefs.SORT_RECENT -> Comparator { a, b ->
                val byDate = b.added.compareTo(a.added)   // mais novas primeiro
                if (byDate != 0) byDate else byTitle.compare(a, b)
            }
            else -> byTitle
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf("Nome (A–Z)", "Artista (A–Z)", "Mais recentes")
        dialog()
            .setTitle("Ordenar por")
            .setSingleChoiceItems(labels, Prefs.sortMode(this)) { dlg, which ->
                Prefs.setSortMode(this, which)
                allTracks.sortWith(currentOrder())
                rebuildBase()
                dlg.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadTracks() {
        if (loading) return
        loading = true
        Thread {
            val result = queryTracks()
            runOnUiThread {
                loading = false
                if (!isDestroyed) {
                    allTracks.clear()
                    allTracks.addAll(result)
                    loadedOnce = true
                    rebuildBase()
                    if (musicService?.currentTitle() == null) nowPlaying.text = idleText()
                }
            }
        }.start()
    }

    /** Lê as músicas do aparelho (roda fora da tela principal, para não travar). */
    private fun queryTracks(): ArrayList<Track> {
        val result = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val customTitles = Prefs.loadCustomTitles(this)
        val artistPool = HashMap<String, String>()   // artistas repetidos passam a compartilhar uma só String
        val cursor: Cursor? = try {
            contentResolver.query(Media.collection(), projection, selection, null, null)
        } catch (e: Exception) {
            null
        }
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val addedCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val original = it.getString(titleCol) ?: "Faixa sem título"
                val rawArtist = it.getString(artistCol) ?: ""
                val artist = artistPool.getOrPut(rawArtist) { rawArtist }
                result.add(Track(id, customTitles[id] ?: original, artist, original, it.getLong(addedCol)))
            }
        }
        result.sortWith(currentOrder())
        return result
    }

    /** As músicas de uma playlist (na ordem em que foram adicionadas), ou todas se [name] for nulo. Nulo se a lista não existir. */
    private fun tracksOf(name: String?): List<Track>? {
        if (name == null) return allTracks
        val playlist = Prefs.loadPlaylists(this).firstOrNull { it.name == name } ?: return null
        val byId = HashMap<Long, Track>(allTracks.size * 2)
        for (t in allTracks) byId[t.id] = t
        val ordered = ArrayList<Track>(playlist.ids.size)
        for (id in playlist.ids) byId[id]?.let { ordered.add(it) }
        return ordered
    }

    /** Define a base da lista (todas as músicas ou uma playlist) e aplica a pesquisa. */
    private fun rebuildBase() {
        val tracks = tracksOf(currentPlaylist)
        if (tracks == null) currentPlaylist = null
        base = tracks ?: allTracks
        applyFilter()
    }

    /** Aplica a pesquisa sobre a base. Cada palavra digitada precisa aparecer no nome, no artista ou no nome original. */
    private fun applyFilter() {
        shown.clear()
        if (queryTokens.isEmpty()) {
            shown.addAll(base)
        } else {
            for (t in base) if (matches(t)) shown.add(t)
        }
        adapter.notifyDataSetChanged()
        val count = if (queryTokens.isEmpty()) "${base.size}" else "${shown.size} de ${base.size}"
        viewLabel.text = (currentPlaylist?.let { "Lista: $it" } ?: "Todas as músicas") + "  ($count)"
        refreshEmptyText()
    }

    private fun matches(t: Track): Boolean {
        val a = prepared(t.title)
        val b = prepared(t.artist)
        val c = if (t.original !== t.title) prepared(t.original) else ""
        for (token in queryTokens) {
            if (!(a.contains(token, ignoreCase = true) || b.contains(token, ignoreCase = true) || c.contains(token, ignoreCase = true))) {
                return false
            }
        }
        return true
    }

    /** Texto só com letras ASCII usa a própria string (sem criar cópia); com acentos, tira os acentos. */
    private fun prepared(text: String): String {
        for (ch in text) if (ch.code >= 128) return fold(text)
        return text
    }

    /** Minúsculas e sem acentos: "Canção" vira "cancao". */
    private fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ menu da música (segurar o dedo)

    private fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this, Ui.dialogTheme(p))

    private fun showTrackMenu(track: Track) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val inPlaylist = currentPlaylist != null
        labels.add(if (inPlaylist) "Adicionar a outra lista" else "Adicionar a uma lista")
        actions.add { chooseListFor(track) }
        if (inPlaylist) {
            labels.add("Remover desta lista")
            actions.add { removeFromCurrentPlaylist(track.id) }
        }
        labels.add("Renomear")
        actions.add { askName("Renomear música", track.title) { name -> applyTitle(track, name) } }
        if (track.title != track.original) {
            labels.add("Restaurar nome original")
            actions.add { applyTitle(track, null) }
        }
        dialog()
            .setTitle(track.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Troca o nome mostrado no app. O arquivo da música NÃO é alterado. Com [newName] nulo, volta ao original. */
    private fun applyTitle(track: Track, newName: String?) {
        val newTitle = newName ?: track.original
        Prefs.setCustomTitle(this, track.id, if (newName == null || newName == track.original) null else newName)
        val index = allTracks.indexOfFirst { it.id == track.id }
        if (index >= 0) {
            allTracks[index] = allTracks[index].copy(title = newTitle)
            allTracks.sortWith(currentOrder())
        }
        musicService?.renameTrack(track.id, newTitle)
        rebuildBase()
        Ui.toast(this, if (newName == null) "Nome original restaurado." else "Música renomeada.")
    }

    // ------------------------------------------------------------------ playlists

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val themed = ContextThemeWrapper(this, Ui.dialogTheme(p))
        val input = EditText(themed)
        input.setSingleLine()
        input.setText(initial)
        input.setSelection(input.text.length)
        val frame = FrameLayout(themed)
        frame.setPadding(dp(20), dp(8), dp(20), 0)
        frame.addView(input, FrameLayout.LayoutParams(-1, -2))
        dialog()
            .setTitle(title)
            .setView(frame)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createPlaylist(name: String): Boolean {
        val lists = Prefs.loadPlaylists(this)
        if (lists.any { it.name.equals(name, ignoreCase = true) }) {
            Ui.toast(this, "Já existe uma lista com esse nome.")
            return false
        }
        lists.add(Playlist(name, mutableListOf()))
        Prefs.savePlaylists(this, lists)
        return true
    }

    private fun addToPlaylist(playlistName: String, trackId: Long) {
        val lists = Prefs.loadPlaylists(this)
        val target = lists.firstOrNull { it.name == playlistName } ?: return
        if (target.ids.contains(trackId)) {
            Ui.toast(this, "Essa música já está em \"$playlistName\".")
            return
        }
        target.ids.add(trackId)
        Prefs.savePlaylists(this, lists)
        Ui.toast(this, "Adicionada a \"$playlistName\".")
        rebuildBase()
    }

    private fun removeFromCurrentPlaylist(trackId: Long) {
        val name = currentPlaylist ?: return
        val lists = Prefs.loadPlaylists(this)
        lists.firstOrNull { it.name == name }?.ids?.remove(trackId)
        Prefs.savePlaylists(this, lists)
        rebuildBase()
    }

    private fun showPlaylistsDialog() {
        val lists = Prefs.loadPlaylists(this)
        val labels = ArrayList<String>()
        labels.add("Todas as músicas")
        for (pl in lists) labels.add("${pl.name}  (${pl.ids.size})")
        labels.add("+ Nova lista")
        val dlg = dialog()
            .setTitle("Listas (segure para editar)")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> { currentPlaylist = null; rebuildBase() }
                    which == labels.size - 1 -> askName("Nova lista", "") { name ->
                        if (createPlaylist(name)) { currentPlaylist = name; rebuildBase() }
                    }
                    else -> { currentPlaylist = lists[which - 1].name; rebuildBase() }
                }
            }
            .setNegativeButton("Fechar", null)
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position >= 1 && position <= lists.size) {
                dlg.dismiss()
                manageList(lists[position - 1])
            }
            true
        }
    }

    private fun manageList(playlist: Playlist) {
        dialog()
            .setTitle(playlist.name)
            .setItems(arrayOf("Renomear", "Excluir")) { _, which ->
                if (which == 0) {
                    askName("Renomear lista", playlist.name) { newName ->
                        val lists = Prefs.loadPlaylists(this)
                        val clash = lists.any { it.name.equals(newName, ignoreCase = true) && it.name != playlist.name }
                        if (clash) {
                            Ui.toast(this, "Já existe uma lista com esse nome.")
                        } else {
                            lists.firstOrNull { it.name == playlist.name }?.name = newName
                            Prefs.savePlaylists(this, lists)
                            if (currentPlaylist == playlist.name) currentPlaylist = newName
                            rebuildBase()
                        }
                    }
                } else {
                    dialog()
                        .setTitle("Excluir \"${playlist.name}\"?")
                        .setMessage("As músicas continuam no aparelho; só a lista será apagada.")
                        .setPositiveButton("Excluir") { _, _ ->
                            val lists = Prefs.loadPlaylists(this)
                            lists.removeAll { it.name == playlist.name }
                            Prefs.savePlaylists(this, lists)
                            if (currentPlaylist == playlist.name) currentPlaylist = null
                            rebuildBase()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseListFor(track: Track) {
        val lists = Prefs.loadPlaylists(this)
        val labels = ArrayList<String>()
        for (pl in lists) labels.add(pl.name)
        labels.add("+ Nova lista")
        dialog()
            .setTitle("Adicionar a...")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == lists.size) {
                    askName("Nova lista", "") { name -> if (createPlaylist(name)) addToPlaylist(name, track.id) }
                } else {
                    addToPlaylist(lists[which].name, track.id)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ imagem de fundo (fixa, vem dentro do app)

    /** Mostra o fundo padrão. A cor por cima (mais leve no tema escuro) mantém o texto legível. */
    private fun applyWallpaper() {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.RGB_565
        val bitmap: Bitmap? = try {
            BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default, options)
        } catch (e: OutOfMemoryError) {
            null
        }
        if (bitmap != null) {
            val alpha = if (p.dark) 0x94 else 0xD9   // ~58% no escuro, ~85% no claro
            val veil = Color.argb(alpha, Color.red(p.bg), Color.green(p.bg), Color.blue(p.bg))
            wallpaperView.setImageBitmap(bitmap)
            wallpaperView.colorFilter = PorterDuffColorFilter(veil, PorterDuff.Mode.SRC_OVER)
            wallpaperView.visibility = View.VISIBLE
            root.background = null   // com imagem, o fundo liso não precisa ser desenhado
        } else {
            wallpaperView.visibility = View.GONE
            root.setBackgroundColor(p.bg)
        }
    }

    private fun freeWallpaper() {
        wallpaperView.setImageDrawable(null)
    }

    companion object {
        private const val REQ_AUDIO = 10
        private const val REQ_NOTIFICATIONS = 20
    }
}
