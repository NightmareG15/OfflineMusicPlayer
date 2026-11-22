package com.nightmare.offlinemusic

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

private data class Track(val title: String, val artist: String, val uri: Uri) {
    override fun toString(): String =
        if (artist.isBlank() || artist == "<unknown>") title else "$title  —  $artist"
}

class MainActivity : Activity() {
    private val tracks = mutableListOf<Track>()
    private lateinit var adapter: ArrayAdapter<Track>
    private lateinit var list: ListView
    private lateinit var nowPlaying: TextView
    private lateinit var playButton: Button
    private var musicService: MusicService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as MusicService.LocalBinder).getService()
            musicService = service
            bound = true
            // Sempre que a música/estado mudar no serviço, atualiza a tela.
            service.onStateChanged = { title, playing -> runOnUiThread { render(title, playing) } }
            render(service.currentTitle(), service.isPlayingNow())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            loadTracks()
            requestNotificationPermissionIfNeeded()
        } else {
            requestPermissions(arrayOf(permission), 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
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
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildInterface() {
        val baseLeft = dp(18)
        val baseTop = dp(22)
        val baseRight = dp(18)
        val baseBottom = dp(14)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseLeft, baseTop, baseRight, baseBottom)
            setBackgroundColor(0xFFF7F6FA.toInt())
        }
        // Android 15+ desenha o app por baixo da barra de status/navegação:
        // somamos o tamanho das barras ao espaçamento para nada ficar escondido.
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                baseLeft + insets.systemWindowInsetLeft,
                baseTop + insets.systemWindowInsetTop,
                baseRight + insets.systemWindowInsetRight,
                baseBottom + insets.systemWindowInsetBottom
            )
            insets
        }

        val heading = TextView(this).apply {
            text = "Offline Music"
            textSize = 26f
            setTextColor(0xFF211D2B.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Suas músicas. Sem internet. Sem complicação."
            textSize = 13f
            setTextColor(0xFF77717F.toInt())
            setPadding(0, dp(4), 0, dp(14))
        }
        nowPlaying = TextView(this).apply {
            text = "Escolha uma música para começar"
            textSize = 15f
            setTextColor(0xFF302A3B.toInt())
            setPadding(dp(12), dp(13), dp(12), dp(13))
            setBackgroundColor(0xFFECE8F5.toInt())
        }
        val refresh = Button(this).apply {
            text = "Atualizar biblioteca"
            setOnClickListener { loadTracks() }
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, tracks)
        list = ListView(this).apply {
            dividerHeight = dp(1)
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> playTrack(position) }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = Button(this).apply {
            text = "Anterior"
            setOnClickListener { musicService?.previous() }
        }
        playButton = Button(this).apply {
            text = "Tocar"
            setOnClickListener { musicService?.togglePlayback() }
        }
        val next = Button(this).apply {
            text = "Próxima"
            setOnClickListener { musicService?.next() }
        }
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(playButton, LinearLayout.LayoutParams(0, dp(52), 1.2f))
        controls.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f))

        root.addView(heading)
        root.addView(subtitle)
        root.addView(nowPlaying)
        root.addView(refresh)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(controls)
        setContentView(root)
    }

    /** Atualiza o texto da música atual e o botão Tocar/Pausar. */
    private fun render(title: String?, playing: Boolean) {
        nowPlaying.text = when {
            title == null -> if (tracks.isEmpty()) "Nenhuma música encontrada no aparelho." else "Escolha uma música para começar"
            playing -> "Tocando: $title"
            else -> "Pausado: $title"
        }
        playButton.text = if (playing) "Pausar" else "Tocar"
    }

    private fun loadTracks() {
        tracks.clear()
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
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, sort)
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val title = it.getString(titleCol) ?: "Faixa sem título"
                val artist = it.getString(artistCol) ?: ""
                tracks.add(Track(title, artist, Uri.withAppendedPath(collection, id.toString())))
            }
        }
        adapter.notifyDataSetChanged()
        if (tracks.isEmpty()) nowPlaying.text = "Nenhuma música encontrada no aparelho."
    }

    private fun playTrack(position: Int) {
        if (tracks.isEmpty()) return
        val intent = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_SET_QUEUE
            putStringArrayListExtra(MusicService.EXTRA_URIS, ArrayList(tracks.map { it.uri.toString() }))
            putStringArrayListExtra(MusicService.EXTRA_TITLES, ArrayList(tracks.map { it.title }))
            putStringArrayListExtra(MusicService.EXTRA_ARTISTS, ArrayList(tracks.map { it.artist }))
            putExtra(MusicService.EXTRA_INDEX, position)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        nowPlaying.text = "Carregando: ${tracks[position].title}"
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MusicService::class.java).also { bindService(it, connection, Context.BIND_AUTO_CREATE) }
    }

    override fun onStop() {
        if (bound) {
            musicService?.onStateChanged = null
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
