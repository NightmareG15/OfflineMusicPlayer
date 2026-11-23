package com.nightmare.offlinemusic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.Normalizer

/**
 * Uma música da lista. [title] é o nome mostrado (pode ter sido renomeado só no app);
 * [original] é o título que está gravado no arquivo (nunca é alterado).
 */
private data class Track(val id: Long, val title: String, val artist: String, val original: String) {
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
    private lateinit var nowPlaying: TextView
    private lateinit var viewLabel: TextView
    private lateinit var playButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var wallpaperView: ImageView

    private var musicService: MusicService? = null
    private var bound = false
    private var seeking = false
    private var lastPosSec = -1
    private var lastDurSec = -1
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (musicService?.isPlayingNow() == true) updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val filterRunnable = Runnable {
        queryTokens = fold(query).split(' ').filter { it.isNotEmpty() }
        applyFilter()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as MusicService.LocalBinder).getService()
            musicService = service
            bound = true
            // Sempre que a música/estado mudar no serviço, atualiza a tela.
            service.onStateChanged = { title, playing -> runOnUiThread { render(title, playing) } }
            render(service.currentTitle(), service.isPlayingNow())
            refreshModeButtons()
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
        refreshModeButtons()
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
            musicService?.onStateChanged = null
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
                Toast.makeText(this, "Permissão necessária para ler suas músicas.", Toast.LENGTH_LONG).show()
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

    @Suppress("DEPRECATION")
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

        // Android 15+ desenha o app por baixo das barras do sistema (e o teclado também entra aqui):
        // somamos o tamanho delas ao espaçamento para nada ficar escondido.
        root.setOnApplyWindowInsetsListener { _, insets ->
            content.setPadding(
                baseLeft + insets.systemWindowInsetLeft,
                baseTop + insets.systemWindowInsetTop,
                baseRight + insets.systemWindowInsetRight,
                baseBottom + insets.systemWindowInsetBottom
            )
            insets
        }

        // ---- Topo: título + botões (listas, imagem de fundo, configurações)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val heading = TextView(this)
        heading.text = "Offline Music"
        heading.textSize = 22f
        heading.setTextColor(p.text)
        heading.setTypeface(null, Typeface.BOLD)
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val playlistsButton = Ui.iconButton(this, R.drawable.ic_playlist, p.text, "Listas")
        playlistsButton.setOnClickListener { showPlaylistsDialog() }
        val wallpaperButton = Ui.iconButton(this, R.drawable.ic_image, p.text, "Imagem de fundo")
        wallpaperButton.setOnClickListener { showWallpaperMenu() }
        val settingsButton = Ui.iconButton(this, R.drawable.ic_settings, p.text, "Configurações")
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        header.addView(playlistsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(wallpaperButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        viewLabel = TextView(this)
        viewLabel.textSize = 13f
        viewLabel.setTextColor(p.sub)
        viewLabel.setPadding(0, dp(2), 0, dp(8))

        // ---- Barra de pesquisa
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
        searchRow.addView(searchIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        searchRow.addView(clearSearch, LinearLayout.LayoutParams(dp(40), dp(40)))

        nowPlaying = TextView(this)
        nowPlaying.text = "Escolha uma música para começar"
        nowPlaying.textSize = 15f
        nowPlaying.setTextColor(p.text)
        nowPlaying.setPadding(dp(12), dp(12), dp(12), dp(12))
        val cardShape = GradientDrawable()
        cardShape.cornerRadius = dp(12).toFloat()
        cardShape.setColor(p.surface)
        nowPlaying.background = cardShape

        // ---- Lista de músicas (com mensagem para quando estiver vazia)
        adapter = object : ArrayAdapter<Track>(this, android.R.layout.simple_list_item_1, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as TextView
                val track = getItem(position)
                val isCurrent = track != null && track.id == playingId
                row.setTextColor(if (isCurrent) p.accent else p.text)
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

        // ---- Barra de progresso
        val seekRow = LinearLayout(this)
        seekRow.orientation = LinearLayout.HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        timeCurrent = TextView(this)
        timeCurrent.text = "0:00"
        timeCurrent.textSize = 12f
        timeCurrent.setTextColor(p.sub)
        timeTotal = TextView(this)
        timeTotal.text = "0:00"
        timeTotal.textSize = 12f
        timeTotal.setTextColor(p.sub)
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(p.accent)
        seek.thumbTintList = ColorStateList.valueOf(p.accent)
        seek.progressBackgroundTintList = ColorStateList.valueOf(p.sub)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = formatTime(progress / 1000)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                musicService?.seekTo(bar?.progress ?: 0)
                seeking = false
                lastPosSec = -1
            }
        })
        seekRow.addView(timeCurrent, LinearLayout.LayoutParams(-2, -2))
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(timeTotal, LinearLayout.LayoutParams(-2, -2))

        // ---- Controles: aleatório, anterior, tocar/pausar, próxima, repetir
        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER

        shuffleButton = Ui.iconButton(this, R.drawable.ic_shuffle, p.sub, "Aleatório")
        shuffleButton.setOnClickListener { toggleShuffle() }
        val previousButton = Ui.iconButton(this, R.drawable.ic_previous, p.text, "Anterior")
        previousButton.setOnClickListener { musicService?.previous() }
        playButton = Ui.iconButton(this, R.drawable.ic_play, Color.WHITE, "Tocar ou pausar")
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(p.accent)
        playButton.background = circle
        val playPad = dp(16)
        playButton.setPadding(playPad, playPad, playPad, playPad)
        playButton.setOnClickListener { musicService?.togglePlayback() }
        val nextButton = Ui.iconButton(this, R.drawable.ic_next, p.text, "Próxima")
        nextButton.setOnClickListener { musicService?.next() }
        repeatButton = Ui.iconButton(this, R.drawable.ic_repeat, p.sub, "Repetir")
        repeatButton.setOnClickListener { cycleRepeat() }

        fun slot(size: Int) = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(4), 0, dp(4), 0) }
        controls.addView(shuffleButton, slot(48))
        controls.addView(previousButton, slot(56))
        controls.addView(playButton, slot(68))
        controls.addView(nextButton, slot(56))
        controls.addView(repeatButton, slot(48))

        content.addView(header)
        content.addView(viewLabel)
        content.addView(searchRow, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(nowPlaying, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        content.addView(seekRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setContentView(root)
        content.requestFocus()   // o foco começa fora da caixa de pesquisa (o teclado não abre sozinho)
        refreshEmptyText()
    }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(searchInput.windowToken, 0)
        content.requestFocus()
    }

    /** Atualiza o texto da música atual, o botão tocar/pausar e o destaque na lista. */
    private fun render(title: String?, playing: Boolean) {
        nowPlaying.text = when {
            title == null -> "Escolha uma música para começar"
            playing -> "Tocando: $title"
            else -> "Pausado: $title"
        }
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playingId = musicService?.currentId() ?: -1L
        adapter.notifyDataSetChanged()
        lastPosSec = -1
        lastDurSec = -1
        updateProgress()
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

    private fun updateProgress() {
        val service = musicService ?: return
        if (seeking) return
        val duration = service.durationMs()
        val position = service.positionMs()
        val max = if (duration > 0) duration else 1
        if (seek.max != max) seek.max = max
        seek.progress = if (duration > 0) position else 0
        // Os textos só são refeitos quando o segundo muda (evita criar texto à toa a cada 0,5 s).
        val posSec = position / 1000
        if (posSec != lastPosSec) { lastPosSec = posSec; timeCurrent.text = formatTime(posSec) }
        val durSec = duration / 1000
        if (durSec != lastDurSec) { lastDurSec = durSec; timeTotal.text = formatTime(durSec) }
    }

    private fun formatTime(totalSeconds: Int): String =
        "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"

    // ------------------------------------------------------------------ aleatório e repetir

    private fun refreshModeButtons() {
        val shuffleOn = Prefs.shuffle(this)
        shuffleButton.setColorFilter(if (shuffleOn) p.accent else p.sub)
        val mode = Prefs.repeatMode(this)
        repeatButton.setImageResource(if (mode == MusicService.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatButton.setColorFilter(if (mode == MusicService.REPEAT_OFF) p.sub else p.accent)
    }

    private fun toggleShuffle() {
        val on = !Prefs.shuffle(this)
        val service = musicService
        if (service != null) service.setShuffle(on) else Prefs.setShuffle(this, on)
        refreshModeButtons()
        Toast.makeText(this, if (on) "Aleatório: ligado" else "Aleatório: desligado", Toast.LENGTH_SHORT).show()
    }

    private fun cycleRepeat() {
        val next = (Prefs.repeatMode(this) + 1) % 3
        val service = musicService
        if (service != null) service.setRepeat(next) else Prefs.setRepeatMode(this, next)
        refreshModeButtons()
        val text = when (next) {
            MusicService.REPEAT_ALL -> "Repetir: lista inteira"
            MusicService.REPEAT_ONE -> "Repetir: esta faixa"
            else -> "Repetir: desligado"
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ biblioteca

    private val titleOrder = Comparator<Track> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title) }

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
            MediaStore.Audio.Media.ARTIST
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val customTitles = Prefs.loadCustomTitles(this)
        val cursor: Cursor? = try {
            contentResolver.query(Media.collection(), projection, selection, null, null)
        } catch (e: Exception) {
            null
        }
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val original = it.getString(titleCol) ?: "Faixa sem título"
                val artist = it.getString(artistCol) ?: ""
                result.add(Track(id, customTitles[id] ?: original, artist, original))
            }
        }
        result.sortWith(titleOrder)
        return result
    }

    /** Define a base da lista (todas as músicas ou uma playlist) e aplica a pesquisa. */
    private fun rebuildBase() {
        val name = currentPlaylist
        val playlist = if (name == null) null else Prefs.loadPlaylists(this).firstOrNull { it.name == name }
        if (playlist == null) {
            currentPlaylist = null
            base = allTracks
        } else {
            val byId = HashMap<Long, Track>(allTracks.size * 2)
            for (t in allTracks) byId[t.id] = t
            val ordered = ArrayList<Track>(playlist.ids.size)
            for (id in playlist.ids) byId[id]?.let { ordered.add(it) }
            base = ordered
        }
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

    private fun playTrack(position: Int) {
        if (position !in shown.indices) return
        val service = musicService
        if (service == null) {
            Toast.makeText(this, "Aguarde um instante e tente de novo.", Toast.LENGTH_SHORT).show()
            return
        }
        val count = shown.size
        val ids = LongArray(count)
        val titles = ArrayList<String>(count)
        val artists = ArrayList<String>(count)
        for (i in 0 until count) {
            val t = shown[i]
            ids[i] = t.id
            titles.add(t.title)
            artists.add(t.artist)
        }
        service.setQueue(ids, titles, artists, position)
        nowPlaying.text = "Carregando: ${shown[position].title}"
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
        labels.add("Renomear (só no app)")
        actions.add { askName("Renomear música (só no app)", track.title) { name -> applyTitle(track, name) } }
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
            allTracks.sortWith(titleOrder)
        }
        musicService?.renameTrack(track.id, newTitle)
        rebuildBase()
        Toast.makeText(this, "Nome alterado só dentro do app.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Já existe uma lista com esse nome.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Essa música já está em \"$playlistName\".", Toast.LENGTH_SHORT).show()
            return
        }
        target.ids.add(trackId)
        Prefs.savePlaylists(this, lists)
        Toast.makeText(this, "Adicionada a \"$playlistName\".", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(this, "Já existe uma lista com esse nome.", Toast.LENGTH_SHORT).show()
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

    // ------------------------------------------------------------------ imagem de fundo

    private fun showWallpaperMenu() {
        val has = Prefs.hasWallpaper(this)
        val items = if (has) arrayOf("Escolher outra imagem", "Remover imagem") else arrayOf("Escolher imagem")
        dialog()
            .setTitle("Imagem de fundo")
            .setItems(items) { _, which ->
                if (which == 0) pickImage() else removeWallpaper()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app de galeria encontrado.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Depois do ajuste (REQ_CROP), a imagem nova é carregada em onStart.
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val intent = Intent(this, CropActivity::class.java)
            intent.data = uri
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(intent, REQ_CROP)
        }
    }

    private fun removeWallpaper() {
        Prefs.wallpaperFile(this).delete()
        applyWallpaper()
    }

    /** Mostra a imagem de fundo salva (já no tamanho da tela). A cor por cima mantém o texto legível. */
    private fun applyWallpaper() {
        freeWallpaper()
        if (Prefs.hasWallpaper(this)) {
            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.RGB_565
            val bitmap: Bitmap? = try {
                BitmapFactory.decodeFile(Prefs.wallpaperFile(this).path, options)
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bitmap != null) {
                val veil = Color.argb(0xD0, Color.red(p.bg), Color.green(p.bg), Color.blue(p.bg))
                wallpaperView.setImageBitmap(bitmap)
                wallpaperView.colorFilter = PorterDuffColorFilter(veil, PorterDuff.Mode.SRC_OVER)
                wallpaperView.visibility = View.VISIBLE
                root.background = null   // com imagem, o fundo liso não precisa ser desenhado
                return
            }
        }
        wallpaperView.visibility = View.GONE
        root.setBackgroundColor(p.bg)
    }

    private fun freeWallpaper() {
        wallpaperView.setImageDrawable(null)
    }

    companion object {
        private const val REQ_AUDIO = 10
        private const val REQ_NOTIFICATIONS = 20
        private const val REQ_PICK = 30
        private const val REQ_CROP = 31
    }
}
