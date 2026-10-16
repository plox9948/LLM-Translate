#!/usr/bin/env bash
# Загрузка моделей LLM-Translator из models/manifest.json в models/<id>/<file>.
# - Скачивание с докачкой (curl -C -) — как BaseDownloader в приложении.
# - Проверка SHA-256 (если задан в манифесте) через sha256sum.
# - Чтение manifest.json через python3.
# - Печатает SHA-256 всех скачанных файлов (для фиксации в манифесте/каталоге).
# - Поле version манифеста синхронно с version в assets/models_catalog.json —
#   правки моделей поднимают версию в обоих файлах одинаково.
#
# Использование:
#   ./scripts/download_models.sh                          # скачать всё
#   ./scripts/download_models.sh firefox-bergamot-ru-en       # скачать конкретную модель
#   ./scripts/download_models.sh --print-sha-only         # только посчитать SHA уже скачанных

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
MANIFEST_PATH="$ROOT/models/manifest.json"
MODELS_ROOT="$ROOT/models"

# Разбор аргументов: --print-sha-only и список id моделей.
PRINT_SHA_ONLY=0
IDS=()
for arg in "$@"; do
  if [ "$arg" = "--print-sha-only" ]; then
    PRINT_SHA_ONLY=1
  else
    IDS+=("$arg")
  fi
done

if [ ! -f "$MANIFEST_PATH" ]; then
  echo "Ошибка: манифест не найден: $MANIFEST_PATH" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Ошибка: требуется python3 для чтения manifest.json" >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "Ошибка: требуется curl для скачивания" >&2
  exit 1
fi

# Вывести записи модели: id<TAB>name<TAB>url<TAB>sha256 (по одной на строку).
# Если переданы id — фильтруем по ним.
list_entries() {
  python3 - "$MANIFEST_PATH" ${IDS[@]+"${IDS[@]}"} <<'PY'
import json, sys

manifest_path = sys.argv[1]
ids = [a for a in sys.argv[2:] if a]

with open(manifest_path, "r", encoding="utf-8") as f:
    manifest = json.load(f)

for model in manifest.get("models", []):
    mid = model.get("id", "")
    if ids and mid not in ids:
        continue
    for f in model.get("files", []):
        name = f.get("name", "")
        url = f.get("url", "")
        sha = f.get("sha256") or ""
        # Поля не содержат табуляции — безопасный разделитель.
        print(f"{mid}\t{name}\t{url}\t{sha}")
PY
}

# SHA-256 файла в нижнем регистре.
sha256_of() {
  sha256sum "$1" | awk '{print $1}' | tr 'A-F' 'a-f'
}

# Скачать файл с докачкой (.part + curl -C -), затем переименовать в dest.
download_file() {
  local url="$1"
  local dest="$2"
  local part="$dest.part"
  local max_attempts=5
  local attempt=1

  mkdir -p "$(dirname "$dest")"

  while [ "$attempt" -le "$max_attempts" ]; do
    # curl: -C - докачка, -L редиректы, -f ошибка на HTTP >= 400, -s/-S тихий режим с логом.
    if curl -fsSL -C - -o "$part" "$url"; then
      if [ -f "$dest" ]; then
        rm -f "$dest"
      fi
      mv "$part" "$dest"
      return 0
    else
      echo "  Попытка $attempt/$max_attempts не удалась: $url" >&2
      sleep 2
      attempt=$((attempt + 1))
    fi
  done
  return 1
}

# Основной цикл по файлам моделей.
while IFS=$'\t' read -r mid fname url sha; do
  [ -z "$mid" ] && continue
  model_dir="$MODELS_ROOT/$mid"
  dest="$model_dir/$fname"

  echo ""
  echo "=== $mid ==="

  if [ "$PRINT_SHA_ONLY" -eq 0 ]; then
    mkdir -p "$model_dir"

    # Пропускаем, если файл уже есть и SHA совпадает.
    if [ -f "$dest" ] && [ -n "$sha" ]; then
      existing="$(sha256_of "$dest")"
      if [ "$existing" = "$sha" ]; then
        echo "  [пропущено, SHA совпадает] $fname"
        continue
      fi
      # Файл есть, но SHA не совпадает — перескачиваем с нуля.
      rm -f "$dest" "$dest.part"
    fi

    echo "  [скачиваю] $fname"
    if ! download_file "$url" "$dest"; then
      echo "Ошибка: не удалось скачать $url" >&2
      exit 1
    fi
  fi

  # Проверка / печать SHA-256.
  if [ -f "$dest" ]; then
    actual="$(sha256_of "$dest")"
    if [ -n "$sha" ]; then
      if [ "$actual" != "$sha" ]; then
        echo "Ошибка: SHA-256 НЕ СОВПАДАЕТ для $fname: expected=$sha actual=$actual" >&2
        exit 1
      else
        echo "  [OK] $fname: $actual"
      fi
    else
      echo "  [SHA] $fname: $actual"
    fi
  fi
done < <(list_entries)

echo ""
echo "Готово. SHA-256 выше можно зафиксировать в models/manifest.json."
