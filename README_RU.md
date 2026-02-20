# Novela

Android-приложение для чтения веб-новелл. Приложение ориентировано на простоту использования и максимальное погружение в процесс чтения. Поиск по большому каталогу контента, выбор понравившегося и наслаждение чтением.

[🇺🇸 English version](README.md)

> **Примечание**: Это любительский форк проекта [NovelDokusha](https://github.com/nanihadesuka/NovelDokusha), который больше не поддерживается активно. Данный форк содержит дополнительные улучшения и исправления. Любые улучшения приветствуются!

## Лицензия
Copyright © 2023, [nani](https://github.com/HnDK0), распространяется под лицензией [GPL-3](LICENSE) FOSS

## Особенности

- **Поддержка перевода Gemini** - Перевод в реальном времени с помощью ИИ Google Gemini
- **Бесплатный перевод через Google API** - Live перевод от Google бесплатно
- **Несколько источников** для чтения новелл:
  - **Китайские источники**: 69书吧, Twkan, Ttkan
  - **Русские источники**: Jaomix, RanobeLib, RanobeHub, Свободный Мир Ранобэ, BookHamster
  - **Англоязычные источники**: FreeWebNovel, NovelFull, NovelBin, Royal Road, Scribble Hub, AllNovel, NoBadNovel, NovelBuddy, NovelFire, NovelHall, NovLove, ReadNovelFull, WuxiaWorld
  - **Индонезийские источники**: BacaLightnovel, Novelku
  - **Мультиязычные**: WTR-LAB
  - **Локальный источник** для чтения локальных EPUB-файлов
- **Несколько баз данных** для поиска новелл
- **Добавление новел по ссылке** - Возможность добавлять новеллы напрямую по URL
- **Простое резервное копирование и восстановление**
- **Светлая и темная темы** (Material 3)
- **Расширенные возможности чтения**:
  - Бесконечная прокрутка
  - Пользовательский шрифт и размер текста
  - **Live перевод** с помощью Gemini AI или Google API
  - **Озвучка текста**: Фоновое воспроизведение, настройка голоса и скорости
- **Автоматический обход Cloudflare**
- **Очистка текста с помощью регулярных выражений**

## Скриншоты

| Библиотека | Источники |
|:----------:|:-----:|
| ![](screenshots/Screenshot_Library.png) | ![](screenshots/Screenshot_Finder.png) |
| Книга | Глава |
| ![](screenshots/Screenshot_book.png) | ![](screenshots/Screenshot_Chapter.png) |
| Перевод | Озвучка |
| ![](screenshots/Screenshot_Chapter_Translate.png) | ![](screenshots/Screenshot_Chapter_Voice.png) |
| По URL | Глобальный поиск |
| ![](screenshots/Screenshot_Add_by_URLs.png) | ![](screenshots/Screenshot_Global_Search.png) |
| Настройки | Обход CloudFlare |
| ![](screenshots/Screenshot_settings.png) | ![](screenshots/Screenshot_CF_Turnstile.png) |
| Настройки regex | Очистка regex |
| ![](screenshots/Screenshot_settings_regex_.png) | ![](screenshots/Screenshot_regex_Cleanup.png) |
| WTR-LAB язык | WTR-LAB настройки |
| ![](screenshots/wtrlab_set_language.png) | ![](screenshots/wrtlab_setting.png) |

## Технологический стек
- Kotlin, XML views, Jetpack Compose
- Material 3, Coroutines, LiveData
- Room (SQLite), Jsoup, OkHttp
- Coil, Glide, Gson, Moshi
- Google MLKit, Android TTS, Android media
