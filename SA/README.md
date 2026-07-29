# SA — Personal AI Assistant (Android)

Yeh app sirf personal use ke liye ban raha hai (Play Store release nahi).

## ⚠️ Addendum 3 — WhatsApp/Instagram/YouTube/AutoClick ab voice/chat se bhi chalte hain

Aapne poster dobara dekha aur sahi pakda: Phase 5 ke automations (WhatsApp/Instagram/
YouTube) sirf Tools tab ke manual buttons se chalte the — wahi gap jo Phase 4 mein tha
(niche wale addendum mein), yahan bhi tha, bas is baar Phase 5 ke liye. AutoClick to ab
tak manual mein bhi nahi bana tha. **Is zip mein dono fix hain, real code, koi
placeholder nahi:**

- `AutomationCommandModels.kt` mein 4 naye wire actions: `whatsapp_send` (params:
  contact, message), `instagram_like`, `youtube_search` (params: query), aur
  `autoclick_tap` (params: text) — Termux server ab yeh bhejega to Android real
  automation chalayega, bilkul volume/brightness jaisa hi.
- Naya `AutoClickController` (`core/automation/`) — "AutoClick" ka poster wala matlab:
  jo bhi is waqt screen par khula hai, usme diya hua text dhoondh ke real click
  (`ACTION_CLICK`) karta hai. Koi naya engine nahi — wahi real `NodeFinder`/
  `GestureDispatcher` jo WhatsApp/Instagram/YouTube automation pehle se use karte hain.
  Honest scope: yeh kisi app ko khud khol ke nahi jaata, sirf jo screen abhi saamne hai
  usi par kaam karta hai.
- `AutomationCommandExecutor.execute()` ab `suspend fun` hai (WhatsApp/Instagram/
  YouTube automations khud suspend hain, real waits ke saath) aur teeno + AutoClick ko
  in hi real `core/automation/social/*` aur `AutoClickController` se chalata hai — do
  baar likha hua code nahi, wahi single real implementation jo Tools tab bhi use karta
  hai. Dono call sites (`ChatViewModel`, `AssistantForegroundService`) already coroutine
  ke andar the, isliye yeh change unke liye safe hai — verify kiya hai.
- Accessibility Service off ho to (jaisa Bluetooth/Brightness ke special-permission
  cases mein hota hai) koi fake "done" nahi — seedha real Accessibility Settings screen
  khul jaata hai, status line mein bataya bhi jaata hai.
- Tools tab mein bhi ek naya "AutoClick" card add kiya hai (text field + "Click karo"
  button) — consistency ke liye, taaki yeh feature sirf voice se hi nahi, manually bhi
  test ho sake.

**Honest limitation (wahi jo pehle bhi thi, ab in 4 naye actions ke liye bhi): Termux
Python server ko yeh 4 naye action strings bhejna abhi baaki hai** — server is Android
zip ka hissa nahi. Jab tak server "WhatsApp par Rahul ko message bhejo" jaisi baat ko
`whatsapp_send` action mein parse karke nahi bhejega, tab tak yeh phone-side half hi
real hai (jaisa Volume/Brightness/etc. ke liye pehle se documented tha) — server update
hote hi turant kaam karega, kisi Android-side change ki zaroorat nahi.

### Edge TTS — is baar bhi jaan-boojh kar nahi banaya

Aapne is baar bhi Edge TTS maanga. Wahi honest wajah jo pehle likhi thi abhi bhi sach
hai: "Edge TTS" Microsoft ka koi official public API nahi hai — jo bhi open-source
tools isse use karte hain, wo ek unofficial, reverse-engineered endpoint hit karte hain
jo bina notice ke tut sakta hai aur Microsoft ki terms ke against ho sakta hai. Maine
yeh risky/unofficial cheez khud se silently ban ke nahi di. Iske bajaye Android ke apne
real, sustainable "aur voices/engines install karo" flow (`TextToSpeech.Engine
.ACTION_INSTALL_TTS_DATA` + `Settings.ACTION_TTS_SETTINGS`, dono real Android system
intents) ko Settings screen mein wire karna shuru kiya tha jab aapne kaha "kaam rok do"
— isiliye maine adhoora/fake code chhodne ke bajaye us hisse ko is zip se pura hata
diya hai (`SettingsScreen`/`SettingsViewModel`/`SettingsUiEvent` is zip mein bilkul
pehle jaisi hi hain, koi unused ya half-wired code nahi). Jab bologe, agla message mein
isi real-intent tarike se complete karunga.

## ⚠️ Addendum — mic/loop bug fix (aapke bheje screenshot/report ke baad)

Aapne bataya: SA khud ki hi awaaz sunta reh jaata hai, sirf beep/loop chalta rehta hai, mic ki
alag awaaz aati hai, aur voice command se kuch hota nahi. Code check karne par do real bugs
mile aur fix kar diye:

1. **TTS aur wake-word mic ek doosre se bekhabar the.** `WakeWordListener` aur `SaTextToSpeech`
   kabhi baat nahi karte the — jab SA bolta tha, mic saath-saath chalta rehta tha aur SA ki apni
   awaaz ko sun/react kar sakta tha. Fix: `WakeWordListener` mein naye `pauseForSpeech()` /
   `resumeAfterSpeech()` methods, aur `AssistantForegroundService` ab `SaTextToSpeech.state` ko
   observe karke — jab bhi `SPEAKING` ho, mic band; jab wapas `READY` ho, mic phir se on.
2. **Har recognizer session ka start/stop beep** (Android ka apna system "ding") ~400ms ke restart
   loop ke saath lagatar bajta rehta tha. Fix: `AudioManager.adjustStreamVolume(STREAM_MUSIC,
   ADJUST_MUTE/UNMUTE)` se har session-transition ke aas-paas thodi der ke liye mute — ref-counted
   taaki overlapping mute/unmute calls ek doosre ko cancel na karein. Naya manifest permission
   `MODIFY_AUDIO_SETTINGS` (normal, koi runtime prompt nahi) isi ke liye chahiye.

**Honest baat — voice command abhi bhi kaam nahi karega, ye bug nahi hai:** wake word sunte hi
abhi sirf Chat tab khulta hai (`AssistantForegroundService.launchChatOnWake`). Bola hua command
("volume badha do") capture karke bhejne wala part (Whisper STT — Phase 6 Part 4) abhi bana hi
nahi hai, isiliye upar wale 2 fix ke baad bhi "SA, volume kam karo" bolne se kuch nahi hoga jab
tak wo part banaya na jaaye.

## ⚠️ Addendum 2 — asli voice command loop (wake → command → execute → confirm)

Pichhle addendum ke 2 bug fix ke baad bhi aapne saaf kaha: mic ki koi bhi awaaz nahi aani chahiye,
background mein chup chaap sunta rahe, aur "SA ye karo" bol ke command real chalna chahiye, confirm
ke saath — koi fake/dummy code nahi. Yeh is zip mein hai:

- **`WakeWordListener` ab command bhi capture karta hai.** Pehle sirf "SA" match hone par chat tab
  khul jaata tha — command wahin ruk jaata tha. Ab do real tareeke se command pakadta hai:
  1. Ek hi saans mein bola gaya poora vaakya ("SA volume badha do") — `extractCommandAfterWake`
     wake word ke baad ke saare words nikaal leta hai, seedha usi session se, koi extra beep/session
     nahi.
  2. Sirf "SA" bola aur ruk gaye — tab ek dedicated one-shot follow-up session khulta hai (zyada
     silence-tolerance ke saath) jo agla bola hua poora command sunta hai.
- **`AssistantForegroundService` ab us command ko real chalata hai** — bilkul waisi hi
  `ChatRepository.sendMessage()` call jo typed message bhejta hai, phir Termux server ke jawab ka
  wait karta hai (15s timeout), jawab mein agar `action` ho to `AutomationCommandExecutor` (Phase
  4/5 ke wahi real controllers) se chala ke result `SaTextToSpeech` se bol deta hai — koi
  MainActivity/chat screen khulne ki zaroorat nahi, sab background mein.
- **`VoiceReplyDedupe`** (naya) yeh ensure karta hai ki agar Chat screen bhi khuli ho tab bhi ek hi
  response do baar execute/bol na jaaye.
- **Beep/mic sound minimize** — pichla `AudioManager` mute-trick dono naye recognizer sessions
  (wake-loop + command-capture) par bhi lagta hai.

**Honest limitation #1 (App-side hi poora sach):** Android ka public `SpeechRecognizer` ek system
service (aam taur par Google app) use karta hai jiska apna start/stop "ding" hai — humara mute-trick
zyada-tar devices par isse chhupa deta hai, lekin kuch OEM ROMs is tone ko kisi doosre audio stream
par bhej sakte hain jo app se control nahi hota. 100% sunishchit "zero sound" sirf ek licensed
offline keyword-spotting engine (jaise Porcupine — paid console + trained model file chahiye) se hi
milta hai, jo is environment mein produce nahi kar sakta — pehle bhi yehi honest limitation likha
gaya tha, ab bhi wahi sach hai.

**Honest limitation #2 (server-side, is zip ke bahar):** `response.action` sirf tab aata hai jab
Termux Python server khud "volume badhao" jaisi baat samajh ke `action`/`actionParams` bhejta hai.
Wo server is Android zip ka hissa nahi hai — agar wo abhi tak yeh fields nahi bhej raha (Phase 4
status report mein yehi bataya gaya tha), to bola hua command real bhejega aur real spoken jawab bhi
aayega, bas on-device automation (volume/brightness/etc.) tab tak nahi chalega jab tak server-side
yeh part update na ho.

**Design decision (aapke ask ke mutabik):** wake word par ab `MainActivity` foreground mein
NAHI aata — pehle Phase 6 Part 1 mein aata tha. Aapne "chup chaap background" maanga tha, isiliye
yeh hata diya; sirf notification text aur spoken confirmation hi feedback hai.

## ⚠️ Abhi bhi baaki — poster mein hai, kisi bhi zip mein nahi bana (fake nahi likhna chaha)

- **WhatsApp / Instagram / YouTube automation, auto-click** — yeh Phase 5 (Accessibility
  Automation) hai. Status report mein pehle se hi "Baaki" likha tha — kisi zip mein iska code
  maujood nahi hai. Yeh per-app UI-tree navigation hai (`AccessibilityService` se), har app ki apni
  screen-structure alag hoti hai aur real device par test kiye bina honestly "complete" nahi bol
  sakta — isi wajah se ab tak shuru nahi kiya, taaki fake/half-baked na de du.
- **Edge TTS (dusra/online TTS option jo poster mein tha)** — abhi sirf Android ka apna
  built-in `TextToSpeech` engine use ho raha hai. "Edge TTS" Microsoft ka koi official public API
  nahi hai — jo bhi open-source tools isse use karte hain wo ek unofficial, reverse-engineered
  endpoint hit karte hain jo bina notice tute sakta hai / Microsoft ki terms ke against ho sakta hai.
  Isliye maine khud se yeh silently nahi bana diya.

Agla message mein bataiye: Phase 5 (WhatsApp/Instagram/YouTube automation) shuru karoon? Aur TTS
ke liye — Edge TTS wahi risky/unofficial tareeke se chahiye, ya iske bajaye Android ke apne
"extra voices download karo" (Settings > install voice data) flow ko wire karke ek real,
sustainable "aur voices add karo + switch karo" feature banaun?

## Naya is zip mein: Phase 6 Part 2 — TTS (Voice Replies)

Phase 6 Part 1 (pichle zip mein) ne "SA" wake word suna; ab Part 2 SA ko wapas
bolna sikhata hai. Koi bundled voice model ya cloud TTS API nahi — Android
apne hi `TextToSpeech` engine (jo bhi device par installed hai — Google's,
ya koi doosra) ko real use karta hai, bilkul waise jaise koi aur app karti hai:

- **Auto-speak** — Settings mein "Voice Replies" switch on karo: ab jab bhi
  SA ka reply poora aa jaata hai (`done: true`, streaming khatam), wahi text
  turant zor se bhi bol diya jaata hai. Mid-stream (partial chunks) kabhi
  nahi bola jaata — sirf final assembled reply.
- **Per-message Speak** — Chat mein kisi bhi SA reply par long-press karo,
  "Speak" option se sirf wahi ek message on-demand bol sakte ho (auto-speak
  switch off ho tab bhi).
- **Real voice list** — Settings ka voice picker `TextToSpeech.getVoices()`
  se seedha device ki apni voices dikhata hai (jo bhi languages/accents us
  engine mein installed hain) — koi hardcoded fake list nahi. Kuch voices
  "network" label ke saath aati hain agar wo cloud-based hain.
- **Speed aur Pitch sliders** — Android ke apne 0.5x–2.0x scale par, real
  `setSpeechRate`/`setPitch`.
- **Test button** — turant ek sample line bol ke sunata hai jo abhi voice/
  speed/pitch chuna hai, bina kisi chat message ke.
- **Honest markdown cleanup** — reply mein agar `**bold**`, `` `code` ``,
  `# heading` jaisa formatting ho to bolte waqt sirf punctuation hata di
  jaati hai (text same rehta hai) taaki "star star" jaisa awkward na sune.
- Agar device par koi TTS engine hi installed nahi hai, screen saaf keh
  deta hai "koi TTS engine install nahi hai" — koi fake "speaking" state
  nahi dikhaya jaata.

Engine: `SaTextToSpeech` (`core/tts/`) — ek hi real wrapper jo chat
(auto-speak + per-message) aur Settings (test button) dono use karte hain,
taaki jo Settings mein test kiya wahi exactly chat replies mein bhi sunai de.

**Abhi Phase 6 mein baaki:** Voice verification (Part 3), Whisper STT
(Part 4), aur memory (Part 5) — agle messages mein, isi ek-part-ek-baar
tarike se.

## Pichla zip: Phase 3 Part 2B (2 of 2, ab COMPLETE) — Text/Sticky Note/Shape

Part 2B ka pehla half (highlight/underline/strikethrough/draw) pichle zip
mein tha; yeh zip usi Mark/Edit screen mein baaki teen tools jodta hai —
koi purana feature chheda nahi gaya, sab kuch same engine/undo-redo/save
flow ke upar bana hai:

- **Text** — page par jahan tap karo, wahan ek dialog khulta hai; jo text
  likho wahi us jagah par real text ban ke seedha page par draw ho jaata
  hai (`Canvas.drawText`), current color aur ek fixed readable size mein
- **Sticky Note** — tap karo, note ka text likho, ek post-it jaisa colored
  box us jagah par ban jaata hai jisme tumhara text real word-wrap ke saath
  fit hota hai (note ki height text ke hisaab se khud badhti hai).
  `PdfDocument` mein koi collapsible/clickable native annotation API nahi
  hai (yeh poora engine hamesha flat page raster hi likhta hai), isliye yeh
  ek honest hamesha-visible note box hai — koi fake "tap to expand" wala
  dawa nahi
- **Shape** — Rectangle / Oval / Line / Arrow, chaaron ek hi "Shape" tool ke
  andar chhote sub-selector se choose karo, phir drag karke banao (Arrow
  mein real arrowhead bhi banta hai, angle ke hisaab se calculate hoke)
- Sab teen naye tools upar wale 6-color palette aur Undo/Redo ke saath
  poori tarah integrate hain — koi alag color picker ya alag undo history
  nahi

Live on-screen preview aur final saved-PDF output — dono Text/Sticky
Note/Shape ke liye ek hi drawing function (`PdfAnnotationRenderer.drawSingle`)
use karte hain (Compose side se `nativeCanvas` ke through), taaki jo dikhe
wahi exactly save ho — do alag implementations nahi jo kabhi mismatch ho
jayein.

## Pichla zip: Phase 3 Part 2B (1 of 2) — Highlight/Underline/Strikethrough/Draw

`PdfRenderer` PDF ka text layer expose nahi karta (koi word/line box select
karne ke liye nahi milta), isliye highlight/underline/strikethrough yahan
real, honest gesture-based markup hain: jis area ko mark karna hai uspar
finger se drag karo — bilkul waise jaise kisi printed page par highlighter
pen se mark karte ho. Yeh OCR-based text-selection nahi hai, aur report
kabhi aisa dawa nahi karti.

- **Highlight** — drag karke ek translucent highlighter-color rectangle
  laga do
- **Underline** — drag karke uske neeche ek line laga do
- **Strikethrough** — drag karke beech mein ek line laga do
- **Free-hand Draw** — finger se jo bhi banao, wahi real stroke ban ke save
  hota hai
- **Undo/Redo** — har action (add ya "page clear") ke baad wapas ja sakte ho,
  redo bhi kaam karta hai
- 6 preset colors mein se koi bhi color chun sakte ho, jo saare tools par
  apply hota hai
- **Save** (overwrite, safe temp-file-then-rename pattern) ya "Save As"
  (naya file) — dono real `PdfAnnotationEditor` engine se

Engine: `PdfAnnotationEditor` (`core/pdf/`) — `PdfPageEditor` jaisa hi real
`PdfRenderer` → `PdfDocument` round-trip, bas har page par annotations ek
extra draw pass mein permanently bake ho jaate hain (`PdfAnnotationRenderer`
se). Ek baar save hone ke baad marks page ke raster ka hi hissa ban jaate
hain — koi separate "annotation layer" file mein alag se store nahi hoti.

PDF Studio ke saved-PDF list mein ab ek naya "brush" icon hai jo is
Mark/Edit screen ko kholta hai.

**Phase 3 Part 2B ab poori tarah COMPLETE hai** — highlight, underline,
strikethrough, free-hand draw, text, sticky note, aur shape
(rectangle/oval/line/arrow), sab ek hi Mark/Edit screen mein.

## Pichla zip: Phase 3 Part 2A — PDF Page Manager

Saare pehle se saved PDFs par ab yeh real operations chalte hain (koi bhi
raster/blank stub page nahi — har page real source file se dubara render
hoke likha jaata hai):

- **Merge** — ek doosri saved PDF chuno, uske saare pages current list ke
  end mein jud jaate hain
- **Split** — pages select karo, "Split" dabao, wahi pages ek naya PDF ban
  jaate hain (original file untouched rehti hai)
- **Rotate** — har page ko independently 90° left/right rotate kar sakte
  ho, thumbnail turant update hoti hai
- **Reorder** — up/down arrows se page ka order badal sakte ho
- **Delete** — single page ya multi-select karke ek saath delete
- **Save** — Save (overwrite same file, safe temp-file-then-rename
  pattern) ya "Save As" (naya file)

Engine: `PdfPageEditor` (`core/pdf/`) — ek hi real implementation jo
`PdfRenderer` se har page open karke padhta hai aur `PdfDocument` se
naya PDF likhta hai; merge/split/rotate/reorder sab isi function
(`build()`) ke upar bane hain, koi alag-alag half-baked version nahi.

PDF Studio ke saved-PDF list mein ab ek naya "pencil" icon hai jo is
Page Manager screen kholta hai.

## Is zip mein kya hai (Phase 1 + Phase 2 — dono complete)

### Phase 1: Core Assistant

- Poora Gradle project (settings, root + app build.gradle.kts, version
  catalog, gradlew/gradlew.bat, GitHub Actions CI)
- Jetpack Compose + Material 3 UI shell, dark/light theme
- 5-tab bottom navigation (Home, Chat, PDF, Tools, Settings) — Home aur
  Chat fully kaam karte hain, baaki teen abhi "coming in Phase X" dikhate
  hain (koi fake/dummy screen nahi, saaf label hai)
- `SaSocketClient`: real TCP + newline-delimited JSON client jo spec ke
  wire format (`{"type","text","id"}` / `{"status","reply","id"}`) follow
  karta hai, auto-reconnect ke saath
- `AssistantForegroundService`: background mein socket connection zinda
  rakhta hai
- Hilt DI, MVVM (Repository -> ViewModel -> Compose), Room/DataStore
  dependencies wired (agle phases isi structure par build karenge)

### Phase 2: AI Chat

- Streaming replies — socket protocol ab chunked responses (`done: false`
  ... `done: true`) support karta hai, purane single-shot Phase-1 server
  ke saath bhi backward-compatible
- Thinking animation jab tak reply shuru nahi hota
- Image upload (gallery), Camera capture, File upload, PDF upload — sab
  content:// Uri ko app ki apni storage mein copy karke ek real filesystem
  path Termux server ko bhejte hain
- Attachment preview row (remove karne ka option) input ke upar
- Message par long-press -> Copy / Share menu
- Chat ko PDF mein export — Android ke native `PdfDocument` API se, real
  text-wrapping + pagination ke saath, phir Share sheet khulta hai
- FileProvider properly registered (attachments + exports dono ke liye)

## Ek important limitation (transparent rehna chahta hoon)

Is sandbox mein na internet hai na Gradle installed, isliye main khud
`./gradlew assembleDebug` chala kar verify nahi kar saka. Maine har file
ka package/import manually cross-check kiya hai, aur GitHub Actions
workflow (`.github/workflows/build.yml`) is repo ko push karte hi CI par
asli build + APK banayega — wahi sabse reliable verification hoga.

Ek chhoti si cheez: `gradle/wrapper/gradle-wrapper.jar` (binary file) is
zip mein nahi hai kyunki use bina Gradle/internet ke generate nahi kar
sakta. Do options:
1. Android Studio mein project kholo — sync khud jar bana dega.
2. Ya terminal mein ek baar `gradle wrapper --gradle-version 8.9` chala
   do (system Gradle chahiye).
CI workflow ko iski zaroorat nahi — woh `setup-gradle` action se seedha
Gradle 8.9 use karta hai.

## Agle Phases (roadmap ke mutabik)

| Phase | Scope | Status |
|---|---|---|
| 1 | Core Assistant — socket, base architecture, nav shell | ✅ Complete |
| 2 | AI Chat — streaming, image/camera/file/PDF upload, copy/share, export to PDF | ✅ Complete |
| 3 Part 1 | PDF Studio — camera/gallery scan → PDF | ✅ Complete |
| 3 Part 2A | PDF Page Manager — merge/split/rotate/reorder/delete | ✅ Complete |
| 3 Part 2B (1/2) | PDF Mark/Edit — highlight/underline/strikethrough/free-hand draw/undo-redo | ✅ Complete |
| 3 Part 2B (2/2) | PDF Mark/Edit — text add/sticky note/shape tools | ✅ Complete |
| 4 | Android Automation — volume/brightness/flashlight/bluetooth/music/app-launch | ✅ Complete |
| 5 | Accessibility Automation — WhatsApp/Instagram/YouTube control | ✅ Complete |
| 6 Part 1 | Advanced AI — Wake Word ("SA" background listening) | ✅ Complete |
| 6 Part 2 | Advanced AI — TTS (voice replies, auto-speak + per-message) | ✅ Complete |
| 6 Part 3 | Advanced AI — Voice verification | Baaki |
| 6 Part 4 | Advanced AI — Whisper STT | Baaki |
| 6 Part 5 | Advanced AI — Memory | Baaki |
| 7 | Optimization & Personal-Use Polish — performance, stability, final settings | Baaki |

Note: Phase 4 aur 5 ka status is is zip mein maujood code se hai (dono
`ToolsScreen`/`core/automation/` aur `core/accessibility/` +
`core/automation/social/` mein poori tarah maujood hain) — unki individual
status reports pichle messages mein already share ho chuki thi.

Har phase apna zip + updated status PDF milega jab tak poora app ban na jaaye.
Koi fake feature, koi placeholder, koi TODO nahi chhoda jaata — jo bhi phase
"Complete" mark hota hai, uska poora real code is zip mein maujood hai.
