import asyncio, subprocess, threading, http.server, functools
from playwright.async_api import async_playwright
URL = "https://video.twimg.com/tweet_video/G5Yn_dJXcAAYQJ8.mp4"
O = "http://127.0.0.1:8765"
def serve():
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory="docs")
    s = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), h)
    threading.Thread(target=s.serve_forever, daemon=True).start()
def curl(*hdr):
    args = ["curl","-s","-D","-","-o","/dev/null","-m","20"]
    for h in hdr: args += ["-H", h]
    out = subprocess.run(args + [URL], capture_output=True, text=True).stdout
    acao = [l for l in out.splitlines() if l.lower().startswith("access-control-allow-origin")]
    return (out.splitlines() or ["?"])[0] + " " + (acao[0] if acao else "SEM ACAO")
UA = "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
print("C1 origin", curl(f"Origin: {O}"))
print("C2 +UA", curl(f"Origin: {O}", UA))
print("C3 +referer", curl(f"Origin: {O}", f"Referer: {O}/"))
print("C4 +secfetch", curl(f"Origin: {O}", "Sec-Fetch-Mode: cors", "Sec-Fetch-Site: cross-site", "Sec-Fetch-Dest: empty"))
print("C5 +accept", curl(f"Origin: {O}", "Accept: */*", "Accept-Encoding: gzip, deflate, br, zstd"))
print("C6 +range", curl(f"Origin: {O}", "Range: bytes=0-"))
print("C7 github.io", curl("Origin: https://carvalho-joao.github.io", UA, "Referer: https://carvalho-joao.github.io/"))
async def main():
    serve()
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page()
        reqs = []
        pg.on("request", lambda r: reqs.append(r) if "twimg" in r.url else None)
        await pg.goto(O + "/index.html")
        for opts in ["{}", "{cache:'no-store'}", "{referrerPolicy:'no-referrer'}", "{referrerPolicy:'no-referrer',cache:'no-store',credentials:'omit'}"]:
            res = await pg.evaluate("""async ([u,o]) => { try { const r = await fetch(u + '?x=' + Math.random(), eval('('+o+')')); const b = await r.blob(); return 'OK ' + r.status + ' ' + b.size; } catch(e){ return 'ERR ' + e.message; } }""", [URL, opts])
            print("B", opts, res)
        if reqs:
            r = reqs[0]
            print("REQ HEADERS", await r.all_headers())
            resp = await r.response()
            if resp: print("RESP HEADERS", await resp.all_headers())
        await b.close()
asyncio.run(main())
