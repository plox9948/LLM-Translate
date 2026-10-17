# LLM Translate — User Guide

An offline translator for Android: Russian ⇄ English ⇄ Nepali. All translations
run right on your phone — you need the internet **only once**, to download the
models. After that the app works without a network connection anywhere.

Download the app: http://31.44.89.204:3000/plox/LLM-Translate/releases

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
indicator. Until the models are downloaded, translation is not possible.

## Models (the "Models" tab)

Model cards are grouped into sections: "Translation (LLM)", "Speech recognition
(STT)", "Speech synthesis (TTS)". The internet is needed only for the
"Download" button; translation and recognition themselves work completely
offline.

- "Download" — fetch a model from the catalog; while downloading, a "Cancel"
  button is available.
- "Export" / "Delete" — on the card of an installed model.
- "Import" — on the card of a not-yet-downloaded model: unpack a model from a
  ZIP file received from another phone, without downloading.
- "Select" — only for TTS engines; the translation model is picked
  automatically by direction, and a recognition model is assigned with the
  "Speech recognition language:" chips on the STT model cards.

How much phone memory you need:

| What you plan to use | Phone memory |
|----------------------|--------------|
| Russian ⇄ English translation | 2 GB or more |
| Translation to/from Nepali (via English) | 3–4 GB |
| Whisper speech recognition (multilingual) | 6 GB |
| Other speech recognition models | 2 GB or more |

If memory or free storage is not enough, the app shows a warning on the
"Models" screen. Deleting a model ("Delete") frees space — the model can be
downloaded again or imported.

## The "Translate" screen

- At the top — two language chips: source and target ("Авто (Auto)",
  "Русский (Russian)", "English (English)", "नेपाली (Nepali)" — the chip labels
  look the same in every interface language). "Авто" detects the input language
  automatically; in this mode the "Swap languages" arrow and the favorites star
  are unavailable — pick a specific language when you know the direction.
- The arrow between the chips swaps the translation direction.
- Type text into the field ("Enter text to translate…") and press "Translate".
  During a long translation a "Cancel" button appears. "Clear" empties both
  fields.
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
  (Nepali), Whisper (all three languages). Models are downloaded in the "Speech
  recognition (STT)" section, and the "Speech recognition language:" chips on a
  card set which model handles which language.
- Read aloud uses the phone's voice engines. Pick an engine in
  "Settings" → "Speech synthesis engine": "Android system TTS",
  "RHVoice (ru, ne)" or "eSpeak NG (100+ languages)". The engine itself (for
  example Google TTS or RHVoice) and a voice for the needed language must be
  installed on the phone — otherwise read aloud will not work.

## Moving to another phone

1. On the old phone: "Models" → "Backup", save the ZIP archive.
2. Copy the archive to the new phone (USB, cloud, Bluetooth).
3. On the new phone: "Models" → "Restore", pick the archive — models and
   settings are restored without downloading.

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
Check that an STT model for that language has been downloaded and assigned the
language with the "Speech recognition language:" chips on the "Models" screen.

**Why is there an "Import" button on a model card?** It lets you bring a model
over from another phone (a ZIP export made by this same app) or from a
computer, without downloading it again.

Technical details, the model catalog and building from source are in README.md.
This guide is also available in Russian: [USER_GUIDE.md](USER_GUIDE.md).
