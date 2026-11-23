package com.nightmare.offlinemusic

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Endereços das músicas no MediaStore. O endereço de cada faixa é montado só na hora de usar (poupa memória). */
object Media {
    fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    fun uriFor(id: Long): Uri = ContentUris.withAppendedId(collection(), id)
}
