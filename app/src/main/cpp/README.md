# Нативные зависимости

## llama.cpp (обязательно с веткой PR #22836)

Каталог включает модель Hy-MT1.5 1.25-bit в формате STQ1_0, для которой нужен
ядро STQ из [llama.cpp PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836).
Поэтому submodule фиксируется на коммите этого PR, а не на main:

```bash
cd app/src/main/cpp/external

# 1. Добавить submodule
git submodule add https://github.com/ggml-org/llama.cpp llama.cpp

# 2. Забрать ветку PR и зафиксировать её
cd llama.cpp
git fetch origin pull/22836/head:pr-22836-stq_0
git checkout pr-22836-stq_0
cd ../..

# 3. Зафиксировать коммит submodule в основном репозитории
git add app/src/main/cpp/external/llama.cpp
```

## whisper.cpp (обычный main)

```bash
cd app/src/main/cpp/external
git submodule add https://github.com/ggml-org/whisper.cpp whisper.cpp
cd ../..
git add app/src/main/cpp/external/whisper.cpp
```

## Обновление зависимостей

```bash
git submodule update --remote --merge
```

Для llama.cpp после обновления проверять, что ветка STQ1_0 всё ещё на месте
(`git -C app/src/main/cpp/external/llama.cpp branch --show-current`).
