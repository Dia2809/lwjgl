#!/usr/bin/env bash
# Build script for the Slay the Spire texture compression mod.
# Produces:
#   libtexcompress.so        — native compressor
#   texcompress-agent.jar    — Java agent

set -e
cd "$(dirname "$0")"

# ---------- 1. Compile native library ----------
echo "==> Compiling libtexcompress.so ..."
gcc -O2 -shared -fPIC -std=c99 \
    -I"$JAVA_HOME/include" \
    -I"$JAVA_HOME/include/linux" \
    native/texture_compress.c \
    -o libtexcompress.so \
    -ldl
echo "    OK: libtexcompress.so"

# ---------- 2. Compile Java agent ----------
echo "==> Compiling Java sources ..."

# ASM is bundled with the LWJGL installation that ships with Slay the Spire.
# Point ASM_JAR at whichever asm jar ships in the game's lib folder.
# Common locations (adjust if needed):
ASM_JAR=""
for candidate in \
    ~/.steam/steam/steamapps/common/SlayTheSpire/lib/asm-all*.jar \
    ~/.steam/steam/steamapps/common/SlayTheSpire/lib/asm*.jar \
    /usr/share/java/asm.jar \
    /usr/share/java/asm-all.jar; do
    if [ -f "$candidate" ]; then
        ASM_JAR="$candidate"
        break
    fi
done

if [ -z "$ASM_JAR" ]; then
    echo "ERROR: ASM jar not found. Set ASM_JAR manually or install: apt install libasm-java"
    exit 1
fi
echo "    Using ASM: $ASM_JAR"

mkdir -p build/classes
javac -cp "$ASM_JAR" \
      -d build/classes \
      src/main/java/com/texcompress/NativeCompressor.java \
      src/main/java/com/texcompress/TextureCompressAgent.java

# ---------- 3. Package agent jar ----------
echo "==> Packaging texcompress-agent.jar ..."
cp -r src/main/java/META-INF build/classes/
jar cfm texcompress-agent.jar build/classes/META-INF/MANIFEST.MF \
    -C build/classes .

echo ""
echo "=== Build complete ==="
echo ""
echo "Files produced:"
echo "  $(pwd)/libtexcompress.so"
echo "  $(pwd)/texcompress-agent.jar"
echo ""
echo "=== How to use with Slay the Spire ==="
echo ""
echo "1. In Steam, right-click Slay the Spire → Properties → Launch Options:"
echo ""
echo '   For Linux:'
echo '   java -javaagent:/PATH/TO/texcompress-agent.jar=native=/PATH/TO/libtexcompress.so %command%'
echo ""
echo '   Or edit the steam_launch script and prepend those JVM flags.'
echo ""
echo "2. Launch the game. You should see in the log:"
echo "   [TexCompress] Native library loaded: ..."
echo "   [TexCompress] Using format 0x83f0 (RGB) / 0x83f3 (RGBA)"
echo "   [TexCompress] Agent installed — will compress textures on upload."
