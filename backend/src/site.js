/**
 * The public face: a landing page with the waitlist form, and a privacy page. Served by the same Worker as the API,
 * so there is nothing else to host and the form posts to the same origin (no CORS, no third party).
 *
 * Self-contained on purpose: no web fonts, no analytics, no trackers, no external requests of any kind. A page that
 * asks strangers for their email should not also be telling a third party that they visited.
 *
 * The product shots are drawn in HTML and CSS from the real app's layout (sidebar, orb, panels, a chat), so they are
 * sharp at any size and cost no image requests. Every claim on the page is one the product already makes; there are
 * no numbers, testimonials or logos of other companies.
 */

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

const STYLE = `
:root{--bg:#06080d;--bg2:#0b0f17;--panel:#0e131c;--line:rgba(255,255,255,.08);--text:#eef2f7;--muted:#9aa6b8;--dim:#6f7b8e;--a:#f0a44b;--a2:#ffd79a;--c:#43e0d6}
*{box-sizing:border-box}html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--text);font:17px/1.6 "Segoe UI Variable Text","Segoe UI",system-ui,-apple-system,Roboto,Helvetica,Arial,sans-serif;-webkit-font-smoothing:antialiased;overflow-x:hidden}
a{color:inherit}a:focus-visible,button:focus-visible,input:focus-visible,summary:focus-visible{outline:2px solid var(--a2);outline-offset:3px}
.wrap{max-width:1160px;margin:0 auto;padding:0 28px}
.mono{font-family:ui-monospace,"Cascadia Mono",Consolas,monospace}
.disp{font-family:"Segoe UI Variable Display","Segoe UI",system-ui,sans-serif;letter-spacing:-.025em}
.kicker{font-size:12.5px;letter-spacing:.22em;text-transform:uppercase;color:var(--a);font-weight:600}
/* nav */
nav{position:sticky;top:0;z-index:20;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);background:rgba(6,8,13,.7);border-bottom:1px solid var(--line)}
nav .in{display:flex;align-items:center;gap:28px;height:68px}
.brand{display:flex;align-items:center;gap:12px;text-decoration:none;font-weight:600;letter-spacing:.3em;font-size:14px}
nav .links{display:flex;gap:26px;margin-left:12px;font-size:15px;color:var(--muted)}nav .links a{text-decoration:none}nav .links a:hover{color:var(--text)}
nav .sp{flex:1}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:10px;min-height:48px;padding:0 24px;border:0;border-radius:999px;background:linear-gradient(135deg,var(--a2),var(--a));color:#1a0f05;font:700 15.5px/1 inherit;cursor:pointer;text-decoration:none;box-shadow:0 8px 30px rgba(240,164,75,.28);transition:transform .15s,box-shadow .15s}
.btn:hover{transform:translateY(-1px);box-shadow:0 12px 38px rgba(240,164,75,.4)}
.btn[disabled]{opacity:.65;cursor:progress;transform:none}
.btn.sm{min-height:40px;padding:0 18px;font-size:14px}
/* hero */
.hero{position:relative;padding:84px 0 40px;text-align:center}
.hero::before{content:"";position:absolute;left:50%;top:-80px;width:1100px;height:760px;transform:translateX(-50%);background:radial-gradient(ellipse at 50% 35%,rgba(240,164,75,.2),rgba(240,164,75,.05) 45%,transparent 70%);pointer-events:none}
.pill{display:inline-flex;align-items:center;gap:9px;padding:7px 15px;border:1px solid var(--line);border-radius:999px;background:rgba(255,255,255,.03);font-size:13.5px;color:var(--muted)}
.pill i{width:7px;height:7px;border-radius:50%;background:var(--c);box-shadow:0 0 10px var(--c)}
h1{position:relative;margin:26px auto 22px;max-width:15em;font-size:clamp(44px,7.4vw,92px);line-height:1;font-weight:700}
h1 .g{background:linear-gradient(120deg,var(--a2) 10%,var(--a) 55%,#ff9a3c);-webkit-background-clip:text;background-clip:text;color:transparent}
.lede{position:relative;margin:0 auto 34px;max-width:36em;font-size:clamp(18px,2.1vw,22px);color:var(--muted)}
form.wl{position:relative;display:flex;gap:10px;flex-wrap:wrap;justify-content:center;max-width:560px;margin:0 auto}
form.wl input[type=email]{flex:1 1 260px;min-height:52px;padding:0 20px;border-radius:999px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.16);color:var(--text);font:17px/1 inherit}
form.wl input[type=email]::placeholder{color:var(--dim)}
form.wl .btn{min-height:52px}
.hp{position:absolute;left:-9999px;width:1px;height:1px;opacity:0}
.fine{position:relative;margin:16px auto 0;font-size:14px;color:var(--dim);max-width:34em}
.msg{position:relative;min-height:22px;margin:12px 0 0;font-size:14.5px;color:var(--a2)}
.done{display:none;margin:0 auto;max-width:520px;padding:20px 22px;border:1px solid rgba(67,224,214,.4);border-radius:18px;background:rgba(67,224,214,.08);text-align:left}
.done strong{display:block;font-size:19px;color:var(--c);margin-bottom:4px}
/* the app, drawn */
.stage{position:relative;margin:64px auto 0;max-width:1060px}
.stage::before{content:"";position:absolute;inset:6% -6% -10%;background:radial-gradient(ellipse at 50% 60%,rgba(240,164,75,.22),transparent 65%);filter:blur(30px);z-index:0}
.win{position:relative;z-index:1;border:1px solid rgba(240,164,75,.28);border-radius:16px;overflow:hidden;background:#120a07;text-align:left;box-shadow:0 40px 120px rgba(0,0,0,.65),0 0 0 1px rgba(255,255,255,.03) inset}
.bar{display:flex;align-items:center;gap:8px;height:34px;padding:0 14px;background:#0c0705;border-bottom:1px solid rgba(240,164,75,.12);font-size:12px;color:var(--dim)}
.bar i{width:11px;height:11px;border-radius:50%;background:#2a1d14}.bar .t{margin-left:8px;letter-spacing:.3em;font-size:11px}
.app{display:grid;grid-template-columns:200px 1fr 190px;min-height:470px;background:radial-gradient(ellipse at 50% 42%,#2a160b 0%,#120a07 62%)}
.sb{padding:16px 12px;border-right:1px solid rgba(240,164,75,.1);display:flex;flex-direction:column;gap:5px;background:rgba(10,6,4,.5)}
.sb .lg{display:flex;align-items:center;gap:9px;padding:2px 6px 12px;font-size:12px;letter-spacing:.3em;color:#f1e6d8}
.sb .new{display:flex;align-items:center;gap:8px;padding:10px 12px;border:1px solid rgba(240,164,75,.5);background:rgba(240,164,75,.12);border-radius:3px 14px 3px 14px;font-size:13px;color:#fff;margin-bottom:8px}
.sb .it{display:flex;align-items:center;gap:10px;padding:8px 12px;font-size:13px;color:#a79a8c;border-radius:6px}
.sb .it.on{background:rgba(240,164,75,.14);color:#fff;box-shadow:inset 0 0 0 1px rgba(240,164,75,.3)}
.sb .it svg{width:15px;height:15px;stroke:currentColor;fill:none;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.sb .h{font-size:9.5px;letter-spacing:.24em;color:#74685b;padding:12px 12px 4px}
.sb .ch{padding:6px 12px;font-size:12.5px;color:#a79a8c;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.sb .grow{flex:1}
.sb .acct{padding:10px 12px;border:1px solid rgba(240,164,75,.2);border-radius:3px 14px 3px 14px;background:rgba(32,18,12,.7)}
.sb .acct b{font-size:12px;font-weight:600;color:#f1e6d8}.sb .acct em{font-style:normal;font-size:8.5px;letter-spacing:.1em;background:var(--a);color:#1a0f05;padding:1px 5px;border-radius:2px;margin-left:6px}
.sb .acct u{display:block;height:3px;margin:8px 0 5px;background:rgba(240,164,75,.2);text-decoration:none}.sb .acct u::after{content:"";display:block;height:3px;width:99%;background:var(--a)}
.sb .acct span{font-size:10px;color:#85796c}
.mid{position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:26px 12px;text-align:center}
.mid .gr{font-size:clamp(15px,2.2vw,22px);letter-spacing:.22em;color:#f1e6d8}.mid .sub{margin:6px 0 0;font-size:12.5px;color:#b9a994}
.orbw{position:relative;width:230px;height:230px;margin:20px 0 14px}
.orbw i{position:absolute;border-radius:50%}
.orbw .o1{inset:0;border:1px solid rgba(240,164,75,.3)}
.orbw .o2{inset:10px;border:2px dashed rgba(240,164,75,.5);animation:spin 80s linear infinite}
.orbw .o3{inset:30px;border:5px solid transparent;border-top-color:var(--a);border-right-color:rgba(240,164,75,.25);border-bottom-color:rgba(240,164,75,.25);animation:spin 36s linear infinite reverse}
.orbw .o4{inset:56px;border:1px solid rgba(255,215,154,.55);animation:spin 50s linear infinite}
.orbw .cr{inset:78px;background:radial-gradient(circle,#fff6e6 0%,#ffd79a 24%,rgba(240,164,75,.55) 56%,transparent 78%);animation:pulse 5s ease-in-out infinite}
.mid .st{font-size:10.5px;letter-spacing:.3em;color:var(--a)}
.chips{display:flex;gap:7px;margin-top:14px;flex-wrap:wrap;justify-content:center}.chips span{padding:7px 12px;border:1px solid rgba(240,164,75,.35);border-radius:3px 10px 3px 10px;font-size:11.5px;color:#cbbba6}
.rail{padding:16px 14px 16px 6px;display:flex;flex-direction:column;gap:10px}
.rail .p{padding:11px 12px;border:1px solid rgba(240,164,75,.2);background:rgba(32,18,12,.6);border-radius:3px 12px 3px 12px;font-size:11.5px;color:#b9a994}
.rail .p h6{margin:0 0 7px;font-size:9px;letter-spacing:.24em;color:#f1e6d8;font-weight:600}
.rail .p strong{display:block;font-size:19px;color:#fff;font-weight:600;letter-spacing:-.01em}
.rail .p u{display:block;height:3px;margin-top:8px;background:rgba(240,164,75,.2);text-decoration:none}.rail .p u::after{content:"";display:block;height:3px;width:var(--w,99%);background:var(--a)}
@keyframes spin{to{transform:rotate(360deg)}}@keyframes pulse{50%{transform:scale(1.07)}}
/* logos strip / platforms */
.plat{display:flex;gap:34px;justify-content:center;flex-wrap:wrap;margin:46px 0 0;color:var(--dim);font-size:14.5px}
.plat span{display:flex;align-items:center;gap:10px}.plat svg{width:18px;height:18px;stroke:var(--muted);fill:none;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
/* sections */
section{padding:112px 0}
h2{margin:14px 0 16px;font-size:clamp(32px,4.8vw,56px);line-height:1.05;font-weight:700}
.sub2{margin:0;color:var(--muted);max-width:34em;font-size:19px}
.center{text-align:center}.center .sub2{margin:0 auto}
/* demo */
.demo{display:grid;grid-template-columns:.9fr 1.1fr;gap:56px;align-items:center}
.chat{border:1px solid var(--line);border-radius:20px;background:linear-gradient(180deg,#0f141d,#0a0e15);padding:26px;display:flex;flex-direction:column;gap:18px;min-height:380px}
.u{align-self:flex-end;max-width:82%;padding:12px 17px;border-radius:18px 18px 4px 18px;background:rgba(240,164,75,.16);border:1px solid rgba(240,164,75,.4);font-size:16px}
.j{display:flex;gap:12px;align-items:flex-start}.j .av{flex:none;width:30px;height:30px;border-radius:50%;border:1.5px solid var(--a);display:grid;place-items:center}.j .av i{width:9px;height:9px;border-radius:50%;background:var(--a2)}
.j .tx{font-size:16px;color:#dbe3ee}.j .tx small{display:inline-flex;align-items:center;gap:8px;padding:5px 11px;margin:0 0 9px;border:1px solid var(--line);border-radius:999px;font-size:12.5px;color:var(--muted)}.j .tx small b{color:#3ee6a5}
.ok{align-self:flex-start;display:inline-flex;gap:10px;align-items:center;padding:10px 14px;border:1px solid rgba(255,159,28,.5);background:rgba(255,159,28,.08);border-radius:14px;font-size:14.5px;color:#ffd79a}
.steps{display:grid;gap:14px;margin-top:30px}
.step{display:flex;gap:16px;align-items:flex-start}.step b{flex:none;width:34px;height:34px;border-radius:50%;display:grid;place-items:center;border:1px solid rgba(240,164,75,.5);color:var(--a2);font-size:14px}
.step h3{margin:0 0 3px;font-size:18px}.step p{margin:0;color:var(--muted);font-size:16px}
/* bento */
.bento{display:grid;grid-template-columns:repeat(6,1fr);gap:16px;margin-top:56px}
.bc{position:relative;padding:28px;border:1px solid var(--line);border-radius:22px;background:linear-gradient(180deg,rgba(255,255,255,.04),rgba(255,255,255,.015));overflow:hidden}
.bc.w2{grid-column:span 2}.bc.w3{grid-column:span 3}.bc.w4{grid-column:span 4}
.bc svg.ic{width:30px;height:30px;stroke:var(--a);fill:none;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round;margin-bottom:18px}
.bc h3{margin:0 0 8px;font-size:21px;letter-spacing:-.01em}.bc p{margin:0;color:var(--muted);font-size:16px}
.bc .mini{margin-top:20px;padding:14px 16px;border:1px solid var(--line);border-radius:14px;background:rgba(0,0,0,.28);font-size:14px;color:#cdd6e2}
.bc .mini .m{color:var(--dim);font-size:12px;display:block;margin-bottom:4px}
.wave{display:flex;gap:4px;align-items:center;height:34px;margin-top:18px}.wave i{width:4px;border-radius:2px;background:linear-gradient(var(--a2),var(--a));animation:eq 1.4s ease-in-out infinite}
.wave i:nth-child(odd){animation-delay:.2s}.wave i:nth-child(3n){animation-delay:.5s}.wave i:nth-child(4n){animation-delay:.8s}
@keyframes eq{0%,100%{height:8px}50%{height:32px}}
/* devices */
.dev{display:grid;grid-template-columns:1.2fr .8fr;gap:30px;align-items:center;margin-top:56px}
.lap{border:1px solid var(--line);border-radius:18px;padding:18px;background:linear-gradient(180deg,#0f141d,#0a0e15)}
.phone{width:240px;margin:0 auto;border:1px solid rgba(255,255,255,.18);border-radius:34px;padding:12px;background:#0a0e15;box-shadow:0 30px 80px rgba(0,0,0,.5)}
.scr{border-radius:24px;background:radial-gradient(ellipse at 50% 38%,#2a160b,#120a07 70%);padding:22px 14px 16px;text-align:center;min-height:430px;display:flex;flex-direction:column;align-items:center}
.scr .gr{font-size:11px;letter-spacing:.24em;color:#f1e6d8;margin-top:6px}.scr .orbw{width:150px;height:150px;margin:24px 0 18px}.scr .orbw .o4{inset:36px}.scr .orbw .o3{inset:20px;border-width:4px}.scr .orbw .cr{inset:50px}
.scr .in{margin-top:auto;width:100%;padding:11px 14px;border:1px solid rgba(240,164,75,.4);border-radius:14px;font-size:12px;color:#9a8c7c;text-align:left}
.col{display:grid;gap:14px}.col div{padding:18px 20px;border:1px solid var(--line);border-radius:16px;background:rgba(255,255,255,.03)}.col h4{margin:0 0 4px;font-size:17px}.col p{margin:0;font-size:15.5px;color:var(--muted)}
/* privacy */
.priv{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:50px}
.pc{padding:26px;border:1px solid var(--line);border-radius:20px;background:rgba(255,255,255,.025)}
.pc h3{margin:0 0 6px;font-size:19px;display:flex;align-items:center;gap:12px}.pc h3 svg{width:22px;height:22px;stroke:var(--c);fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round;flex:none}
.pc p{margin:0;color:var(--muted);font-size:16px}
/* faq */
details{border-bottom:1px solid var(--line);padding:22px 0}
summary{cursor:pointer;font-size:19px;font-weight:600;list-style:none;display:flex;justify-content:space-between;gap:20px}
summary::-webkit-details-marker{display:none}
summary::after{content:"";flex:none;width:22px;height:22px;margin-top:3px;background:linear-gradient(var(--a),var(--a)) center/14px 2px no-repeat,linear-gradient(var(--a),var(--a)) center/2px 14px no-repeat;transition:transform .2s}
details[open] summary::after{transform:rotate(45deg)}
details p{margin:12px 0 0;color:var(--muted);max-width:46em;font-size:17px}
/* cta */
.cta{position:relative;margin:0 0 70px;padding:78px 30px;border:1px solid rgba(240,164,75,.3);border-radius:32px;text-align:center;background:radial-gradient(ellipse at 50% 0%,rgba(240,164,75,.2),transparent 65%),#0b0f17;overflow:hidden}
.cta h2{margin-top:0}
footer{padding:34px 0 60px;border-top:1px solid var(--line);display:flex;gap:22px;flex-wrap:wrap;align-items:center;color:var(--dim);font-size:14.5px}footer .sp{flex:1}footer a{color:var(--muted)}
/* reveal */
.rv{opacity:0;transform:translateY(22px);transition:opacity .7s ease,transform .7s ease}.rv.in{opacity:1;transform:none}
@media (max-width:980px){.app{grid-template-columns:150px 1fr}.rail{display:none}.demo,.dev,.priv{grid-template-columns:1fr}.bento{grid-template-columns:1fr 1fr}.bc.w2,.bc.w3,.bc.w4{grid-column:span 2}nav .links{display:none}section{padding:80px 0}}
@media (max-width:640px){.app{grid-template-columns:1fr}.sb{display:none}.bento{grid-template-columns:1fr}.bc.w2,.bc.w3,.bc.w4{grid-column:auto}.hero{padding-top:52px}.orbw{width:190px;height:190px}.cta{padding:56px 20px;border-radius:24px}}
@media (prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}.rv{opacity:1;transform:none}html{scroll-behavior:auto}}
`

const LOGO = `<svg width="34" height="34" viewBox="0 0 32 32" aria-hidden="true"><circle cx="16" cy="16" r="14.5" fill="#150c07" stroke="#f0a44b" stroke-width="1.4"/><circle cx="16" cy="16" r="10.5" fill="none" stroke="#ffd79a" stroke-width="1" stroke-dasharray="3 2.2" opacity=".85"/><text x="16" y="21.5" text-anchor="middle" font-family="Segoe UI,system-ui,sans-serif" font-weight="700" font-size="14" fill="#ffe9c7">J</text></svg>`

const I = {
  chat: '<path d="M4 5.5h16v10H9l-5 4z"/>', task: '<circle cx="12" cy="12" r="8.5"/><path d="M8.5 12.2l2.4 2.4 4.6-5"/>',
  mem: '<path d="M12 3.5l1.9 4.6 4.6 1.9-4.6 1.9L12 16.5l-1.9-4.6L5.5 10l4.6-1.9z"/>', file: '<path d="M7 3.5h7l4 4v13H7z"/><path d="M14 3.5v4h4M10 12.5h5M10 16h5"/>',
  bolt: '<path d="M13 3L5.5 13.5H11L10 21l7.5-10.5H12z"/>', mic: '<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0013 0M12 18v2.5"/>',
  globe: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.5 2.6 3.5 5.4 3.5 8.5s-1 5.9-3.5 8.5c-2.5-2.6-3.5-5.4-3.5-8.5S9.5 6.1 12 3.5z"/>',
  lock: '<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 017 0v2.5"/>', eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
  hand: '<path d="M12 21c-4 0-6.5-2.6-6.5-6.5V9a1.5 1.5 0 013 0v3M8.5 12V6a1.5 1.5 0 013 0v5M11.5 11V5a1.5 1.5 0 013 0v6M14.5 11V7a1.5 1.5 0 013 0v7.5c0 3.6-2.3 6.5-5.5 6.5z"/>',
  sync: '<path d="M4 12a8 8 0 0113.7-5.6L20 8.5M20 4v4.5h-4.5M20 12a8 8 0 01-13.7 5.6L4 15.5M4 20v-4.5h4.5"/>',
  win: '<rect x="3.5" y="5" width="17" height="11.5" rx="1.5"/><path d="M9 20h6M12 16.5V20"/>', and: '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
}
const ic = (k, cls = '') => `<svg ${cls ? `class="${cls}" ` : ''}viewBox="0 0 24 24" aria-hidden="true">${I[k]}</svg>`

function form(id) {
  return `<form class="wl" id="${id}" novalidate>
<label for="${id}-email" class="hp">Email</label>
<input id="${id}-email" type="email" name="email" inputmode="email" autocomplete="email" placeholder="Your email address" required aria-label="Your email address">
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
});
const io='IntersectionObserver' in window?new IntersectionObserver(es=>es.forEach(x=>{if(x.isIntersecting){x.target.classList.add('in');io.unobserve(x.target)}}),{threshold:.12}):null;
document.querySelectorAll('.rv').forEach(el=>io?io.observe(el):el.classList.add('in'));`

const head = (title, description) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><meta name="description" content="${esc(description)}">
<meta name="theme-color" content="#06080d"><meta property="og:title" content="${esc(title)}"><meta property="og:description" content="${esc(description)}"><meta property="og:type" content="website">
<style>${STYLE}</style></head>`

const NAV = (cta = true) => `<nav><div class="wrap in"><a class="brand" href="/">${LOGO}JARVIS</a><div class="links"><a href="/#what">What it does</a><a href="/#how">How it works</a><a href="/#private">Privacy</a><a href="/#faq">FAQ</a></div><span class="sp"></span>${cta ? '<a class="btn sm" href="/#join">Join the waitlist</a>' : ''}</div></nav>`

const APP = `<div class="win" aria-hidden="true">
<div class="bar"><i></i><i></i><i></i><span class="t">JARVIS</span></div>
<div class="app">
<div class="sb">
<div class="lg">${LOGO.replace('width="34" height="34"', 'width="22" height="22"')}JARVIS</div>
<div class="new"><b style="color:var(--a)">+</b> New chat</div>
<div class="it on">${ic('chat')}Chat</div><div class="it">${ic('task')}Tasks</div><div class="it">${ic('mem')}Memory</div><div class="it">${ic('file')}Files</div><div class="it">${ic('bolt')}Routines</div>
<div class="h">TODAY</div><div class="ch">Plan my day around my priorities</div><div class="ch">Summarise the project brief</div>
<div class="grow"></div>
<div class="acct"><b>Your name</b><em>PRO</em><u></u><span>1.99M tokens left today</span></div>
</div>
<div class="mid">
<div class="gr disp">GOOD EVENING</div><p class="sub">All systems nominal. Awaiting your command.</p>
<div class="orbw"><i class="o1"></i><i class="o2"></i><i class="o3"></i><i class="o4"></i><i class="cr"></i></div>
<div class="st">SYSTEMS NOMINAL</div>
<div class="chips"><span>Run day plan</span><span>Brief me simply</span><span>Log to memory</span></div>
</div>
<div class="rail">
<div class="p"><h6>TODAY</h6>Call the bank<br><span style="color:#85796c">due 5:00 PM</span></div>
<div class="p"><h6>ALLOWANCE</h6><strong>1.99M</strong>of 2M tokens left<u></u></div>
<div class="p"><h6>THIS LAPTOP</h6>CPU 18%<u style="--w:18%"></u></div>
</div>
</div></div>`

export function renderSite() {
  return `${head('JARVIS: your own AI assistant for Windows and Android', 'JARVIS listens, remembers and gets things done on your Windows laptop and your Android phone, and checks with you first before anything it cannot undo. Join the early-access waitlist.')}
<body>
${NAV()}
<main>
<header class="hero"><div class="wrap">
<span class="pill"><i></i>Early access · Windows and Android</span>
<h1 class="disp">An assistant that actually <span class="g">gets things done.</span></h1>
<p class="lede">JARVIS lives on your laptop and your phone. Talk to it, ask it, hand it a document. It remembers what matters, takes care of the small jobs, and checks with you before anything it can't undo.</p>
${form('hero')}
<p class="fine">Join the waitlist. We'll only email you about access to JARVIS, never anything else. <a href="/privacy">How we handle your email</a>.</p>
<div class="stage rv">${APP}</div>
<div class="plat"><span>${ic('win')}Windows laptop</span><span>${ic('and')}Android phone</span><span>${ic('sync')}One account across both</span></div>
</div></header>

<section id="what"><div class="wrap">
<div class="center rv"><div class="kicker">What it does</div><h2 class="disp">Say it once. It's handled.</h2><p class="sub2">The everyday things that eat your day, done in plain language, by voice or by keyboard.</p></div>
<div class="bento">
<div class="bc w4 rv">${ic('mic', 'ic')}<h3>Talk to it, and it talks back</h3><p>Press a key or just say “Jarvis”. Ask out loud and it can answer out loud, hands-free, while you get on with something else.</p><div class="wave" aria-hidden="true">${'<i style="height:10px"></i>'.repeat(26)}</div></div>
<div class="bc w2 rv">${ic('mem', 'ic')}<h3>It remembers</h3><p>Tell it once. Read, edit or delete every memory it keeps.</p><div class="mini"><span class="m">You said</span>“My sister's birthday is the 14th.”</div></div>
<div class="bc w2 rv">${ic('task', 'ic')}<h3>Tasks and reminders</h3><p>“Remind me to call the bank at 5.” Done, and it nudges you when it's time.</p></div>
<div class="bc w2 rv">${ic('file', 'ic')}<h3>Reads your documents</h3><p>Give it a PDF or a Word file. Ask what's in it, or for a summary.</p></div>
<div class="bc w2 rv">${ic('globe', 'ic')}<h3>Looks things up</h3><p>Need today's answer, not last year's? It searches the live web and tells you what it found.</p></div>
<div class="bc w3 rv">${ic('bolt', 'ic')}<h3>Routines that run themselves</h3><p>“Every weekday at 8, brief me on my day.” It does it while you're getting ready.</p></div>
<div class="bc w3 rv">${ic('hand', 'ic')}<h3>Asks before it acts</h3><p>Sending a message or deleting something can't be undone, so JARVIS waits for your yes first. Always.</p></div>
</div></div></section>

<section id="how"><div class="wrap">
<div class="demo">
<div class="rv"><div class="kicker">How it works</div><h2 class="disp">Plain words in. Real results out.</h2>
<div class="steps">
<div class="step"><b>1</b><div><h3>Sign in with Google</h3><p>One account, so your plan and usage follow you from laptop to phone.</p></div></div>
<div class="step"><b>2</b><div><h3>Ask, or say it</h3><p>Type, press a key, or speak. You don't need special commands.</p></div></div>
<div class="step"><b>3</b><div><h3>It acts, and checks first</h3><p>Anything that can't be undone waits for your approval before it happens.</p></div></div>
</div></div>
<div class="chat rv" aria-hidden="true">
<div class="u">add a task to buy milk tomorrow</div>
<div class="j"><div class="av"><i></i></div><div class="tx"><small><b>✓</b> Added task “Buy milk” · tomorrow 9:00</small><br>Done. “Buy milk” is on your list for tomorrow morning.</div></div>
<div class="u">email the landlord that I'll pay on Friday</div>
<div class="j"><div class="av"><i></i></div><div class="tx">I've written the email. It's ready, but I haven't sent it.</div></div>
<div class="ok">Needs your OK · send this email to the landlord</div>
</div>
</div></div></section>

<section id="devices"><div class="wrap">
<div class="center rv"><div class="kicker">Everywhere you are</div><h2 class="disp">Your laptop. Your phone. One JARVIS.</h2><p class="sub2">The same assistant on both, tied to one account, so what you started on one is waiting on the other once you switch sync on.</p></div>
<div class="dev">
<div class="lap rv">${APP}</div>
<div class="rv"><div class="phone" aria-hidden="true"><div class="scr"><div class="gr disp">GOOD EVENING</div><div class="orbw"><i class="o1"></i><i class="o2"></i><i class="o3"></i><i class="o4"></i><i class="cr"></i></div><div class="mid"><div class="st" style="font-size:9px">SYSTEMS NOMINAL</div></div><div class="in">Ask JARVIS anything…</div></div></div></div>
</div></div></section>

<section id="private"><div class="wrap">
<div class="center rv"><div class="kicker">Yours</div><h2 class="disp">Private by design.</h2><p class="sub2">An assistant that knows your life should be one you can trust with it.</p></div>
<div class="priv">
<div class="pc rv"><h3>${ic('lock')}Stays on your devices</h3><p>Your chats, memory and tasks live on your own laptop and phone. They only travel between your devices if you switch sync on.</p></div>
<div class="pc rv"><h3>${ic('eye')}You can see everything</h3><p>Open its memory any time. Read it, edit it, delete it. Nothing is kept that you can't look at.</p></div>
<div class="pc rv"><h3>${ic('hand')}You grant access, one thing at a time</h3><p>Files, your screen and other permissions are off until you allow them.</p></div>
<div class="pc rv"><h3>${ic('chat')}Never speaks for you unasked</h3><p>It will not send a message or email on your behalf without your approval.</p></div>
</div></div></section>

<section id="faq"><div class="wrap" style="max-width:820px">
<div class="center rv"><div class="kicker">Questions</div><h2 class="disp">Good to know.</h2></div>
<div style="margin-top:36px" class="rv">
<details><summary>Which devices does it run on?</summary><p>A Windows laptop and an Android phone. They share one account, so your plan and usage are the same on both.</p></details>
<details><summary>Is it free?</summary><p>There's a free daily allowance so you can try it properly. A Pro plan gives you much more. People on the waitlist hear about both first.</p></details>
<details><summary>When do I get access?</summary><p>We're letting people in gradually. Join the waitlist and we'll email you when your turn comes.</p></details>
<details><summary>Does it send things without asking me?</summary><p>No. Anything that can't be undone, like sending a message or deleting something, waits for your approval.</p></details>
<details><summary>What do you do with my email address?</summary><p>We use it to tell you when JARVIS is ready for you, and for nothing else. We don't sell or share it. The <a href="/privacy">privacy page</a> has the details, including how to be removed.</p></details>
</div></div></section>

<section id="join" style="padding-top:20px"><div class="wrap">
<div class="cta rv"><div class="kicker">Early access</div><h2 class="disp">Be one of the first.</h2><p class="sub2" style="margin:0 auto 30px">Join the waitlist and we'll let you know the moment JARVIS is ready for you.</p>
${form('foot')}
<p class="fine">Only about access to JARVIS. <a href="/privacy">Privacy</a></p></div>
</div></section>
</main>
<div class="wrap"><footer><span>© JARVIS</span><span class="sp"></span><a href="/privacy">Privacy</a></footer></div>
<script>${SCRIPT}</script>
</body></html>`
}

/** [contact] is the address people write to to be removed; shown only when the owner has set one. */
export function renderPrivacy({ contact = '' } = {}) {
  const reach = contact
    ? `Email <a href="mailto:${esc(contact)}">${esc(contact)}</a> and we will delete your address.`
    : 'Reply to any email we send you and we will delete your address.'
  return `${head('Privacy · JARVIS waitlist', 'What the JARVIS waitlist collects, why, and how to be removed.')}
<body>${NAV(false)}
<div class="wrap" style="max-width:760px;padding-top:70px;padding-bottom:40px">
<div class="kicker">Privacy</div>
<h1 class="disp" style="margin:16px 0 18px;font-size:clamp(36px,6vw,58px);text-align:left;max-width:none">The waitlist, plainly.</h1>
<p class="sub2">The short version: we keep your email so we can tell you when JARVIS is ready, and nothing more.</p>
<h2 class="disp" style="font-size:26px;margin-top:46px">What we collect</h2>
<ul style="color:var(--muted);line-height:1.9;padding-left:22px">
<li>Your email address</li><li>When you signed up</li><li>Which link brought you here, if it had a tag</li>
<li>A scrambled (hashed) version of your network address, used only to stop one machine from flooding the list. We never store the address itself.</li></ul>
<h2 class="disp" style="font-size:26px;margin-top:38px">What we don't do</h2>
<p class="sub2">No analytics, no advertising trackers and no cookies on this site. We don't sell or share the list.</p>
<h2 class="disp" style="font-size:26px;margin-top:38px">What we use it for</h2>
<p class="sub2">To email you about access to JARVIS and its launch. That's all.</p>
<h2 class="disp" style="font-size:26px;margin-top:38px">How long we keep it</h2>
<p class="sub2">Until JARVIS has launched and we've invited everyone, plus up to twelve months, or sooner if you ask us to remove you.</p>
<h2 class="disp" style="font-size:26px;margin-top:38px">Where it's stored</h2>
<p class="sub2">In a database on Cloudflare's network, which also runs this site.</p>
<h2 class="disp" style="font-size:26px;margin-top:38px">Removing yourself</h2>
<p class="sub2">${reach}</p>
<footer style="margin-top:50px"><span>© JARVIS</span><span class="sp"></span><a href="/">Back to the site</a></footer>
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
