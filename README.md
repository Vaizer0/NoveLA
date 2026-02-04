# Novela

Android application for reading web novels. The app focuses on ease of use and maximum immersion. Search through a large catalog of content, pick what you like, and enjoy reading.

[🇷🇺 Русская версия](README_RU.md)

> **Note**: This is an amateur fork of the [NovelDokusha](https://github.com/nanihadesuka/NovelDokusha) project, which is no longer actively maintained. This fork contains additional improvements and fixes. Any improvements are welcome!

## License
Copyright © 2023, [nani](https://github.com/HnDK0), distributed under the [GPL-3](LICENSE) FOSS license.

## Features

- **Gemini Translation Support** - Real-time translation with Google Gemini AI
- **Free translation via Google API** - Live translation from Google for free
- **Multiple sources**:
  - **Chinese sources**: 69书吧, Twkan, Ttkan
  - **Russian sources**: Jaomix, RanobeLib, RanobeHub, Свободный Мир Ранобэ, BookHamster
  - **English sources**: FreeWebNovel, NovelFull, NovelBin, Royal Road, Scribble Hub, AllNovel, NoBadNovel, NovelBuddy, NovelFire, NovelHall, NovLove, ReadNovelFull, WuxiaWorld
  - **Indonesian sources**: BacaLightnovel, Novelku
  - **Local source** for reading local EPUB files
- **Multiple databases** for novel search
- **Add novels by link** - Ability to add novels directly by URL
- **Simple backup and restore**
- **Light and dark themes** (Material 3)
- **Advanced reading features**:
  - Infinite scrolling
  - Custom font and text size
  - **Live translation** using Gemini AI or Google API
  - **Text-to-speech**: Background playback, voice/pitch/speed adjustments
- **Automatic Cloudflare bypass**
- **Text cleanup using regular expressions**

## Screenshots

| Library | Sources |
|:----------:|:-----:|
| ![](screenshots/Screenshot_Library.png) | ![](screenshots/Screenshot_Finder.png) |
| Book | Chapter |
| ![](screenshots/Screenshot_book.png) | ![](screenshots/Screenshot_Chapter.png) |
| Translation | Voice |
| ![](screenshots/Screenshot_Chapter_Translate.png) | ![](screenshots/Screenshot_Chapter_Voice.png) |
| Add by URL | Global Search |
| ![](screenshots/Screenshot_Add_by_URLs.png) | ![](screenshots/Screenshot_Global_Search.png) |
| Settings | CloudFlare Bypass |
| ![](screenshots/Screenshot_settings.png) | ![](screenshots/Screenshot_CF_Turnstile.png) |
| Regex settings | Regex cleanup |
| ![](screenshots/Screenshot_settings_regex_.png) | ![](screenshots/Screenshot_regex_Cleanup.png) |

## Tech Stack
- Kotlin, XML views, Jetpack Compose
- Material 3, Coroutines, LiveData
- Room (SQLite), Jsoup, OkHttp
- Coil, Glide, Gson, Moshi
- Google MLKit, Android TTS, Android media
