# LLM Translate — User Guide

An offline translator for Android: Russian ⇄ English ⇄ Nepali. All translations
run right on your phone — you need the internet **only once**, to download the
models. After that the app works without a network connection anywhere.

Download the app (direct link to the latest release APK):
https://github.com/plox9948/LLM-Translate/releases/latest/download/LLM-Translator-release.apk

All releases: https://github.com/plox9948/LLM-Translate/releases

## Installation

1. Open the link above in your phone's browser and download the APK file of the
   latest release.
2. Open the downloaded file. If Android warns that the app comes from an
   "unknown source" — allow the installation (usually in the pop-up dialog, or
   in "Install unknown apps" settings for your browser).
3. Later updates install over the existing version: just download the new APK
   and install it — history, favorites and models are kept.

> ⚠ **If you are running an old version of the app (before v0.5.7)**, the new
> one will not install over it — the release signing key changed. Do this
> instead:
> 1. Open the "Models" tab → "Backup" and save the archive (models and settings).
> 2. Uninstall the old version of the app.
> 3. Install the new APK and restore the archive: "Models" → "Restore".
>
> History and favorites are not included in the archive — your old history will
> be lost when switching this way. All later updates install over the top
> without uninstalling.

## First launch

On the first launch you will see the "What are we translating?" sheet — pick the
direction you need: "Russian ⇄ English", "English ⇄ Nepali" or "Russian ⇄
Nepali" (or "Skip").

Right below the input fields, cards for the models missing in the chosen
direction appear ("Translation models required"), each with its file size and a
"Download" button. Tap "Download" — the download starts with a progress
indicator. Until the models are downloaded, translation is not possible. The
same reminder has a separate "Speech recognition" section listing the missing
speech recognition model for the source language — you can download it right
there and start talking into the microphone straight away.

## Models (the "Models" tab)

Model cards are grouped into sections: "Translation (LLM)", "Speech recognition
(STT)", "Speech synthesis (TTS)". The internet is needed only for the
"Download" button; translation and recognition themselves work completely
offline.

- "Download" — fetch a model from the catalog; while downloading, a "Cancel"
  button is available. After canceling, you can press "Download" again right
  away — the download restarts, the card never gets stuck in a downloading
  state. Double-tapping "Download"/"Retry" won't start the download twice —
  the button is disabled while the download is being submitted.
- If a download fails, the card gets a "Download error" label and the
  "Download" button turns into "Retry": it re-downloads the model without
  deleting it first; while the model is in error it cannot translate.
- If the app crashed or was killed mid-download and a card stays "downloading"
  for a long time, the next app launch repairs that state: the card no longer
  sticks, and the model can be fetched again with "Retry". Legit downloads
  that are still resuming are not affected.
- "Export" / "Delete" — on the card of an installed model. Before deleting, the
  app asks for confirmation in a dialog ("Delete model?" with "Delete" and
  "Cancel" buttons): one accidental tap no longer loses the model.
- "Import" — on the card of a not-yet-downloaded model: unpack a model from a
  ZIP file received from another phone, without downloading. A ZIP export made
  by this same app keeps the model whole: its type and languages are restored
  automatically on import. A broken or tampered archive is rejected on import
  (its contents are checked against the model's checksums) — and an already
  installed model is left untouched. Manually imported Marian ONNX models must
  ship a valid generation_config.json: if it is missing or damaged, the app
  reports an explicit model-load error instead of producing an empty translation.
- "Select" — only on TTS engine cards; the translation model is picked
  automatically by direction, and a recognition model is assigned with the
  "Speech recognition language:" chips on the STT model cards.
- "Speech synthesis language:" — the language chips (RU/EN/NE) on cards in the
  "Speech synthesis (TTS)" section (details in the "Speech input and read
  aloud" section).

How much phone memory you need:

| What you plan to use | Phone memory |
|----------------------|--------------|
| Russian ⇄ English translation | 2 GB or more |
| Translation to/from Nepali (via English) | 3–4 GB |
| Speech recognition (GigaAM, Parakeet, IndicConformer) | 2 GB or more |
| Read aloud with local Piper voices | 2 GB or more |

If memory or free storage is not enough, the app shows a warning on the
"Models" screen — the space estimate also accounts for unpacking (installing a
model needs room for both the downloaded file and its unpacked contents). If
the storage runs out during the download or unpacking itself, the download
stops right away with a clear message instead of re-downloading the model over
and over; what has already been downloaded is kept — once you free up space,
"Retry" resumes from where it stopped.
Deleting a model ("Delete") frees space — the model can be
downloaded again or imported. The "Loaded into memory" card on the "Models"
screen lists the models currently held in RAM (including loaded Piper voice
models) together with an estimated memory consumption.

> ℹ Temporarily hidden from the "Models" screen: Whisper (recognition),
> RHVoice and eSpeak NG (synthesis engines). Already-installed models of
> these engines stay visible on the screen and remain manageable ("Delete",
> "Export") — the same goes for models imported outside the catalog. If
> RHVoice or eSpeak NG was picked in the settings earlier, that choice is
> kept.

## The "Translate" screen

- At the top — two language chips: source and target ("Авто (Auto)",
  "Русский (Russian)", "English (English)", "नेपाली (Nepali)" — the chip labels
  look the same in every interface language). "Авто" detects the input language
  automatically; in this mode the "Swap languages" arrow and the favorites star
  are unavailable — pick a specific language when you know the direction.
- The arrow between the chips swaps the translation direction; the shown
  translation is automatically placed into the input field, so you can check
  the translation both ways without copying it manually.
- Type text into the field ("Enter text to translate…") and press "Translate".
  While a translation is running the input field is locked (so the text cannot
  drift away from its result); "Cancel" or a finished translation unlocks it.
  "Clear" empties both fields.
- The typed text and its translation survive the app being closed or the
  system killing its process: reopening "Translate" restores both fields
  (up to 10,000 characters each, longer texts are trimmed). The result comes
  back bound to the direction it was translated in: its card title and
  labels match that snapshot. If you translated with "Авто", the actually
  detected source language is stored in the snapshot — such a result is
  shown again after reopening as well. If the restored direction differs
  from the currently selected one, the result is hidden by the same direction-binding
  rule — no mismatched pair appears under the new labels (your input is
  kept; you can bring the needed translation back from history or translate
  again).
- The screen scrolls: long input and long translations are fully visible, the
  input field grows with the text instead of cutting it off.
- In the input field header, next to the microphone, there is a "Copy source
  text" button — it puts the typed text into the clipboard (available while the
  field is not empty).
- If your input does not end with punctuation, a period is added automatically
  before translating: without it the models translate short phrases worse and
  distort them when translating back (for example, in the
  Russian → Nepali → Russian chain). The fields and the history still keep your
  original text without the added period.
- The result keeps the line breaks of the source text: paragraphs and
  multi-line formatting stay exactly as in the original.
- The result is bound to the translation direction: after changing direction
  or returning to the tab, a non-matching result is hidden (your input text is
  kept) and "Read aloud" never reads text in the wrong language.
- Above the result: "Copy translation" (to the clipboard), "Read aloud"
  (loudspeaker button) and the "Add to favorites" star. The star is bound to
  the translation together with the input text as it was at translation time:
  editing the input field afterwards does not change the star state and does
  not break the pair — a saved favorite keeps its original text and
  translation.
- The microphone in the input field ("Record audio"): one tap starts recording
  ("● Recording… Tap the microphone to stop" — an elapsed-time counter ticks
  next to it), the second tap stops it and recognizes the speech into text.
  Recording stops automatically after 3 minutes — even if you have already
  left the "Translate" tab: the recording is closed properly, and everything
  captured before that is kept and recognized as usual. Allow microphone
  access when asked the first time.

## "History" and "Favorites"

- "History" — your latest translations. Tapping a card loads its text and
  direction back onto the "Translate" screen without re-downloading models;
  the applied direction is never rolled back to the previously saved one.
  The star on a card adds the translation to favorites, the cross removes the
  entry from history, and "Clear" erases the whole history — after asking for
  confirmation ("Clear history?" with "Clear" and "Cancel" buttons), so one
  accidental tap cannot lose it.
- "Favorites" — saved translations. The "Search favorites" field searches the
  source text and the translation (case-insensitive); the search query is kept
  when you leave the tab and come back. Cards have "Read source
  aloud" and "Read translation aloud" buttons; the star removes the entry from
  favorites; tapping a card goes back to "Translate".
- If an operation on entries fails (saving, deleting, clearing, adding to
  favorites), the app says so in a clear message in the interface language. When
  the database misbehaves the app does not close: the History and Favorites lists
  reload automatically, and the cause is shown as a message like "Failed to load
  history".

## The "Settings" screen

- "Interface language": "System default", "Русский", "English", "नेपाली".
- "Speech synthesis engine": only "Android system TTS" is offered now (RHVoice
  and eSpeak NG are temporarily hidden; an earlier choice of them is kept).
  Per-language read aloud is configured with the "Speech synthesis language:"
  chips on the "Models" screen.
- "Theme": "System default", "Light", "Dark".
- "Model unloading" — how many minutes after the app is minimized the models
  are released from RAM: "Immediately", "After 5 minutes (default)",
  "After 15 minutes", "After 30 minutes", "Never unload".
- "Behavior on translation direction change":
  - "Do not unload" (default) — models of directions you have already used are
    kept in memory: switching directions becomes faster, but it uses more
    memory;
  - "Unload" — only the current model stays in memory, saving memory.

## Speech input and read aloud

- Speech recognition: GigaAM (Russian), Parakeet (English), IndicConformer
  (Nepali). Models are downloaded in the "Speech recognition (STT)" section,
  and the "Speech recognition language:" chips on a card set which model
  handles which language. If the recognition model for the source language is
  missing, the "Translate" screen shows it in the "Translation models
  required" reminder under the "Speech recognition" section with a "Download"
  button.
  The app strictly checks the contents of a recognition model's folder
  (its files and names) when loading it into memory: if a model was imported
  by hand and its files have non-standard names, recognition will not guess
  the configuration — it reports a clear error instead. Catalog models always
  fit; this never affects them.
- Read aloud. By default every language is read by the phone's voice engine
  ("Android system TTS"). Per language you can switch it to a local Piper TTS
  model (Russian, English, Nepali) with the "Speech synthesis language:" chips
  (RU / EN / NE) on the cards in the "Speech synthesis (TTS)" section:
  - tapping a language chip on a Piper model card (the model must be
    downloaded) assigns that language to the model, and the same chip on the
    "Android system TTS" card goes off;
  - tapping a language chip on the "Android system TTS" card returns that
    language to the system engine.
  The "Selected" highlight on TTS cards works per language: a card is
  highlighted if it is assigned at least one language. Tap the language icon
  on a card to make it active for that language (the hint at the top of the
  TTS section reminds about this).
- If the phone's system TTS does not support a language (for example, the
  device has no Nepali voice), the app offers and starts downloading the voice
  model for that language on its own, and read aloud continues with the local
  model. Local Piper voices play a phrase to the very end, without cutting
  the tail off.
- Deleting or re-downloading a Piper model unloads its voice from memory
  immediately: no "ghost" of the old voice stays in the "Loaded into memory"
  list, and after a re-download the voice reads from the new files. A
  cancelled read-aloud no longer comes back as a late repeat of a stale
  phrase. A quick "stop → Read aloud" no longer loses a phrase: a just-stopped
  read-aloud cannot mute the next one — the new phrase plays to the end.
  Engines never overlap: a new phrase replaces the previous one, and
  when the app automatically switches to a Piper voice the system read-aloud
  is stopped — two voices never speak at the same time.

## Moving to another phone

1. On the old phone: "Models" → "Backup", save the ZIP archive.
2. Copy the archive to the new phone (USB, cloud, Bluetooth).
3. On the new phone: "Models" → "Restore", pick the archive — models and
   settings are restored without downloading.

While restoring, model files are checked against the checksums stored in the
archive: damaged files are skipped (such a model can be re-downloaded or
imported separately), everything else is restored. File replacement is safe: a
new file overwrites the old model file only after it has been copied
successfully — if the copy is interrupted, the previously installed model stays
intact. A restore never conflicts with work on the same phone: replacing files
from the archive waits its turn behind an in-progress download or import of the
same model, and temporary files of an unfinished import are removed
automatically. If a restore or import replaces an already installed model, it will not
keep translating the old way: the app reloads it from the new files right away,
without a restart, and the files of the previous version are no longer left
behind on the phone as junk.

The archive itself is created safely too: it is first built completely in a
temporary file and only then saved to the location you picked; if there is not
enough free space, the app refuses before writing starts, and an interrupted
partial file is deleted — a broken archive never gets saved. When installing
from the archive, the app also checks free space up front (unpacking included)
and verifies model bundles file by file against their checksums.

Canceling backup operations never damages your existing models: a canceled
restore leaves no half-replaced bundles — models stay exactly as they were.
A damaged or tampered archive — with duplicate models or abnormally large
entries — is rejected with a clear "the archive is damaged" error before any
changes begin. If a restore interrupted a model download that was already
running, the app tells you so ("Active downloads were interrupted by the
import and not replaced — retry downloading later") and you can simply retry
the download. While a restore or archive import is in progress, the "Backup"
button will temporarily refuse — retry the export later.

The settings backup includes: interface language and theme, per-language
speech recognition and read-aloud assignments, the model unloading timeout,
the policy on translation direction change, and the last translation
direction. Only the languages actually present in the archive get their
assignments restored: a partial backup leaves the assignments of the other
languages untouched.

> 💡 It is easier to download the models once on one device and move them via
> the archive than to download everything again.

Some speech recognition models are distributed under non-commercial licenses —
before downloading them the app shows the "Model license" dialog, where you
confirm your agreement with the "Download" button.

## Frequently asked questions

**Why is the first action after minimizing the app slower?**
After the app is minimized, the models are unloaded from phone memory after a
while. On the next "Translate" press a model is loaded once again — this is
normal. If you do not want to wait, choose "Settings" → "Model unloading" →
"Never unload" (but the app will then use more memory).

**Translation to/from Nepali is noticeably slower than Russian ⇄ English.**
This is expected: the Nepali model weighs about 320 MB and runs on the phone's
CPU; for the Russian ⇄ Nepali direction two translation models work at the
same time (via English).

**Nepali translation sometimes "thinks" longer than usual.**
If the model is unsure about a short text or falls into a repetition, the app
suppresses the repetition at generation level and, when needed, runs an
alternative translation pass; very long texts are translated in parts — each
sentence as its own chunk, paragraphs preserved. Deliberate repeats in your
own text are kept in the translation. This is normal and needs no action from
you.

**Why did "3 hours later" come back as "after 3 hours"?**
In Nepali one word covers both "later" and "after", so the model picks the
neutral reading. This is a quality limit of the 320 MB offline model, while
the meaning of the sentence is preserved. To check the nuance, translate the
phrase once more into the original direction — the swap arrow changes the
direction and puts the translation into the input field.

**The microphone does not recognize speech.**
The message on the screen tells you the cause: "Microphone recording
permission is not granted…" — allow microphone access in the app settings;
"To recognize speech, download a speech recognition model…" — download the
STT model for the language (right from the "Speech recognition" section of the
reminder on the "Translate" screen) and assign the language with the "Speech
recognition language:" chips on the "Models" screen.

**Instead of a Russian ⇄ English translation, a model loading error appeared.**
If the model file (language pack) is damaged or does not fit the app, the app now
reports a loading error instead of producing nonsense text. Delete such a model
on the "Models" screen and download or import it again (for example, from a
backup made on another device).

**I deleted model files by hand (or a file manager cleaned them up).**
On launch the app checks installed models against the files on the phone, and a
model whose files are gone shows a "Download error" label on its "Models" card.
Tap "Retry" to re-download it; alternatively import it or restore it from a
backup.

**The app said there was not enough free space.** The download stops with that
message right away instead of re-downloading the model over and over. Remember
that a model needs room for unpacking as well (see the table above). Free up
space and tap "Retry" — the download resumes from where it stopped.

**The app showed a "Failed to…" message — what should I do?** Error messages are
uniform and appear in the interface language (Russian, English, Nepali) and say
exactly what failed: translation, recognition, microphone recording, a
history/favorites operation, backup, or loading settings on a screen. Usually
repeating the action is enough: if the failure concerned the History/Favorites
lists or the settings, they reload and resubscribe automatically, and the app
keeps working — a temporary database or settings-storage failure never closes
it. A failure while saving a setting or a speech recognition / read-aloud
language assignment (the chips on the "Models" screen) likewise does not close
the app: the reason stays on screen as a warning banner, and everything else
keeps working.

**Is there a limit on microphone recording length?** Yes: recording stops
automatically after 3 minutes so that you cannot forget to turn it off — even
if you have already left the "Translate" tab. The recording indicator shows
how long it has been running; everything captured before the stop is kept and
recognized as usual.

**Why is there an "Import" button on a model card?** It lets you bring a model
over from another phone (a ZIP export made by this same app) or from a
computer, without downloading it again. When the imported version replaces an
already installed model, the model is replaced whole and reloaded from the new
files — the previous version stops being used at once, its files are deleted
and never mix with the files of the new version.

**Can I delete and import a model several times in a row, quickly?** Yes.
Operations on one model — import, deletion, download canceling, and backup
restore — always run strictly one after another, so quick actions (deleting
right after or right before an import, two imports of the same ZIP, restoring
an archive while the same model is downloading) cannot damage it: the result
is always consistent — the model is either fully
installed or fully deleted. A failed import (for example, "Model import failed"
when there is not enough free space) does not destroy an already installed
model either: it keeps working, and you only need to retry the import after
freeing up space.

Technical details, the model catalog and building from source are in README.md.
This guide is also available in Russian: [USER_GUIDE.md](USER_GUIDE.md).
