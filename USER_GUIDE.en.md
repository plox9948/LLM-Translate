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
  button is available.
- "Export" / "Delete" — on the card of an installed model.
- "Import" — on the card of a not-yet-downloaded model: unpack a model from a
  ZIP file received from another phone, without downloading. A ZIP export made
  by this same app keeps the model whole: its type and languages are restored
  automatically on import.
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
model needs room for both the downloaded file and its unpacked contents).
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
  During a long translation a "Cancel" button appears. "Clear" empties both
  fields.
- The result keeps the line breaks of the source text: paragraphs and
  multi-line formatting stay exactly as in the original.
- The result is bound to the translation direction: after changing direction
  or returning to the tab, a non-matching result is hidden (your input text is
  kept) and "Read aloud" never reads text in the wrong language.
- Above the result: "Copy translation" (to the clipboard), "Read aloud"
  (loudspeaker button) and the "Add to favorites" star.
- The microphone in the input field ("Record audio"): one tap starts recording
  ("● Recording… Tap the microphone to stop"), the second tap stops it and
  recognizes the speech into text. Allow microphone access when asked the
  first time.

## "History" and "Favorites"

- "History" — your latest translations. Tapping a card loads its text and
  direction back onto the "Translate" screen without re-downloading models.
  The star on a card adds the translation to favorites, the cross removes the
  entry from history, and "Clear" erases the whole history.
- "Favorites" — saved translations. The "Search favorites" field searches the
  source text and the translation (case-insensitive). Cards have "Read source
  aloud" and "Read translation aloud" buttons; the star removes the entry from
  favorites; tapping a card goes back to "Translate".

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
  phrase. Engines never overlap: a new phrase replaces the previous one, and
  when the app automatically switches to a Piper voice the system read-aloud
  is stopped — two voices never speak at the same time.

## Moving to another phone

1. On the old phone: "Models" → "Backup", save the ZIP archive.
2. Copy the archive to the new phone (USB, cloud, Bluetooth).
3. On the new phone: "Models" → "Restore", pick the archive — models and
   settings are restored without downloading.

While restoring, model files are checked against the checksums stored in the
archive: damaged files are skipped (such a model can be re-downloaded or
imported separately), everything else is restored.

The settings backup includes: interface language and theme, per-language
speech recognition and read-aloud assignments, the model unloading timeout,
the policy on translation direction change, and the last translation
direction.

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

**The microphone does not recognize speech.**
The message on the screen tells you the cause: "Microphone recording
permission is not granted…" — allow microphone access in the app settings;
"To recognize speech, download a speech recognition model…" — download the
STT model for the language (right from the "Speech recognition" section of the
reminder on the "Translate" screen) and assign the language with the "Speech
recognition language:" chips on the "Models" screen.

**Why is there an "Import" button on a model card?** It lets you bring a model
over from another phone (a ZIP export made by this same app) or from a
computer, without downloading it again.

Technical details, the model catalog and building from source are in README.md.
This guide is also available in Russian: [USER_GUIDE.md](USER_GUIDE.md).
