#!/usr/bin/env python3
"""
Comicaso Downloader v5.0 — semua endpoint PHP (Juni 2026)

Endpoints confirmed dari DevTools:
  Browse/Popular : GET /api/home.php?source=all&q=&mode=update&type=all&limit=60&offset=0
  Search         : GET /api/home.php?source=all&q={query}&mode=search&type=all&limit=60&offset=0
  Genre filter   : GET /api/home.php?source=all&q=&mode=update&type=all&genre=action&limit=12&offset=0
  Manga detail   : GET /api/manga.php?source={source}&slug={slug}&platform=web
  Chapter pages  : GET /api/chapter.php?source={source}&manga={slug}&chapter={ch}&platform=web
  Trending       : GET /api/trending.php?period=daily&source=all&limit=20

Cara pakai: python comicaso.py
"""

import sys, re, time, json, zipfile, argparse, requests
from pathlib import Path
from urllib.parse import urlparse, urlencode

BASE     = "https://v3.comicaso.pro"
API      = f"{BASE}/api"
SOURCE   = "comicazen"
OUT_DIR  = Path("/sdcard/Manga")
DELAY    = 1.2

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
                  "AppleWebKit/537.36 (KHTML, like Gecko) "
                  "Chrome/139.0.0.0 Mobile Safari/537.36",
    "Accept": "application/json, */*",
    "Accept-Language": "id-ID,id;q=0.9,en-US;q=0.8",
    "Referer": BASE + "/",
    "X-Comicaso-Platform": "web",
}

R="\033[91m"; G="\033[92m"; Y="\033[93m"; B="\033[96m"
M="\033[95m"; W="\033[97m"; D="\033[2m";  X="\033[0m"; BOLD="\033[1m"

def sanitize(s): return re.sub(r'[\\/:*?"<>|]', "_", s).strip()

def get(url, params=None, stream=False, retries=3):
    for i in range(1, retries+1):
        try:
            r = requests.get(url, params=params, headers=HEADERS,
                             timeout=20, stream=stream, allow_redirects=True)
            r.raise_for_status()
            return r
        except Exception as e:
            if i == retries: return None
            time.sleep(DELAY * i)
    return None

# ── API calls ─────────────────────────────────────────────────────────────────

def fetch_home(query="", mode="update", type_="all", genre="",
               limit=60, offset=0, source="all"):
    params = {"source": source, "q": query, "mode": mode,
              "type": type_, "limit": limit, "offset": offset}
    if genre: params["genre"] = genre
    r = get(f"{API}/home.php", params=params)
    if not r: return []
    try:
        data = r.json()
        # Normalisasi — bisa list langsung atau dict dengan key data/manga/results
        if isinstance(data, list): return data
        for key in ["data", "manga", "results", "list"]:
            v = data.get(key)
            if isinstance(v, list): return v
        # Fallback: ambil value pertama yang list
        for v in data.values():
            if isinstance(v, list): return v
        return []
    except: return []

def fetch_manga_detail(slug, source=SOURCE):
    r = get(f"{API}/manga.php", params={
        "source": source, "slug": slug, "platform": "web"
    })
    if not r: return {}
    d = r.json()
    # Response: {"ok": true, "data": {...}, "mode_source": "..."}
    # Chapters ada di dalam d["data"]
    return d.get("data") or d

def fetch_chapter_images(manga_slug, chapter_slug, source=SOURCE):
    r = get(f"{API}/chapter.php", params={
        "source": source, "manga": manga_slug,
        "chapter": chapter_slug, "platform": "web"
    })
    if not r: return []
    try:
        data = r.json()
    except: return []

    # Unwrap {"ok":true, "data":{...images...}}
    if isinstance(data, dict) and "data" in data:
        data = data["data"]

    raw = []
    if isinstance(data, list): raw = data
    else:
        for key in ["images", "pages", "list"]:
            v = data.get(key)
            if isinstance(v, list) and v: raw = v; break
        if not raw:
            for v in data.values():
                if isinstance(v, list) and v: raw = v; break

    urls = []
    for item in raw:
        if isinstance(item, str) and item.startswith("http"):
            urls.append(item)
        elif isinstance(item, dict):
            # pages[0] bisa Object {"url":"...", "page":1, ...}
            for k in ["url", "src", "image", "img", "link", "page_url", "file"]:
                v = item.get(k, "")
                if isinstance(v, str) and v.startswith("http"):
                    urls.append(v); break
    return [u for u in urls if re.search(r'\.(webp|jpg|jpeg|png)', u, re.I)]

def fetch_trending(period="daily", limit=20):
    r = get(f"{API}/trending.php", params={
        "period": period, "source": "all", "limit": limit
    })
    if not r: return []
    try:
        data = r.json()
        if isinstance(data, list): return data
        for key in ["data","manga","results"]:
            v = data.get(key)
            if isinstance(v, list): return v
        return []
    except: return []

def get_chapters(detail):
    chapters = detail.get("chapters") or detail.get("chapter_list") or []
    result = []
    for ch in chapters:
        slug  = ch.get("slug") or ch.get("chapter") or ""
        title = ch.get("title") or ch.get("chapter_title") or \
                slug.replace("-"," ").title()
        result.append({"slug": slug, "title": title})
    return result

# ── Download & Pack ───────────────────────────────────────────────────────────

def download_image(url, dest: Path) -> bool:
    r = get(url, stream=True)
    if not r: return False
    try:
        dest.parent.mkdir(parents=True, exist_ok=True)
        with open(dest, "wb") as f:
            for chunk in r.iter_content(8192): f.write(chunk)
        return dest.stat().st_size > 500
    except: return False

def pack_cbz(image_dir: Path, cbz_path: Path) -> bool:
    imgs = sorted([f for f in image_dir.iterdir()
                   if f.suffix.lower() in {".jpg",".jpeg",".png",".webp"}])
    if not imgs: return False
    with zipfile.ZipFile(cbz_path, "w", zipfile.ZIP_STORED) as zf:
        for img in imgs: zf.write(img, img.name)
    return True

def download_chapter(manga_slug, chapter, manga_dir,
                     source=SOURCE, keep_raw=False) -> bool:
    ch_slug  = chapter["slug"]
    ch_title = chapter["title"]
    label    = sanitize(ch_title)
    cbz_path = manga_dir / f"{label}.cbz"

    if cbz_path.exists():
        print(f"  {Y}⏭  Skip:{X} {ch_title}")
        return True

    img_urls = fetch_chapter_images(manga_slug, ch_slug, source)
    if not img_urls:
        print(f"  {R}✗  Tidak ada gambar:{X} {ch_title}")
        return False

    tmp = manga_dir / f"_tmp_{label}"
    tmp.mkdir(parents=True, exist_ok=True)
    ok = 0
    for i, url in enumerate(img_urls, 1):
        ext  = Path(urlparse(url).path).suffix or ".jpg"
        dest = tmp / f"{i:04d}{ext}"
        sys.stdout.write(f"\r  {B}[{i:3d}/{len(img_urls)}]{X} {url[-50:]}")
        sys.stdout.flush()
        if download_image(url, dest): ok += 1
        time.sleep(0.3)

    print(f"\r  {G}✓{X} {ch_title:<38} {ok}/{len(img_urls)} hlm → ", end="")
    success = pack_cbz(tmp, cbz_path)
    if success: print(f"{G}{cbz_path.stat().st_size//1024} KB{X}")
    else: print(f"{R}GAGAL pack CBZ{X}")
    if not keep_raw:
        import shutil; shutil.rmtree(tmp, ignore_errors=True)
    return success

# ── UI ────────────────────────────────────────────────────────────────────────

def clear(): print("\033[2J\033[H", end="")

def ask(prompt, default=None):
    try:
        v = input(f"{W}{prompt}{X}").strip()
        return v if v else default
    except (KeyboardInterrupt, EOFError):
        print(f"\n{Y}Keluar.{X}"); sys.exit(0)

def header():
    print(f"{M}{BOLD}")
    print("╔══════════════════════════════════════════╗")
    print("║     COMICASO DOWNLOADER  v5.0            ║")
    print("║  v3.comicaso.pro  •  Auto CBZ Pack       ║")
    print("╚══════════════════════════════════════════╝")
    print(X)

def show_manga_list(manga_list, title_hint=""):
    PAGE = 30
    page = 0
    while True:
        clear(); header()
        start = page * PAGE
        chunk = manga_list[start:start+PAGE]
        total_pages = max(1, (len(manga_list)-1)//PAGE+1)
        print(f"{G}  {len(manga_list)} manga{X}  |  hal {page+1}/{total_pages}"
              + (f"  {D}[{title_hint}]{X}" if title_hint else ""))
        print(f"  {D}{'─'*44}{X}")
        for i, m in enumerate(chunk, start+1):
            tipe = m.get("type","")
            print(f"  {B}{i:4d}{X}. {m.get('title','?'):<40} {D}{tipe}{X}")
        print(f"\n  {Y}N{X}ext  {Y}P{X}rev  {Y}0{X}kembali")
        val = ask("  Pilih nomor / N / P / 0: ", "").upper()
        if val == "N":
            if start+PAGE < len(manga_list): page += 1
        elif val == "P":
            if page > 0: page -= 1
        elif val == "0":
            return None
        else:
            try:
                idx = int(val) - 1
                if 0 <= idx < len(manga_list):
                    return manga_list[idx]
            except: pass

def page_chapters(manga, out_dir, source):
    clear(); header()
    slug  = manga.get("slug","")
    title = manga.get("title", slug)
    print(f"{BOLD}{W}  {title}{X}\n  {D}{slug}{X}")
    print(f"  {Y}Mengambil data chapter...{X}")

    detail   = fetch_manga_detail(slug, source)
    chapters = get_chapters(detail)

    if not chapters:
        print(f"{R}  Chapter tidak ditemukan di API!{X}")
        cf = ask("  Manual — chapter mulai (misal 1): ", "1")
        ct = ask("  Manual — chapter akhir: ", cf)
        try:
            chapters = [{"slug": f"chapter-{i}", "title": f"Chapter {i}"}
                        for i in range(int(cf), int(ct)+1)]
        except:
            ask("  Input tidak valid. Enter..."); return

    clear(); header()
    print(f"{BOLD}{W}  {title}{X}")
    print(f"  {G}{len(chapters)} chapter{X}")
    print(f"  {D}{'─'*44}{X}")

    manga_dir = Path(out_dir) / sanitize(title)
    manga_dir.mkdir(parents=True, exist_ok=True)

    for i, ch in enumerate(chapters, 1):
        done = (manga_dir / f"{sanitize(ch['title'])}.cbz").exists()
        mark = f"{G}✓{X}" if done else " "
        print(f"  {mark} {B}{i:3d}{X}. {ch['title']}")

    print(f"\n  {Y}0{X}=semua  |  {Y}1-5{X}=range  |  {Y}3{X}=satu")
    val = ask("  Pilih: ", "0")

    selected = []
    if val == "0":
        selected = chapters
    elif "-" in val:
        try:
            a, b = val.split("-")
            selected = chapters[int(a)-1:int(b)]
        except:
            ask("  Format salah. Enter..."); return
    else:
        try:
            idx = int(val) - 1
            if 0 <= idx < len(chapters): selected = [chapters[idx]]
        except: pass

    if not selected:
        ask("  Tidak ada dipilih. Enter..."); return

    clear(); header()
    print(f"{BOLD}{W}  {title}{X}  —  {len(selected)} chapter")
    print(f"  {D}{manga_dir}{X}\n  {D}{'─'*44}{X}\n")

    ok = 0
    for ch in selected:
        if download_chapter(slug, ch, manga_dir, source): ok += 1
        time.sleep(DELAY)

    c = G if ok == len(selected) else Y
    print(f"\n  {c}✓ Selesai: {ok}/{len(selected)} chapter{X}")
    print(f"  {G}📁 {manga_dir}{X}\n")
    ask("  Enter untuk kembali...")

# ── Main menu ─────────────────────────────────────────────────────────────────

def main():
    global DELAY
    p = argparse.ArgumentParser()
    p.add_argument("--source", default=SOURCE)
    p.add_argument("--out",    default=str(OUT_DIR))
    p.add_argument("--delay",  type=float, default=DELAY)
    args = p.parse_args()
    DELAY = args.delay
    Path(args.out).mkdir(parents=True, exist_ok=True)

    while True:
        clear(); header()
        print(f"  {D}Output: {args.out}{X}")
        print(f"  {D}{'─'*44}{X}")
        print(f"  {B}1{X}. 🔍 Cari manga")
        print(f"  {B}2{X}. 📋 Browse terbaru (Popular)")
        print(f"  {B}3{X}. 🔥 Trending hari ini")
        print(f"  {B}4{X}. 🎭 Browse per genre")
        print(f"  {B}5{X}. ⬇  Download langsung (masukkan slug)")
        print(f"  {B}0{X}. ✕  Keluar")
        print(f"  {D}{'─'*44}{X}")
        val = ask("  Pilih: ")

        if val == "1":
            clear(); header()
            q = ask("  🔍 Kata kunci: ").strip()
            if not q: continue
            print(f"  {Y}Mencari '{q}'...{X}")
            results = fetch_home(query=q, mode="search", limit=60)
            if not results:
                print(f"{R}  Tidak ada hasil.{X}"); ask("  Enter..."); continue
            manga = show_manga_list(results, f"hasil: '{q}'")
            if manga: page_chapters(manga, args.out, args.source)

        elif val == "2":
            clear(); header()
            print(f"  {Y}Memuat manga terbaru...{X}")
            results = fetch_home(mode="update", limit=60)
            if not results:
                print(f"{R}  Gagal memuat.{X}"); ask("  Enter..."); continue
            manga = show_manga_list(results, "terbaru")
            if manga: page_chapters(manga, args.out, args.source)

        elif val == "3":
            clear(); header()
            print(f"  {Y}Memuat trending...{X}")
            results = fetch_trending(period="daily", limit=30)
            if not results:
                print(f"{R}  Gagal memuat trending.{X}"); ask("  Enter..."); continue
            manga = show_manga_list(results, "trending hari ini")
            if manga: page_chapters(manga, args.out, args.source)

        elif val == "4":
            clear(); header()
            genres = [
                ("Action","action"), ("Adventure","adventure"), ("Comedy","comedy"),
                ("Drama","drama"), ("Fantasy","fantasy"), ("Horror","horror"),
                ("Isekai","isekai"), ("Romance","romance"), ("Sci-Fi","sci-fi"),
                ("Slice of Life","slice-of-life"), ("Supernatural","supernatural"),
            ]
            print("  Pilih genre:")
            for i, (label, _) in enumerate(genres, 1):
                print(f"  {B}{i:2d}{X}. {label}")
            gval = ask("  Nomor genre: ")
            try:
                gidx = int(gval) - 1
                genre_key = genres[gidx][1]
                genre_lbl = genres[gidx][0]
            except:
                continue
            print(f"  {Y}Memuat genre {genre_lbl}...{X}")
            results = fetch_home(genre=genre_key, limit=60)
            if not results:
                print(f"{R}  Tidak ada manga.{X}"); ask("  Enter..."); continue
            manga = show_manga_list(results, genre_lbl)
            if manga: page_chapters(manga, args.out, args.source)

        elif val == "5":
            clear(); header()
            slug = ask("  Slug manga\n  (contoh: the-flower-who-bears-the-sword): ").strip()
            if not slug: continue
            manga = {"slug": slug, "title": slug.replace("-"," ").title()}
            page_chapters(manga, args.out, args.source)

        elif val == "0":
            clear(); print(f"{G}  Sampai jumpa!{X}\n"); break

if __name__ == "__main__":
    main()
