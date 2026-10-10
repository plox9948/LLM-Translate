<#
.SYNOPSIS
    Патч версии ORT в нативной библиотеке sherpa-onnx (обход UnsatisfiedLinkError OrtGetApiBase).

.DESCRIPTION
    libsherpa-onnx-jni.so из AAR sherpa-onnx 1.13.8 собрана с ORT 1.28.2 и требует
    строго versioned-символ OrtGetApiBase@VERS_1.28.2 (секция .gnu.version_r).
    В APK попадает единая libonnxruntime.so от onnxruntime-android 1.30.0 с версией
    VERS_1.30.0 — линковка dlopen падает ("cannot locate symbol OrtGetApiBase").

    Скрипт правит в libsherpa-onnx-jni.so запись запрошенной версии в .gnu.version_r
    для файла libonnxruntime.so: имя version-node "VERS_1.28.2" -> "VERS_1.30.0"
    (строки одинаковой длины, пуказатели не сдвигаются) И поле vna_hash (SysV hash
    нового имени) — Android-линкер сопоставляет версии по хешу, поэтому одной замены
    строки недостаточно.

    Это безопасно: OrtGetApiBase ABI-стабилен, а GetApi() в рантайме 1.30 обратно
    совместим с C API, собранным против заголовков 1.28 (API-структура дополняется
    только хвостом).

    Результат пишется в app/src/main/jniLibs/<abi>/libsherpa-onnx-jni.so (jniLibs
    приложения имеют приоритет при слиянии, в паре с packaging.jniLibs.pickFirsts).

.NOTES
    Вход: распакованный AAR sherpa-onnx (jni/<abi>/libsherpa-onnx-jni.so).
    После обновления sherpa-onnx / onnxruntime-android перепустить скрипт с
    актуальными FROM/TO версиями.
#>
param(
    [Parameter(Mandatory = $true)][string]$AarExtractDir,
    [string]$Abis = "arm64-v8a,x86_64",
    [string]$OutDir = "$PSScriptRoot\..\app\src\main\jniLibs",
    [string]$FromVersion = "VERS_1.28.2",
    [string]$ToVersion = "VERS_1.30.0",
    [string]$NeededFile = "libonnxruntime.so"
)

# SysV Elf hash (совпадает с elf_hash() в Android linker.cpp / glibc).
function Get-ElfHash([byte[]]$bytes, [int]$start, [int]$len) {
    [uint32]$h = 0
    for ($i = 0; $i -lt $len; $i++) {
        $h = ($h -shl 4) + [uint32]$bytes[$start + $i]
        $g = $h -band 0xF0000000
        if ($g -ne 0) { $h = $h -bxor ($g -shr 24) }
        $h = $h -band (-bnot $g)
    }
    return $h
}

function Read-ZString([byte[]]$bytes, [int64]$off) {
    $sb = New-Object System.Text.StringBuilder
    while ($bytes[$off] -ne 0) { [void]$sb.Append([char]$bytes[$off]); $off++ }
    return $sb.ToString()
}

$enc = [System.Text.Encoding]::GetEncoding("ISO-8859-1")
$SHT_GNU_verneed = 0x6FFFFFFE

foreach ($abi in ($Abis -split ",")) {
    $src = Join-Path $AarExtractDir "jni\$abi\libsherpa-onnx-jni.so"
    if (-not (Test-Path -LiteralPath $src)) { throw "Не найдено: $src" }
    $bytes = [System.IO.File]::ReadAllBytes($src)

    if ($bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4C -or $bytes[3] -ne 0x46) {
        throw "Не ELF: $src"
    }
    if ($bytes[4] -ne 2) { throw "Ожидается ELF64 (класс 2): $src" }

    $eShoff = [int64][BitConverter]::ToUInt64($bytes, 0x28)
    $eShentsize = [int][BitConverter]::ToUInt16($bytes, 0x3A)
    $eShnum = [int][BitConverter]::ToUInt16($bytes, 0x3C)

    $verneedOff = [int64](-1); $strtabOff = [int64](-1)
    for ($i = 0; $i -lt $eShnum; $i++) {
        $sh = $eShoff + $i * $eShentsize
        $shType = [int][BitConverter]::ToUInt32($bytes, $sh + 4)
        if ($shType -eq $SHT_GNU_verneed) {
            $verneedOff = [int64][BitConverter]::ToUInt64($bytes, $sh + 0x18)
            $strtabSh = $eShoff + [int64][BitConverter]::ToUInt32($bytes, $sh + 0x28) * $eShentsize
            $strtabOff = [int64][BitConverter]::ToUInt64($bytes, $strtabSh + 0x18)
        }
    }
    if ($verneedOff -lt 0) { throw ".gnu.version_r не найдена в $src" }

    $patched = 0
    $off = $verneedOff
    while ($true) {
        $vnCnt = [int][BitConverter]::ToUInt16($bytes, $off + 2)
        $vnFile = Read-ZString $bytes ($strtabOff + [int64][BitConverter]::ToUInt32($bytes, $off + 4))
        $auxOff = $off + [int64][BitConverter]::ToUInt32($bytes, $off + 8)
        if ($vnFile -eq $NeededFile) {
            for ($j = 0; $j -lt $vnCnt; $j++) {
                $namePtr = $strtabOff + [int64][BitConverter]::ToUInt32($bytes, $auxOff + 8)
                $name = Read-ZString $bytes $namePtr
                if ($name -eq $FromVersion) {
                    $fromBytes = $enc.GetBytes($FromVersion)
                    $nameBytes = $enc.GetBytes($ToVersion)
                    if ($fromBytes.Length -ne $nameBytes.Length) {
                        throw "Строки разной длины — побайтовая замена невозможна"
                    }
                    [Array]::Copy($nameBytes, 0, $bytes, [int]$namePtr, $nameBytes.Length)
                    $newHash = Get-ElfHash $bytes ([int]$namePtr) $nameBytes.Length
                    [BitConverter]::GetBytes([uint32]$newHash).CopyTo($bytes, [int]$auxOff)
                    $patched++
                }
                $next = [int][BitConverter]::ToUInt32($bytes, $auxOff + 12)
                if ($next -eq 0) { break }
                $auxOff += $next
            }
        }
        $nextVn = [int][BitConverter]::ToUInt32($bytes, $off + 12)
        if ($nextVn -eq 0) { break }
        $off += $nextVn
    }
    if ($patched -eq 0) { throw "Запрошенной версии '$FromVersion' для $NeededFile нет в $src (библиотека уже патчена или сменилась версия)" }

    $outAbi = Join-Path $OutDir $abi
    New-Item -ItemType Directory -Force -Path $outAbi | Out-Null
    $out = Join-Path $outAbi "libsherpa-onnx-jni.so"
    [System.IO.File]::WriteAllBytes($out, $bytes)
    Write-Host "$abi : патчено записей: $patched ('$FromVersion' -> '$ToVersion', хеш обновлён) => $out"
}
