# Исходник заставки

3D-сцена на three.js (`scene.html`) и скрипт рендера в видео (`render.py`, headless Chrome + ffmpeg).

Пересобрать:

```
npm install three@0.170.0 @fontsource/manrope
pip install playwright
python3 render.py stills ./stills 0.5 1.9 3.05   # проверочные кадры
python3 render.py video ./sputnik_splash.mp4      # полный ролик, 3,6 с, 1080×2400, 30 к/с
```

Тайминги — функция `setTime(t)` в конце `scene.html`; свет и отражения — `makeEnvironment()`; модель очков — `frameShape()`, `lensShape()`, `templeShape()`.
