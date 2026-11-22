package com.nightmare.offlinemusic

import android.Manifest
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
import android.app.Activity

private data class Track(val title: String, val artist: String, val uri: Uri) {
    override fun toString(): String = if (artist.isBlank() || artist == "<unknown>") title else "$title  —  $artist"
}

class MainActivity : Activity() {
    private val tracks = mutableListOf<Track>()
    private lateinit var list: ListView
    private lateinit var nowPlaying: TextView
    private lateinit var playButton: Button
    private var musicService: MusicService? = null
    private var bound = false


    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            musicService = (binder as MusicService.LocalBinder).getService()
            bound = true
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
        } else requestPermissions(arrayOf(permission), 10)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                loadTracks()
                requestNotificationPermissionIfNeeded()
            } else Toast.makeText(this, "Permissão necessária para ler suas músicas.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(14))
            setBackgroundColor(0xFFF7F6FA.toInt())
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
        list = ListView(this).apply {
            dividerHeight = dp(1)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, tracks)
            setOnItemClickListener { _, _, position, _ -> playTrack(position) }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = Button(this).apply { text = "⏮"; setOnClickListener { musicService?.previous() } }
        playButton = Button(this).apply { text = "▶ / ❚❚"; setOnClickListener { musicService?.togglePlayback() } }
        val next = Button(this).apply { text = "⏭"; setOnClickListener { musicService?.next() } }
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(52), 1f))
        controls.addView(playButton, LinearLayout.LayoutParams(0, dp(52), 1.4f))
        controls.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f))

        root.addView(heading)
        root.addView(subtitle)
        root.addView(nowPlaying)
        root.addView(refresh)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(controls)
        setContentView(root)
    }

    private fun loadTracks() {
        tracks.clear()
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
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
        (list.adapter as ArrayAdapter<Track>).notifyDataSetChanged()
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
        nowPlaying.text = "Tocando: ${tracks[position].title}"
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MusicService::class.java).also { bindService(it, connection, Context.BIND_AUTO_CREATE) }
    }

    override fun onStop() {
        if (bound) { unbindService(connection); bound = false }
        super.onStop()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
