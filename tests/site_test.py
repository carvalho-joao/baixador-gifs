"""Teste de ponta a ponta do site com posts reais (roda no GitHub Actions)."""
import asyncio, sys, threading, http.server, functools
from playwright.async_api import async_playwright

IDS = ["1988338900897808542", "1988270717280874615", "1988292708201574699", "1989268114023092320", "1988845377735327918"]

def serve():
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory="docs")
    s = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), h)
    threading.Thread(target=s.serve_forever, daemon=True).start()

async def main():
    serve()
    ok_mp4 = ok_gif = 0
    async with async_playwright() as p:
        b = await p.chromium.launch(channel="chrome")
        for pid in IDS:
            pg = await b.new_page(accept_downloads=True)
            errs = []
            pg.on("pageerror", lambda e: errs.append(str(e)))
            await pg.goto("http://127.0.0.1:8765/index.html")
            await pg.fill("#url", f"https://x.com/i/status/{pid}")
            try:
                await pg.wait_for_selector(".card, .msg", timeout=20000)
            except Exception:
                print(pid, "SEM RESPOSTA"); continue
            if not await pg.query_selector(".card"):
                print(pid, "MSG:", (await pg.inner_text("#msg"))[:120]); continue
            tag = await pg.inner_text(".card .tag")
            try:
                async with pg.expect_download(timeout=30000) as d:
                    await pg.click(".card [data-act=mp4]")
                dl = await d.value
                path = await dl.path()
                size = __import__("os").path.getsize(path)
                print(pid, tag, "MP4 OK", dl.suggested_filename, size, "bytes"); ok_mp4 += 1
            except Exception as e:
                print(pid, tag, "MP4 FALHOU", str(e)[:120], await pg.inner_text(".card .out"))
            try:
                await pg.select_option(".card [data-k=w]", index=0)
                await pg.click(".card [data-act=gif]")
                await pg.wait_for_selector(".card [data-save]", timeout=120000)
                print(pid, "GIF OK", await pg.inner_text(".card [data-save]")); ok_gif += 1
            except Exception as e:
                print(pid, "GIF FALHOU", str(e)[:120], "|", (await pg.inner_text(".card")).replace("\n"," / ")[-300:])
            if errs: print(pid, "ERROS JS", errs)
            await pg.close()
        await b.close()
    print(f"RESUMO: MP4 {ok_mp4}/{len(IDS)}  GIF {ok_gif}/{len(IDS)}")
    sys.exit(0 if ok_mp4 and ok_gif else 1)

asyncio.run(main())
