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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import java.io.FileOutputStream

private data class Track(val id: Long, val title: String, val artist: String, val uri: Uri) {
    override fun toString(): String =
        if (artist.isBlank() || artist == "<unknown>") title else "$title  —  $artist"
}

class MainActivity : Activity() {
    private val allTracks = mutableListOf<Track>()   // todas as músicas do aparelho
    private val shown = mutableListOf<Track>()       // o que aparece na tela (tudo ou uma playlist)
    private var currentPlaylist: String? = null      // null = "Todas as músicas"
    private var playingId = -1L

    private lateinit var p: Ui.Palette
    private var dark = true

    private lateinit var adapter: ArrayAdapter<Track>
    private lateinit var list: ListView
    private lateinit var nowPlaying: TextView
    private lateinit var viewLabel: TextView
    private lateinit var playButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var wallpaperView: ImageView
    private lateinit var scrim: View

    private var musicService: MusicService? = null
    private var bound = false
    private var seeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (musicService?.isPlayingNow() == true) updateProgress()
            handler.postDelayed(this, 500)
        }
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
        applyWallpaper()
        refreshModeButtons()
        if (!hasAudioPermission()) requestPermissions(arrayOf(audioPermission()), REQ_AUDIO)
    }

    override fun onStart() {
        super.onStart()
        // Se o tema foi trocado nas configurações, reconstrói a tela com as novas cores.
        if (Prefs.isDark(this) != dark) { recreate(); return }
        Intent(this, MusicService::class.java).also { bindService(it, connection, Context.BIND_AUTO_CREATE) }
        if (hasAudioPermission()) loadTracks()   // a biblioteca se atualiza sozinha ao abrir o app
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
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

        val root = FrameLayout(this)
        root.setBackgroundColor(p.bg)

        wallpaperView = ImageView(this)
        wallpaperView.scaleType = ImageView.ScaleType.CENTER_CROP
        wallpaperView.visibility = View.GONE
        root.addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))

        // Película por cima da imagem de fundo, para o texto continuar legível.
        scrim = View(this)
        scrim.setBackgroundColor(Color.argb(0xD0, Color.red(p.bg), Color.green(p.bg), Color.blue(p.bg)))
        scrim.visibility = View.GONE
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(baseLeft, baseTop, baseRight, baseBottom)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        // Android 15+ desenha o app por baixo das barras do sistema:
        // somamos o tamanho das barras ao espaçamento para nada ficar escondido.
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
        viewLabel.setPadding(0, dp(2), 0, dp(10))

        nowPlaying = TextView(this)
        nowPlaying.text = "Escolha uma música para começar"
        nowPlaying.textSize = 15f
        nowPlaying.setTextColor(p.text)
        nowPlaying.setPadding(dp(12), dp(12), dp(12), dp(12))
        val cardShape = GradientDrawable()
        cardShape.cornerRadius = dp(12).toFloat()
        cardShape.setColor(p.surface)
        nowPlaying.background = cardShape

        // ---- Lista de músicas
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
        list.setOnItemClickListener { _, _, position, _ -> playTrack(position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position in shown.indices) showTrackMenu(shown[position])
            true
        }

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
                if (fromUser) timeCurrent.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                musicService?.seekTo(bar?.progress ?: 0)
                seeking = false
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
        content.addView(nowPlaying)
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        content.addView(seekRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setContentView(root)
    }

    /** Atualiza o texto da música atual, o botão tocar/pausar e o destaque na lista. */
    private fun render(title: String?, playing: Boolean) {
        nowPlaying.text = when {
            title == null -> if (shown.isEmpty()) emptyMessage() else "Escolha uma música para começar"
            playing -> "Tocando: $title"
            else -> "Pausado: $title"
        }
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playingId = musicService?.currentUri()?.let { Uri.parse(it).lastPathSegment?.toLongOrNull() } ?: -1L
        adapter.notifyDataSetChanged()
        updateProgress()
    }

    private fun emptyMessage(): String =
        if (currentPlaylist == null) "Nenhuma música encontrada no aparelho."
        else "Esta lista está vazia. Segure o dedo numa música para adicionar."

    private fun updateProgress() {
        val service = musicService ?: return
        if (seeking) return
        val duration = service.durationMs()
        val position = service.positionMs()
        val max = if (duration > 0) duration else 1
        if (seek.max != max) seek.max = max
        seek.progress = if (duration > 0) position else 0
        val current = formatTime(position)
        if (timeCurrent.text.toString() != current) timeCurrent.text = current
        val total = formatTime(duration)
        if (timeTotal.text.toString() != total) timeTotal.text = total
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

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

    private var loading = false

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
                    rebuildShown()
                }
            }
        }.start()
    }

    private fun queryTracks(): List<Track> {
        val result = ArrayList<Track>()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val cursor: Cursor? = try {
            contentResolver.query(collection, projection, selection, null, sort)
        } catch (e: Exception) {
            null
        }
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol) ?: "Faixa sem título"
                val artist = it.getString(artistCol) ?: ""
                result.add(Track(id, title, artist, Uri.withAppendedPath(collection, id.toString())))
            }
        }
        return result
    }

    /** Monta a lista que aparece na tela: todas as músicas ou só as da playlist escolhida. */
    private fun rebuildShown() {
        shown.clear()
        val name = currentPlaylist
        val playlist = if (name == null) null else Prefs.loadPlaylists(this).firstOrNull { it.name == name }
        if (playlist == null) {
            currentPlaylist = null
            shown.addAll(allTracks)
        } else {
            val byId = allTracks.associateBy { it.id }
            for (id in playlist.ids) byId[id]?.let { shown.add(it) }
        }
        adapter.notifyDataSetChanged()
        viewLabel.text = currentPlaylist?.let { "Lista: $it  (${shown.size})" } ?: "Todas as músicas  (${shown.size})"
        if (musicService?.currentTitle() == null) nowPlaying.text = if (shown.isEmpty()) emptyMessage() else "Escolha uma música para começar"
    }

    private fun playTrack(position: Int) {
        if (position !in shown.indices) return
        val service = musicService
        if (service == null) {
            Toast.makeText(this, "Aguarde um instante e tente de novo.", Toast.LENGTH_SHORT).show()
            return
        }
        service.setQueue(
            shown.map { it.uri.toString() },
            shown.map { it.title },
            shown.map { it.artist },
            position
        )
        nowPlaying.text = "Carregando: ${shown[position].title}"
    }

    // ------------------------------------------------------------------ playlists

    private fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this, Ui.dialogTheme(p))

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
        rebuildShown()
    }

    private fun removeFromCurrentPlaylist(trackId: Long) {
        val name = currentPlaylist ?: return
        val lists = Prefs.loadPlaylists(this)
        lists.firstOrNull { it.name == name }?.ids?.remove(trackId)
        Prefs.savePlaylists(this, lists)
        rebuildShown()
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
                    which == 0 -> { currentPlaylist = null; rebuildShown() }
                    which == labels.size - 1 -> askName("Nova lista", "") { name ->
                        if (createPlaylist(name)) { currentPlaylist = name; rebuildShown() }
                    }
                    else -> { currentPlaylist = lists[which - 1].name; rebuildShown() }
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
                            rebuildShown()
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
                            rebuildShown()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showTrackMenu(track: Track) {
        val inPlaylist = currentPlaylist != null
        val items = if (inPlaylist) arrayOf("Adicionar a outra lista", "Remover desta lista") else arrayOf("Adicionar a uma lista")
        dialog()
            .setTitle(track.title)
            .setItems(items) { _, which ->
                if (which == 0) chooseListFor(track) else removeFromCurrentPlaylist(track.id)
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
            startActivityForResult(intent, REQ_WALLPAPER)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app de galeria encontrado.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WALLPAPER && resultCode == RESULT_OK) {
            data?.data?.let { importWallpaper(it) }
        }
    }

    /** Copia a imagem escolhida para dentro do app, já reduzida ao tamanho da tela (poupa memória). */
    private fun importWallpaper(uri: Uri) {
        val metrics = resources.displayMetrics
        Thread {
            var ok = false
            try {
                val screenW = metrics.widthPixels
                val screenH = metrics.heightPixels
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    // Escala necessária para a imagem cobrir a tela; decodifica já reduzida (potência de 2).
                    val cover = maxOf(screenW.toFloat() / bounds.outWidth, screenH.toFloat() / bounds.outHeight)
                    var sample = 1
                    while (cover * sample * 2 <= 1f) sample *= 2
                    val options = BitmapFactory.Options()
                    options.inSampleSize = sample
                    options.inPreferredConfig = Bitmap.Config.RGB_565
                    val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    if (decoded != null) {
                        // Recorta/ajusta exatamente ao tamanho da tela e guarda só essa versão pequena.
                        val target = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.RGB_565)
                        val scale = maxOf(screenW.toFloat() / decoded.width, screenH.toFloat() / decoded.height)
                        val matrix = Matrix()
                        matrix.setScale(scale, scale)
                        matrix.postTranslate((screenW - decoded.width * scale) / 2f, (screenH - decoded.height * scale) / 2f)
                        Canvas(target).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                        decoded.recycle()
                        FileOutputStream(Prefs.wallpaperFile(this)).use { target.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        target.recycle()
                        ok = true
                    }
                }
            } catch (e: Exception) {
                ok = false
            }
            runOnUiThread {
                if (ok) applyWallpaper() else Toast.makeText(this, "Não foi possível usar essa imagem.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun removeWallpaper() {
        Prefs.wallpaperFile(this).delete()
        applyWallpaper()
    }

    private fun applyWallpaper() {
        if (Prefs.hasWallpaper(this)) {
            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.RGB_565
            val bitmap = BitmapFactory.decodeFile(Prefs.wallpaperFile(this).path, options)
            if (bitmap != null) {
                wallpaperView.setImageBitmap(bitmap)
                wallpaperView.visibility = View.VISIBLE
                scrim.visibility = View.VISIBLE
                return
            }
        }
        wallpaperView.setImageDrawable(null)
        wallpaperView.visibility = View.GONE
        scrim.visibility = View.GONE
    }

    companion object {
        private const val REQ_AUDIO = 10
        private const val REQ_NOTIFICATIONS = 20
        private const val REQ_WALLPAPER = 30
    }
}
