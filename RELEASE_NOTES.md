# NoveLA — Light Novel Reader & Parser

<details>
<summary><b>🇷🇺 Russian Version</b></summary>

### Новые возможности
- Добавлена поддержка OpenAI
- Рефакторинг Gemini, общий менеджер промптов
- Добавлен механизм повторных попыток при загрузке глав

### Улучшения
- Исправлены лаги производительности при открытии глав
- Увеличен таймаут запросов перевода до 120 секунд

### Исправления
- Исправлены лаги TTS после повторной попытки загрузки главы
- Исправлена позиция вставки повторно загруженной главы
- Предотвращена цепочка предзагрузки после ошибки загрузки главы
- Исправлен пропуск глав при ошибке загрузки

⚠️ В читалке загрузка главы происходит только после полного получения всего текста. Поэтому при использовании локальных моделей (Ollama и т.д.) возможны таймауты при больших объёмах текста.
</details>

<details open>
<summary><b>🇺🇸 English Version</b></summary>

### New Features
- Added OpenAI support
- Gemini refactored, shared prompt manager
- Added retry mechanism for chapter downloads

### Improvements
- Fixed performance lags when opening chapters
- Increased translation request timeout to 120 seconds

### Fixes
- Fixed TTS lag after chapter retry
- Fixed retried chapter insert position
- Prevented preload chain after chapter load error
- Fixed chapter skipping on load error

⚠️ In the reader, chapter loading occurs only after the entire text is fully received. Therefore, when using local models (Ollama etc.), timeouts may occur for large text volumes.
</details>