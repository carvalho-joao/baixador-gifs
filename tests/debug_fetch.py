import asyncio, json, subprocess, threading, http.server, functools
from playwright.async_api import async_playwright
PID = "1988338900897808542"
def serve():
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory="docs")
    s = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), h)
    threading.Thread(target=s.serve_forever, daemon=True).start()
async def main():
    serve()
    raw = subprocess.run(["curl","-s",f"https://api.fxtwitter.com/2/status/{PID}"],capture_output=True,text=True).stdout
    v = json.loads(raw)["status"]["media"]["videos"][0]
    print("VIDEO", json.dumps({k: v.get(k) for k in ("type","url","width","height","duration","formats")})[:900])
    url = v["url"]
    print(subprocess.run(["curl","-s","-D","-","-o","/dev/null","-H","Origin: http://127.0.0.1:8765","-m","20",url],capture_output=True,text=True).stdout)
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page()
        pg.on("console", lambda m: print("CONSOLE", m.text))
        pg.on("requestfailed", lambda r: print("REQFAIL", r.url[:100], r.failure))
        pg.on("response", lambda r: print("RESP", r.status, r.url[:100]))
        await pg.goto("http://127.0.0.1:8765/index.html")
        res = await pg.evaluate("""async (u) => {
          const t0 = performance.now();
          const ctl = new AbortController(); setTimeout(() => ctl.abort(), 25000);
          try { const r = await fetch(u, {signal: ctl.signal}); const h = [...r.headers].map(x=>x.join(': ')).join(' | ');
                const b = await r.blob(); return 'OK ' + r.status + ' ' + b.size + ' ' + Math.round(performance.now()-t0) + 'ms ' + h; }
          catch(e){ return 'ERR ' + e.name + ' ' + e.message + ' ' + Math.round(performance.now()-t0) + 'ms'; }
        }""", url)
        print("FETCH", res)
        await b.close()
asyncio.run(main())
