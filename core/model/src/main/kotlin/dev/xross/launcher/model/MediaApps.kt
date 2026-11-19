package dev.xross.launcher.model

/** Where a non-game Android app is listed: Apps, or the Apps folder of Video, Music or Photo. */
enum class AppHome { APPS, VIDEO, MUSIC, PHOTO }

/**
 * Well-known media apps that don't declare an `ApplicationInfo.category` (or declare none of
 * video, audio or image). Only used when the app itself says nothing; a declared category wins.
 */
object MediaApps {
    val KNOWN: Map<String, AppHome> = buildMap {
        for (pkg in listOf(
            "com.google.android.youtube", "com.google.android.youtube.tv", "com.google.android.apps.youtube.kids",
            "com.netflix.mediaclient", "com.netflix.ninja", "tv.twitch.android.app", "com.disney.disneyplus",
            "com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom", "com.wbd.stream", "com.hbo.hbonow",
            "com.hulu.plus", "com.crunchyroll.crunchyroid", "com.google.android.videos", "com.plexapp.android",
            "org.jellyfin.mobile", "org.jellyfin.androidtv", "org.videolan.vlc", "org.xbmc.kodi", "is.xyz.mpv",
            "com.mxtech.videoplayer.ad", "com.apple.atve.androidtv.appletv",
        )) put(pkg, AppHome.VIDEO)
        for (pkg in listOf(
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.amazon.mp3", "com.apple.android.music",
            "deezer.android.app", "com.aspiro.tidal", "com.soundcloud.android", "com.pandora.android",
            "com.bandcamp.android", "au.com.shiftyjelly.pocketcasts", "com.qobuz.music",
        )) put(pkg, AppHome.MUSIC)
        for (pkg in listOf(
            "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery", "com.oneplus.gallery",
            "com.adobe.lrmobile", "com.android.gallery3d",
        )) put(pkg, AppHome.PHOTO)
    }
}
