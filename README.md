# 📚 Novela

Android application for reading web novels, light novels, and EPUB with built-in translation and multi-source support.  
Focused on immersion, simplicity, and convenient reading of foreign content.

<p align="center">
  <b>Read web novels from multiple sources • Translate chapters instantly • Enjoy clean immersive reading</b>
</p>

---

## 🌍 Languages

<details open>
<summary>🇬🇧 English</summary>

## 📖 Description

**Novela** is an open-source Android application for reading web novels, light novels, and EPUB with built-in translation and multi-source support.

The app is designed for convenient reading of foreign content: find a novel, open a chapter, and translate text instantly inside the reader.

Main focus:
- comfortable long reading
- built-in translator
- large multi-source ecosystem
- clean and fast UI without clutter

---

## ✨ Features

### 🌐 Built-in Translator
- Translate chapters directly while reading
- Free translation via Google API
- Optional Gemini integration for higher-quality translation
- Instant language switching without reloading chapters

> Note: Free Gemini API keys are rate-limited (request and speed limits depend on model and Google quota policy and may change over time).

---

### 📚 Multi-Source Support

Complete list of supported sources:

#### 🇨🇳 Chinese sources
- 69书吧  
- Twkan  
- Ttkan  
- Novel543  
- Quanben5  

#### 🇷🇺 Russian sources
- Jaomix  
- RanobeLib  
- RanobeHub  
- Свободный Мир Ранобэ  
- BookHamster  

#### 🇬🇧 English sources
- FreeWebNovel  
- NovelFull  
- NovelBin  
- Royal Road  
- Scribble Hub  
- AllNovel  
- NoBadNovel  
- NovelBuddy  
- NovelFire  
- NovelHall  
- NovLove  
- ReadNovelFull  
- WuxiaWorld  

#### 🇮🇩 Indonesian sources
- BacaLightnovel  
- Novelku  

#### 🌐 Multilanguage sources
- WTR-LAB  

#### 📄 Local source
- Local EPUB files

Additional features:
- Add novels directly by URL
- Global multi-database search

---

### 📖 Reader Features
- Infinite chapter scrolling
- Custom fonts and text size
- Light & dark themes (Material 3)
- Offline chapter caching
- Clean immersive reading mode

---

### 🔊 Text-to-Speech
- Background playback
- Voice, speed, and pitch control
- Convenient for listening to chapters

---

### 🛠 Advanced Tools
- Automatic Cloudflare Turnstile bypass
- Regex-based text cleanup (remove ads and junk text)
- Local library and reading history
- Backup and restore support

---

## 🧩 Tech Stack
- Kotlin, Coroutines, LiveData
- Jetpack Compose + XML Views
- Material 3 UI
- Room (SQLite), Jsoup, OkHttp
- Coil, Glide
- Android TTS & Media APIs
- Google MLKit

---

## 📦 Installation
```bash
git clone https://github.com/HnDK0/Novela
```
Open the project in Android Studio and run on a device or emulator.

---

## 📜 License
GPL-3.0

</details>

<details>
<summary>🇷🇺 Русский</summary>

## 📖 Описание

**Novela** — это open source Android-приложение для чтения веб-новелл, ранобэ и EPUB с встроенным переводчиком и поддержкой множества источников.

Приложение создано для удобного чтения иностранного контента: находите новеллу, открываете главу и переводите текст прямо в ридере без лишних действий.

Основной фокус:
- удобство чтения
- встроенный переводчик
- поддержка множества источников
- чистый и быстрый интерфейс без перегрузки

---

## ✨ Возможности

### 🌐 Встроенный переводчик
- Перевод текста прямо во время чтения
- Бесплатный перевод через Google API
- Опциональная интеграция Gemini для более качественного перевода
- Мгновенное переключение языка без перезагрузки главы

> Примечание: бесплатные ключи Gemini API имеют ограничения по количеству запросов и скорости (зависят от модели и квот Google и могут изменяться).

---

### 📚 Поддержка множества источников

Полный список поддерживаемых источников:

#### 🇨🇳 Китайские источники
- 69书吧  
- Twkan  
- Ttkan  
- Novel543  
- Quanben5  

#### 🇷🇺 Русские источники
- Jaomix  
- RanobeLib  
- RanobeHub  
- Свободный Мир Ранобэ  
- BookHamster  

#### 🇬🇧 Английские источники
- FreeWebNovel  
- NovelFull  
- NovelBin  
- Royal Road  
- Scribble Hub  
- AllNovel  
- NoBadNovel  
- NovelBuddy  
- NovelFire  
- NovelHall  
- NovLove  
- ReadNovelFull  
- WuxiaWorld  

#### 🇮🇩 Индонезийские источники
- BacaLightnovel  
- Novelku  

#### 🌐 Мультиязычные источники
- WTR-LAB  

#### 📄 Локальный источник
- EPUB файлы (локальная библиотека)

Дополнительно:
- Добавление новелл по ссылке (URL)
- Глобальный поиск по нескольким базам

---

### 📖 Возможности ридера
- Бесконечная прокрутка глав
- Настройка шрифта и размера текста
- Светлая и тёмная темы (Material 3)
- Оффлайн кэширование глав
- Чистый режим чтения без отвлекающих элементов

---

### 🔊 Озвучка (TTS)
- Фоновое воспроизведение глав
- Настройка голоса, скорости и высоты тона
- Удобно для прослушивания новелл

---

### 🛠 Продвинутые функции
- Автоматический обход Cloudflare Turnstile
- Очистка текста через Regex (удаление рекламы и мусора)
- Локальная библиотека и история чтения
- Простое резервное копирование и восстановление

---

## 🖼 Скриншоты
<details>
<summary>Показать скриншоты</summary>

| Library | Sources |
|:--:|:--:|
| ![](screenshots/Screenshot_Library.png) | ![](screenshots/Screenshot_Finder.png) |
| Book | Chapter |
| ![](screenshots/Screenshot_book.png) | ![](screenshots/Screenshot_Chapter.png) |
| Translation | Voice |
| ![](screenshots/Screenshot_Chapter_Translate.png) | ![](screenshots/Screenshot_Chapter_Voice.png) |
| Add by URL | Global Search |
| ![](screenshots/Screenshot_Add_by_URLs.png) | ![](screenshots/Screenshot_Global_Search.png) |
| Settings | Cloudflare Bypass |
| ![](screenshots/Screenshot_settings.png) | ![](screenshots/Screenshot_CF_Turnstile.png) |
| Regex settings | Regex cleanup |
| ![](screenshots/Screenshot_settings_regex_.png) | ![](screenshots/Screenshot_regex_Cleanup.png) |
| WTR-LAB language | WTR-LAB settings |
| ![](screenshots/wtrlab_set_language.png) | ![](screenshots/wrtlab_setting.png) |

</details>

---

## 🧩 Технологии
- Kotlin, Coroutines, LiveData
- Jetpack Compose + XML Views
- Material 3
- Room (SQLite)
- Jsoup, OkHttp
- Coil, Glide
- Android TTS & Media APIs
- Google MLKit

---

## 📦 Установка
```bash
git clone https://github.com/HnDK0/Novela
```
Откройте проект в Android Studio и запустите на устройстве или эмуляторе.

---

## 🤝 Контрибьютинг
Приветствуются исправления парсеров, новые источники и улучшения ридера.

---

## 📄 Лицензия
GPL-3.0

</details>
