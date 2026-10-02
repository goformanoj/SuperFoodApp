/**
 * The public face: a landing page with the waitlist form, and a privacy page. Served by the same Worker as the API,
 * so there is nothing else to host and the form posts to the same origin (no CORS, no third party).
 *
 * Self-contained on purpose: no web fonts, no analytics, no trackers, no external requests of any kind. A page that
 * asks strangers for their email should not also be telling a third party that they visited.
 *
 * Every claim on the page is one the product already makes; there are no numbers, testimonials or logos.
 */

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

const STYLE = `
:root{--bg:#04101a;--panel:#0a1a28;--line:rgba(61,232,224,.18);--accent:#3de8e0;--warm:#ffc857;--text:#e6f1ff;--muted:#a9b8cb;--dim:#7f8da2}
*{box-sizing:border-box}html{scroll-behavior:smooth}
body{margin:0;background:radial-gradient(ellipse at 50% -10%,#0d2a3c 0%,var(--bg) 55%);color:var(--text);font:16px/1.6 "Segoe UI",system-ui,-apple-system,Roboto,sans-serif;-webkit-font-smoothing:antialiased}
body::before{content:"";position:fixed;inset:0;pointer-events:none;background-image:linear-gradient(rgba(61,232,224,.045) 1px,transparent 1px),linear-gradient(90deg,rgba(61,232,224,.045) 1px,transparent 1px);background-size:48px 48px;-webkit-mask-image:radial-gradient(ellipse at 50% 20%,#000 20%,transparent 75%);mask-image:radial-gradient(ellipse at 50% 20%,#000 20%,transparent 75%)}
a{color:var(--accent)}a:focus-visible,button:focus-visible,input:focus-visible{outline:2px solid var(--warm);outline-offset:3px}
.wrap{position:relative;max-width:1080px;margin:0 auto;padding:0 24px}
.mono{font-family:ui-monospace,"Cascadia Mono",Consolas,monospace}
.eyebrow{font-size:12px;letter-spacing:.28em;text-transform:uppercase;color:var(--accent)}
nav{display:flex;align-items:center;gap:14px;padding:22px 0}
nav .brand{display:flex;align-items:center;gap:12px;font-weight:600;letter-spacing:.34em;font-size:14px;color:var(--text);text-decoration:none}
nav .spacer{flex:1}
.btn{display:inline-flex;align-items:center;justify-content:center;min-height:46px;padding:0 22px;border:1px solid var(--accent);background:rgba(61,232,224,.14);color:var(--text);font:600 15px/1 inherit;letter-spacing:.02em;cursor:pointer;text-decoration:none;clip-path:polygon(10px 0,100% 0,100% calc(100% - 10px),calc(100% - 10px) 100%,0 100%,0 10px);transition:background .15s}
.btn:hover{background:rgba(61,232,224,.28)}
.btn[disabled]{opacity:.6;cursor:progress}
.btn.ghost{background:transparent;border-color:rgba(255,255,255,.22);color:var(--muted)}
.hero{display:grid;grid-template-columns:1.1fr .9fr;gap:40px;align-items:center;padding:56px 0 72px}
h1{margin:14px 0 18px;font-size:clamp(38px,6vw,64px);line-height:1.04;font-weight:650;letter-spacing:-.01em}
h1 em{font-style:normal;color:var(--accent)}
.lede{font-size:19px;color:var(--muted);max-width:34em;margin:0 0 30px}
form.wl{display:flex;gap:10px;flex-wrap:wrap;max-width:520px}
form.wl input[type=email]{flex:1 1 240px;min-height:46px;padding:0 16px;background:rgba(4,16,26,.8);border:1px solid rgba(255,255,255,.22);color:var(--text);font:16px/1 inherit}
form.wl input[type=email]::placeholder{color:var(--dim)}
.hp{position:absolute;left:-9999px;width:1px;height:1px;opacity:0}
.fine{margin:12px 0 0;font-size:13px;color:var(--dim);max-width:520px}
.msg{min-height:22px;margin:10px 0 0;font-size:14px;color:var(--warm)}
.done{display:none;padding:18px 20px;border:1px solid var(--accent);background:rgba(61,232,224,.1);max-width:520px}
.done strong{display:block;font-size:18px;color:var(--accent);margin-bottom:4px}
/* the orb */
.orb{position:relative;width:min(380px,80vw);aspect-ratio:1;margin:0 auto}
.orb i{position:absolute;border-radius:50%;display:block}
.orb .r1{inset:0;border:1px solid rgba(61,232,224,.3)}
.orb .r2{inset:7%;border:2px dashed rgba(61,232,224,.5);animation:spin 70s linear infinite}
.orb .r3{inset:15%;border:6px solid transparent;border-top-color:var(--accent);border-right-color:rgba(61,232,224,.2);border-bottom-color:rgba(61,232,224,.2);animation:spin 38s linear infinite reverse}
.orb .r4{inset:27%;border:1px solid rgba(255,200,87,.6);animation:spin 52s linear infinite}
.orb .core{inset:36%;background:radial-gradient(circle,#fff 0%,#a4fff9 22%,rgba(61,232,224,.55) 55%,rgba(61,232,224,0) 78%);animation:pulse 5s ease-in-out infinite}
.orb .tick{inset:-4%;border:1px solid rgba(61,232,224,.12)}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes pulse{50%{transform:scale(1.07);opacity:.9}}
@media (prefers-reduced-motion:reduce){.orb i{animation:none!important}html{scroll-behavior:auto}}
section{padding:64px 0;border-top:1px solid rgba(255,255,255,.06)}
h2{margin:10px 0 12px;font-size:clamp(26px,3.6vw,38px);line-height:1.15;font-weight:650}
.sub{color:var(--muted);max-width:38em;margin:0 0 34px}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}
.card{padding:22px;border:1px solid var(--line);background:rgba(10,26,40,.7);clip-path:polygon(0 0,calc(100% - 14px) 0,100% 14px,100% 100%,14px 100%,0 calc(100% - 14px))}
.card svg{width:26px;height:26px;stroke:var(--accent);fill:none;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round;margin-bottom:14px}
.card h3{margin:0 0 6px;font-size:17px}.card p{margin:0;color:var(--muted);font-size:15px}
.steps{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;counter-reset:s}
.step{padding:22px;border-left:2px solid var(--accent);background:rgba(10,26,40,.5)}
.step::before{counter-increment:s;content:"0" counter(s);display:block;font:600 13px/1 ui-monospace,Consolas,monospace;letter-spacing:.2em;color:var(--accent);margin-bottom:12px}
.step h3{margin:0 0 6px;font-size:17px}.step p{margin:0;color:var(--muted);font-size:15px}
.two{display:grid;grid-template-columns:1fr 1fr;gap:40px;align-items:center}
.panel{padding:26px;border:1px solid var(--line);background:rgba(10,26,40,.7)}
.panel ul{margin:0;padding-left:20px;color:var(--muted)}.panel li{margin:8px 0}
details{border-bottom:1px solid rgba(255,255,255,.08);padding:16px 0}
summary{cursor:pointer;font-weight:600;list-style:none;display:flex;justify-content:space-between;gap:16px}
summary::after{content:"+";color:var(--accent);font-size:20px;line-height:1}details[open] summary::after{content:"–"}
details p{margin:10px 0 0;color:var(--muted);max-width:44em}
.cta{text-align:center;padding:72px 0}.cta form.wl{margin:0 auto;justify-content:center}.cta .fine,.cta .msg,.cta .done{margin-left:auto;margin-right:auto}
footer{padding:30px 0 50px;border-top:1px solid rgba(255,255,255,.06);color:var(--dim);font-size:13px;display:flex;gap:18px;flex-wrap:wrap;align-items:center}
footer .spacer{flex:1}footer a{color:var(--muted)}
@media (max-width:820px){.hero,.two{grid-template-columns:1fr}.hero{padding-top:28px}.grid,.steps{grid-template-columns:1fr}.orb{order:-1;width:min(260px,70vw)}}
`

const LOGO = `<svg width="34" height="34" viewBox="0 0 32 32" aria-hidden="true"><circle cx="16" cy="16" r="14.5" fill="#06141f" stroke="#3de8e0" stroke-width="1.4"/><circle cx="16" cy="16" r="10.5" fill="none" stroke="#a4fff9" stroke-width="1" stroke-dasharray="3 2.2" opacity=".8"/><text x="16" y="21.5" text-anchor="middle" font-family="Segoe UI,system-ui,sans-serif" font-weight="700" font-size="14" fill="#e6fffd">J</text></svg>`

const ICONS = {
  voice: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0013 0M12 18v2.5"/></svg>',
  memory: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l1.9 4.6 4.6 1.9-4.6 1.9L12 16.5l-1.9-4.6L5.5 10l4.6-1.9z"/><path d="M18 15.5l.8 1.7 1.7.8-1.7.8-.8 1.7-.8-1.7-1.7-.8 1.7-.8z"/></svg>',
  tasks: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M8.5 12.2l2.4 2.4 4.6-5"/></svg>',
  files: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3.5h7l4 4v13H7z"/><path d="M14 3.5v4h4M10 12.5h5M10 16h5"/></svg>',
  web: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.5 2.6 3.5 5.4 3.5 8.5s-1 5.9-3.5 8.5c-2.5-2.6-3.5-5.4-3.5-8.5S9.5 6.1 12 3.5z"/></svg>',
  devices: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="2.5" y="5" width="13" height="9" rx="1.5"/><path d="M6 17.5h6M9 14v3.5"/><rect x="17" y="8.5" width="4.5" height="8.5" rx="1"/></svg>',
}

function form(id) {
  return `<form class="wl" id="${id}" novalidate>
<label for="${id}-email" class="hp" style="position:absolute;left:-9999px">Email</label>
<input id="${id}-email" type="email" name="email" inputmode="email" autocomplete="email" placeholder="you@example.com" required aria-label="Your email address">
<input class="hp" type="text" name="company" tabindex="-1" autocomplete="off" aria-hidden="true">
<button class="btn" type="submit">Join the waitlist</button>
</form>
<p class="msg" role="status" aria-live="polite" data-for="${id}"></p>
<div class="done" role="status" data-done="${id}"><strong>You're on the list.</strong>We'll email you when JARVIS is ready for you. Nothing else.</div>`
}

const SCRIPT = `
const ref=new URLSearchParams(location.search).get('ref')||'';
document.querySelectorAll('form.wl').forEach(f=>{
  f.addEventListener('submit',async e=>{
    e.preventDefault();
    const id=f.id,msg=document.querySelector('[data-for="'+id+'"]'),done=document.querySelector('[data-done="'+id+'"]'),btn=f.querySelector('button');
    const email=f.elements.email.value.trim();
    if(!email){msg.textContent='Please enter your email address.';return}
    btn.disabled=true;btn.textContent='Joining…';msg.textContent='';
    try{
      const r=await fetch('/waitlist',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({email,company:f.elements.company.value,source:ref})});
      if(r.ok){f.style.display='none';msg.textContent='';done.style.display='block';return}
      msg.textContent=r.status===429?'Too many tries from this network. Please try again in a little while.':'That email address doesn\\u2019t look right. Please check it.';
    }catch(_){msg.textContent='Couldn\\u2019t reach the server. Check your connection and try again.'}
    btn.disabled=false;btn.textContent='Join the waitlist';
  });
});`

const head = (title, description) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><meta name="description" content="${esc(description)}">
<meta name="theme-color" content="#04101a"><meta property="og:title" content="${esc(title)}"><meta property="og:description" content="${esc(description)}"><meta property="og:type" content="website">
<style>${STYLE}</style></head>`

export function renderSite() {
  return `${head('JARVIS — your own AI assistant, on your laptop and phone', 'JARVIS talks, remembers and gets things done, on your Windows laptop and your Android phone. Join the early-access waitlist.')}
<body><div class="wrap">
<nav><a class="brand" href="/">${LOGO}JARVIS</a><span class="spacer"></span><a class="btn ghost" href="#join">Join the waitlist</a></nav>

<header class="hero">
<div>
<div class="eyebrow mono">Early access · Windows and Android</div>
<h1>Your own <em>JARVIS.</em></h1>
<p class="lede">An assistant that lives on your laptop and your phone. You talk to it, it remembers what matters, and it gets things done, asking first before anything it can't undo.</p>
${form('hero')}
<p class="fine">We'll only email you about access to JARVIS. No spam, and you can ask us to delete your address any time. <a href="/privacy">How we handle it</a>.</p>
</div>
<div class="orb" aria-hidden="true"><i class="tick"></i><i class="r1"></i><i class="r2"></i><i class="r3"></i><i class="r4"></i><i class="core"></i></div>
</header>

<section id="what">
<div class="eyebrow mono">What it does</div>
<h2>Built for the everyday things.</h2>
<p class="sub">Not a chat window you have to babysit. JARVIS listens, remembers, and does the small jobs that eat your day.</p>
<div class="grid">
<div class="card">${ICONS.voice}<h3>Talk to it</h3><p>Press a key or just say “Jarvis”. Ask out loud and it can answer out loud.</p></div>
<div class="card">${ICONS.memory}<h3>It remembers</h3><p>Tell it something once. It keeps a memory you can read, edit and delete, and uses it when it helps.</p></div>
<div class="card">${ICONS.tasks}<h3>Tasks and reminders</h3><p>“Remind me to call the bank at 5.” Done, and it nudges you when the time comes.</p></div>
<div class="card">${ICONS.files}<h3>Reads your documents</h3><p>Hand it a PDF or a Word file and ask what's in it, or for a summary.</p></div>
<div class="card">${ICONS.web}<h3>Looks things up</h3><p>Need today's answer, not last year's? It can search the live web and tell you what it found.</p></div>
<div class="card">${ICONS.devices}<h3>Laptop and phone</h3><p>One account across a Windows laptop and an Android phone, with your plan and usage following you.</p></div>
</div>
</section>

<section id="how">
<div class="eyebrow mono">How it works</div>
<h2>Simple on purpose.</h2>
<div class="steps" style="margin-top:28px">
<div class="step"><h3>Sign in with Google</h3><p>One account for every device you use JARVIS on.</p></div>
<div class="step"><h3>Ask, or say it</h3><p>Type, press a key, or speak. Plain language is fine.</p></div>
<div class="step"><h3>It acts, and checks first</h3><p>Anything that can't be undone, like sending a message, waits for your yes.</p></div>
</div>
</section>

<section id="private">
<div class="two">
<div>
<div class="eyebrow mono">Yours</div>
<h2>Private by design.</h2>
<p class="sub" style="margin-bottom:0">Your chats, memory and tasks are stored on your own device, and only shared between your devices if you switch that on. Only what you send to JARVIS is processed on our server, and you can see, change or delete everything it remembers about you.</p>
</div>
<div class="panel"><ul>
<li>Stored on your own devices, and shared between them only if you switch sync on</li>
<li>A memory you can open, read and clear</li>
<li>Permissions you grant, one at a time</li>
<li>Nothing sent on your behalf without your approval</li>
</ul></div>
</div>
</section>

<section id="faq">
<div class="eyebrow mono">Questions</div>
<h2>Good to know.</h2>
<div style="max-width:760px;margin-top:20px">
<details><summary>Which devices does it run on?</summary><p>A Windows laptop and an Android phone. They share one account, so your plan and usage are the same on both.</p></details>
<details><summary>Is it free?</summary><p>There's a free daily allowance so you can try it for real. A Pro plan gives you much more. Waitlist members hear about both first.</p></details>
<details><summary>When do I get access?</summary><p>We're letting people in gradually. Join the waitlist and we'll email you when your turn comes.</p></details>
<details><summary>What do you do with my email?</summary><p>Use it to tell you when JARVIS is ready for you, and nothing else. We don't sell it or share it. Read the short <a href="/privacy">privacy page</a>.</p></details>
</div>
</section>

<section class="cta" id="join">
<div class="eyebrow mono">Early access</div>
<h2>Be among the first.</h2>
<p class="sub" style="margin-left:auto;margin-right:auto">Join the waitlist and we'll let you know when JARVIS is ready.</p>
${form('foot')}
<p class="fine">We'll only email you about access to JARVIS. <a href="/privacy">Privacy</a></p>
</section>

<footer><span>© JARVIS</span><span class="spacer"></span><a href="/privacy">Privacy</a></footer>
</div>
<script>${SCRIPT}</script>
</body></html>`
}

/** [contact] is the address people write to to be removed; shown only when the owner has set one. */
export function renderPrivacy({ contact = '' } = {}) {
  const reach = contact
    ? `Email <a href="mailto:${esc(contact)}">${esc(contact)}</a> and we will delete your address.`
    : 'Reply to any email we send you and we will delete your address.'
  return `${head('Privacy — JARVIS waitlist', 'What the JARVIS waitlist collects, why, and how to be removed.')}
<body><div class="wrap" style="max-width:720px">
<nav><a class="brand" href="/">${LOGO}JARVIS</a></nav>
<div class="eyebrow mono" style="margin-top:28px">Privacy</div>
<h1 style="font-size:clamp(30px,5vw,44px)">The waitlist, plainly.</h1>
<p class="lede">The short version: we keep your email so we can tell you when JARVIS is ready, and nothing more.</p>
<h2>What we collect</h2>
<ul class="mono" style="color:var(--muted);line-height:1.9">
<li>Your email address</li>
<li>When you signed up</li>
<li>Which link brought you here, if it had a tag</li>
<li>A scrambled (hashed) version of your network address, used only to stop one machine from flooding the list. We never store the address itself.</li>
</ul>
<h2>What we don't do</h2>
<p class="sub">No analytics, no advertising trackers and no cookies on this site. We don't sell or share the list.</p>
<h2>What we use it for</h2>
<p class="sub">To email you about access to JARVIS and its launch. That's all.</p>
<h2>How long we keep it</h2>
<p class="sub">Until JARVIS has launched and we've invited everyone, plus up to twelve months, or sooner if you ask us to remove you.</p>
<h2>Where it's stored</h2>
<p class="sub">In a database on Cloudflare's network, which also runs this site.</p>
<h2>Removing yourself</h2>
<p class="sub">${reach}</p>
<footer><span>© JARVIS</span><span class="spacer"></span><a href="/">Back to the site</a></footer>
</div></body></html>`
}

/** The headers every page gets: no framing, no sniffing, and a policy that allows only what the page actually uses. */
export const PAGE_HEADERS = {
  'content-type': 'text/html; charset=utf-8',
  'cache-control': 'public, max-age=300',
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
}
