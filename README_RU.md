<!--
  SEO ключевые слова: читалка новелл android, приложение для ранобэ, читать веб-новеллы,
  epub читалка android, перевод новелл, jaomix, ranobelib, wuxiaworld, бесплатная читалка,
  open source ридер новелл, читать ранобэ на телефоне
-->

<div align="center">

<img src="https://github.com/HnDK0/NoveLA/raw/default/screenshots/NoveLA.jpg" width="96" height="96" alt="Иконка приложения NoveLA — читалка новелл для Android"/>

# NoveLA

### Бесплатный open source Android-ридер для веб-новелл, ранобэ и EPUB

**25+ источников &nbsp;·&nbsp; Встроенный переводчик &nbsp;·&nbsp; Озвучка &nbsp;·&nbsp; Оффлайн-чтение &nbsp;·&nbsp; Material 3**

<br/>

[![Скачать](https://img.shields.io/github/v/release/HnDK0/NoveLA?style=for-the-badge&label=%E2%AC%87%20%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C&color=4CAF50)](https://github.com/HnDK0/NoveLA/releases/latest)
&nbsp;
[![Загрузки](https://img.shields.io/github/downloads/HnDK0/NoveLA/total?style=for-the-badge&color=2196F3&label=%D0%97%D0%B0%D0%B3%D1%80%D1%83%D0%B7%D0%BA%D0%B8)](https://github.com/HnDK0/NoveLA/releases)
&nbsp;
[![Звёзды](https://img.shields.io/github/stars/HnDK0/NoveLA?style=for-the-badge&color=FFD700)](https://github.com/HnDK0/NoveLA/stargazers)
&nbsp;
[![Лицензия](https://img.shields.io/github/license/HnDK0/NoveLA?style=for-the-badge)](LICENSE)
&nbsp;
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?style=for-the-badge&logo=android)](https://github.com/HnDK0/NoveLA/releases/latest)

<br/>

<img src="preview.png" alt="NoveLA — Android читалка веб-новелл со встроенным переводчиком, 25+ источниками, TTS и поддержкой EPUB" width="100%"/>

<br/><br/>

> 🌍 **Language / Язык:** &nbsp; [🇬🇧 English](README.md) &nbsp;|&nbsp; **🇷🇺 Русский**

<br/>

[📥 Скачать APK](#-скачать) &nbsp;·&nbsp; [✨ Возможности](#-возможности) &nbsp;·&nbsp; [📚 Источники](#-источники-25) &nbsp;·&nbsp; [🖼 Скриншоты](#-скриншоты) &nbsp;·&nbsp; [🤝 Контрибьютинг](#-контрибьютинг)

</div>

---

## О приложении

NoveLA — бесплатный Android-ридер для веб-новелл, ранобэ и EPUB. Совмещает мощный мультисорс-ридер со встроенным переводчиком: открываете любую главу и переводите в один тап, не переключая приложение.

Без подписок. Без рекламы. Полностью открытый исходный код под лицензией GPL-3.0.

---

## ✨ Возможности

<table>
<tr>
<td valign="top" width="50%">

**Чтение**
- Бесконечная прокрутка глав
- Настройка шрифта и размера текста
- Светлая и тёмная темы (Material 3)
- Оффлайн-кэширование глав
- Чистый режим чтения

**Озвучка (TTS)**
- Фоновое воспроизведение
- Настройка голоса, скорости и высоты тона

</td>
<td valign="top" width="50%">

**Поиск и источники**
- 25+ встроенных источников (EN, RU, ZH, ID, MTL)
- Глобальный мультисорс-поиск
- Добавление новелл по ссылке
- Фильтры по жанру, рейтингу, статусу
- Репозиторий плагинов от сообщества (Lua)

**Продвинутые функции**
- Резервное копирование и восстановление
- Regex-очистка текста (удаление рекламы)
- Обход Cloudflare Turnstile
- Локальная EPUB-библиотека

</td>
</tr>
</table>

---

## 🌐 Встроенный переводчик

Переводите главы прямо в ридере — без копирования, без переключения приложений. Поддерживается четыре движка:

| Сервис | Стоимость | API-ключ | Примечание |
|--------|-----------|----------|------------|
| Google Translate (Enhanced) | Бесплатно | Не нужен | HTML-режим, лучше качество |
| Google Translate (Simple) | Бесплатно | Не нужен | Текстовый режим, быстрый |
| Google Gemini API | Есть бесплатный тир | Нужен | AI-перевод, высокое качество |
| OpenAI-совместимый API | По тарифу | Нужен | OpenAI, OpenRouter, DeepSeek, Ollama, Mistral… |

> При превышении лимита несколько API-ключей чередуются по кругу.

---

## 📚 Источники (25+)

<details>
<summary>Показать полный список</summary>

| Язык | Источники |
|------|-----------|
| Английские | FreeWebNovel · NovelFull · NovelBin · Royal Road · Scribble Hub · AllNovel · NoBadNovel · NovelBuddy · NovelFire · NovelHall · NovLove · ReadNovelFull · WuxiaWorld |
| Русские | Jaomix · RanobeLib · RanobeHub · Свободный Мир Ранобэ · BookHamster |
| Китайские | 69书吧 · Twkan · Ttkan · Novel543 · Quanben5 · Piaotia |
| Индонезийские | BacaLightnovel |
| MTL | WTR-LAB |
| Локальные | EPUB-файлы |

</details>

**Плюс:** глобальный мультисорс-поиск · добавление любой новеллы по ссылке · репозиторий плагинов

### Репозиторий плагинов

NoveLA поддерживает внешние Lua-плагины — источники от сообщества, устанавливаемые прямо из приложения.

Официальный репозиторий: [`HnDK0/external-sources`](https://github.com/HnDK0/external-sources)

Подключить: **Finder → Extensions → Настройки (иконка шестерёнки)** → вставьте URL репозитория.

---

## 📥 Скачать

**[Получить последний APK →](https://github.com/HnDK0/NoveLA/releases/latest)**

Или собрать из исходников:

```bash
git clone https://github.com/HnDK0/NoveLA
# Откройте в Android Studio и запустите на устройстве или эмуляторе
```

Требования: Android 8.0+.

---

## 🛠 Технологии

Kotlin · Coroutines · LiveData · Jetpack Compose · Material 3 · Room (SQLite) · Jsoup · OkHttp · Coil · Glide · Android TTS & Media APIs · Google MLKit · LuaJ

---

## 🤝 Контрибьютинг

Приветствуется любой вклад:

- Исправления парсеров существующих источников
- Новые источники через [репозиторий плагинов](https://github.com/HnDK0/external-sources)
- Улучшения ридера или производительности
- Баг-репорты через [Issues](https://github.com/HnDK0/NoveLA/issues)

---

## 📄 Лицензия

[GPL-3.0](LICENSE) — свободно использовать, изменять и распространять на тех же условиях.

---

<div align="center">

**[Скачать](https://github.com/HnDK0/NoveLA/releases/latest) &nbsp;·&nbsp; [Issues](https://github.com/HnDK0/NoveLA/issues) &nbsp;·&nbsp; [Плагины](https://github.com/HnDK0/external-sources)**

</div>
