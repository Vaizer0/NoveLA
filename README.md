# <img src="https://github.com/HnDK0/NoveLA/raw/default/screenshots/NoveLA.png" width="40" height="40" valign="middle"> NoveLA

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

**NoveLA** is an open-source Android application for reading web novels, light novels, and EPUB with built-in translation and multi-source support.

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

### 🧩 External Plugin Repository

NoveLA supports external Lua-based plugin repositories, allowing community-maintained sources to be installed directly from the app.

**Official external repository:** [`HnDK0/external-sources`](https://github.com/HnDK0/external-sources)

To add it: go to **Finder → Extensions → ⚙️ Settings** and paste the repository URL.

Community contributions (new sources, fixes) are welcome via pull requests to the external repository.

---

### 📚 Multi-Source Support

Complete list of built-in sources:

#### 🇨🇳 Chinese sources
- 69书吧  
- Twkan  
- Ttkan  
- Novel543  
- Quanben5
- Piaotia

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

#### 🌐 MTL sources
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

## 🖼 Screenshots
<details open>
<summary>Show screenshots</summary>
<table>
  <tr>
    <td align="center"><b>Library</b><br><img src="screenshots/Screenshot_Library.png"/></td>
    <td align="center"><b>Sources</b><br><img src="screenshots/Screenshot_Finder.png"/></td>
    <td align="center"><b>Extensions</b><br><img src="screenshots/Screenshot_Extensions.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Book</b><br><img src="screenshots/Screenshot_book.png"/></td>
    <td align="center"><b>Chapter</b><br><img src="screenshots/Screenshot_Chapter.png"/></td>
    <td align="center"><b>Translation</b><br><img src="screenshots/Screenshot_Chapter_Translate.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Voice</b><br><img src="screenshots/Screenshot_Chapter_Voice.png"/></td>
    <td align="center"><b>Add by URL</b><br><img src="screenshots/Screenshot_Add_by_URLs.png"/></td>
    <td align="center"><b>Global Search</b><br><img src="screenshots/Screenshot_Global_Search.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Settings</b><br><img src="screenshots/Screenshot_settings.png"/></td>
    <td align="center"><b>Cloudflare Bypass</b><br><img src="screenshots/Screenshot_CF_Turnstile.png"/></td>
    <td align="center"><b>Regex settings</b><br><img src="screenshots/Screenshot_settings_regex_.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Regex cleanup</b><br><img src="screenshots/Screenshot_regex_Cleanup.png"/></td>
    <td align="center"><b>WTR-LAB language</b><br><img src="screenshots/wtrlab_set_language.png"/></td>
    <td align="center"><b>WTR-LAB settings</b><br><img src="screenshots/wrtlab_setting.png"/></td>
  </tr>
</table>
</details>

---

## 🧩 Tech Stack
- Kotlin, Coroutines, LiveData
- Jetpack Compose + XML Views
- Material 3 UI
- Room (SQLite), Jsoup, OkHttp
- Coil, Glide
- Android TTS & Media APIs
- Google MLKit
- LuaJ (plugin engine)

---

## 📦 Installation
```bash
git clone https://github.com/HnDK0/NoveLA
```
Open the project in Android Studio and run on a device or emulator.

---

## 📜 License
GPL-3.0

</details>

<details>
<summary>🇷🇺 Русский</summary>

## 📖 Описание

**NoveLA** — это open source Android-приложение для чтения веб-новелл, ранобэ и EPUB с встроенным переводчиком и поддержкой множества источников.

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

### 🧩 Внешний репозиторий плагинов

NoveLA поддерживает внешние Lua-плагины, которые можно устанавливать прямо из приложения через репозитории, поддерживаемые сообществом.

**Официальный внешний репозиторий:** [`HnDK0/external-sources`](https://github.com/HnDK0/external-sources)

Чтобы подключить: **Finder → Extensions → ⚙️ Настройки** → вставьте URL репозитория.

PR с новыми источниками и исправлениями приветствуются.

---

### 📚 Поддержка множества источников

Полный список встроенных источников:

#### 🇨🇳 Китайские источники
- 69书吧  
- Twkan  
- Ttkan  
- Novel543  
- Quanben5
- Piaotia

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

#### 🌐 MTL источники
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
<table>
  <tr>
    <td align="center"><b>Library</b><br><img src="screenshots/Screenshot_Library.png"/></td>
    <td align="center"><b>Sources</b><br><img src="screenshots/Screenshot_Finder.png"/></td>
    <td align="center"><b>Extensions</b><br><img src="screenshots/Screenshot_Extensions.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Book</b><br><img src="screenshots/Screenshot_book.png"/></td>
    <td align="center"><b>Chapter</b><br><img src="screenshots/Screenshot_Chapter.png"/></td>
    <td align="center"><b>Translation</b><br><img src="screenshots/Screenshot_Chapter_Translate.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Voice</b><br><img src="screenshots/Screenshot_Chapter_Voice.png"/></td>
    <td align="center"><b>Add by URL</b><br><img src="screenshots/Screenshot_Add_by_URLs.png"/></td>
    <td align="center"><b>Global Search</b><br><img src="screenshots/Screenshot_Global_Search.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Settings</b><br><img src="screenshots/Screenshot_settings.png"/></td>
    <td align="center"><b>Cloudflare Bypass</b><br><img src="screenshots/Screenshot_CF_Turnstile.png"/></td>
    <td align="center"><b>Regex settings</b><br><img src="screenshots/Screenshot_settings_regex_.png"/></td>
  </tr>
  <tr>
    <td align="center"><b>Regex cleanup</b><br><img src="screenshots/Screenshot_regex_Cleanup.png"/></td>
    <td align="center"><b>WTR-LAB language</b><br><img src="screenshots/wtrlab_set_language.png"/></td>
    <td align="center"><b>WTR-LAB settings</b><br><img src="screenshots/wrtlab_setting.png"/></td>
  </tr>
</table>
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
- LuaJ (движок плагинов)

---

## 📦 Установка
```bash
git clone https://github.com/HnDK0/NoveLA
```
Откройте проект в Android Studio и запустите на устройстве или эмуляторе.

---

## 🤝 Контрибьютинг
Приветствуются исправления парсеров, новые источники и улучшения ридера.

---

## 📄 Лицензия
GPL-3.0

</details>
