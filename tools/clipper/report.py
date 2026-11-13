"""A local review page for one batch of clips: outbox/<slug>/index.html."""
import html
import json


def _mmss(t):
    return f"{int(t // 60)}:{int(t % 60):02d}"


def write_report(folder, source_name, clips, summary=None):
    cards = []
    for c in clips:
        cards.append(f"""
  <article>
    <video src="{html.escape(c['file'])}" poster="{html.escape(c['thumb'])}" controls preload="none" playsinline></video>
    <div class="meta">
      <div class="row"><span class="rank">#{c['rank']}</span><span class="score">{c['score']}</span></div>
      <h2>{html.escape(c['title'])}</h2>
      <p class="sub">{_mmss(c['source_start'])}–{_mmss(c['source_end'])} no original · {c['duration']:.0f}s · {c['cuts']} cortes</p>
      <p class="why">{html.escape(c['reason'])}</p>
      <details><summary>Transcrição</summary><p>{html.escape(c['text'])}</p></details>
      <a href="{html.escape(c['file'])}" download>Baixar MP4</a>
    </div>
  </article>""")
    summary_html = f'<p class="sum">{html.escape(summary["explanation"])}</p>' if summary else ""
    page = f"""<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Clipes: {html.escape(source_name)}</title>
<style>
:root{{--bg:#0e0f12;--card:#17191e;--fg:#f2f2f2;--mut:#9aa0aa;--acc:#ffd400}}
*{{box-sizing:border-box}} body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif}}
.sum{{color:var(--acc)!important}} header{{padding:24px 16px 8px;max-width:1200px;margin:auto}} h1{{margin:0;font-size:22px}} header p{{color:var(--mut);margin:4px 0 0}}
main{{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:16px;padding:16px;max-width:1200px;margin:auto}}
article{{background:var(--card);border-radius:14px;overflow:hidden}}
video{{width:100%;aspect-ratio:9/16;background:#000;display:block}}
.meta{{padding:12px 14px 16px}} .row{{display:flex;justify-content:space-between;color:var(--mut);font-size:13px}}
.score{{color:var(--acc);font-weight:700}} h2{{font-size:16px;margin:6px 0}} .sub,.why{{color:var(--mut);font-size:13px;margin:4px 0}}
details{{font-size:13px;color:var(--mut);margin:8px 0}} a{{color:var(--acc);font-weight:600;text-decoration:none}}
</style></head><body>
<header><h1>{len(clips)} clipes de “{html.escape(source_name)}”</h1><p>Ordenados do melhor para o pior. Os arquivos estão nesta mesma pasta.</p>{summary_html}</header>
<main>{''.join(cards)}
</main></body></html>"""
    (folder / "index.html").write_text(page, encoding="utf-8")
    (folder / "clips.json").write_text(json.dumps({"source": source_name, "summary": summary, "clips": clips}, ensure_ascii=False, indent=2),
                                       encoding="utf-8")
