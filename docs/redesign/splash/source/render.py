"""Render the splash scene with headless Chrome.

  python3 render.py stills OUT_DIR t1 t2 ...   -> PNG frames at given times
  python3 render.py video OUT.mp4               -> full 30 fps H.264 video
"""
import base64, functools, http.server, os, subprocess, sys, threading, time
from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.abspath(__file__))
FPS = 30


def serve():
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT)
    handler.log_message = lambda *a, **k: None
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def open_page(p):
    browser = p.chromium.launch(
        executable_path="/usr/bin/google-chrome",
        args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"],
    )
    page = browser.new_page(viewport={"width": 1080, "height": 2400})
    page.on("console", lambda m: print("console:", m.text) if m.type in ("error", "warning") else None)
    page.on("pageerror", lambda e: print("pageerror:", e))
    page.goto("http://127.0.0.1:8765/scene.html")
    page.wait_for_function("window.ready === true || window.loadError", timeout=120000)
    err = page.evaluate("window.loadError || null")
    if err:
        raise SystemExit("load error: " + err)
    return browser, page


def grab(page, t):
    page.evaluate(f"window.renderAt({t})")
    data = page.evaluate("document.querySelector('canvas').toDataURL('image/png')")
    return base64.b64decode(data.split(",", 1)[1])


def main():
    mode = sys.argv[1]
    serve()
    with sync_playwright() as p:
        browser, page = open_page(p)
        if mode == "stills":
            out = sys.argv[2]
            os.makedirs(out, exist_ok=True)
            for t in sys.argv[3:]:
                t0 = time.time()
                png = grab(page, float(t))
                open(os.path.join(out, f"t{float(t):.2f}.png"), "wb").write(png)
                print(f"t={t} {time.time() - t0:.1f}s")
        else:
            out = sys.argv[2]
            tmp = out[:-4] + ".part.mp4"
            dur = page.evaluate("window.DURATION")
            n = round(dur * FPS)
            ff = subprocess.Popen(
                ["ffmpeg", "-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", str(FPS), "-c:v", "png", "-i", "-",
                 "-c:v", "libx264", "-preset", "veryfast", "-crf", "17", "-pix_fmt", "yuv420p", "-movflags", "+faststart", tmp],
                stdin=subprocess.PIPE,
            )
            t0 = time.time()
            for i in range(n):
                ff.stdin.write(grab(page, i / FPS))
                if i % 10 == 0:
                    print(f"frame {i}/{n} {time.time() - t0:.0f}s", flush=True)
            ff.stdin.close()
            if ff.wait() == 0:
                os.replace(tmp, out)
                print("done", out)
            else:
                raise SystemExit("ffmpeg failed")
        browser.close()


if __name__ == "__main__":
    main()
