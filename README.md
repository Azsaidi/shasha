# Shasha (شاشة)

A free, ad-free Android IPTV player for Xtream Codes accounts.

- Log in with playlist name, username, password and server (or paste a full M3U `get.php` link)
- Live TV, Movies and Series with categories and search
- Favorites, resume playback for movies and episodes
- ExoPlayer (Media3) with TS and HLS, automatic TS/HLS fallback for live channels
- Picture-in-picture, fit / fill / stretch, subtitles and audio track selection
- Several accounts

## Getting the APK

Every push to `main` builds a signed APK with GitHub Actions. Download it from the
[Releases](../../releases) page on your phone and open it to install.

## Building locally

Requires JDK 17 and the Android SDK (platform 34).

```
gradle assembleRelease
```
