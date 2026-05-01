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

# ---------- 3. Package fat agent jar (bundle ASM so it is self-contained) ----------
echo "==> Packaging texcompress-agent.jar (fat jar, ASM bundled) ..."
cp -r src/main/java/META-INF build/classes/

# Extract ASM classes into the build directory so they get bundled
mkdir -p build/asm-extract
(cd build/asm-extract && jar xf "$ASM_JAR")
# Copy only the org/objectweb/asm hierarchy — skip META-INF to avoid conflicts
cp -rn build/asm-extract/org build/classes/ 2>/dev/null || true

jar cfm texcompress-agent.jar build/classes/META-INF/MANIFEST.MF \
    -C build/classes .

echo ""
echo "=== Build complete ==="
echo ""
echo "Files produced:"
echo "  $(pwd)/libtexcompress.so"
echo "  $(pwd)/texcompress-agent.jar"
echo ""
STS_DIR="$HOME/snap/steam/common/.local/share/Steam/steamapps/common/SlayTheSpire"
if [ ! -d "$STS_DIR" ]; then
    STS_DIR="$HOME/.steam/steam/steamapps/common/SlayTheSpire"
fi

echo "=== How to use with Slay the Spire ==="
echo ""
echo "Step 1 — Copy files into the game directory (required for snap Steam):"
echo "  cp $(pwd)/libtexcompress.so   \"$STS_DIR/\""
echo "  cp $(pwd)/texcompress-agent.jar \"$STS_DIR/\""
echo ""
echo "Step 2 — Launch with:"
echo "  cd \"$STS_DIR\""
echo "  LD_LIBRARY_PATH=/usr/lib/jvm/java-21-openjdk-amd64/lib:\$LD_LIBRARY_PATH \\"
echo '  java \'
echo '    -javaagent:$(pwd)/texcompress-agent.jar=native=$(pwd)/libtexcompress.so \'
echo '    -jar ./desktop-1.0.jar'
echo ""
echo "  OR add to Steam launch options (right-click → Properties):"
echo "  LD_LIBRARY_PATH=/usr/lib/jvm/java-21-openjdk-amd64/lib:\$LD_LIBRARY_PATH"
echo '  JAVA_TOOL_OPTIONS="-javaagent:%command%/texcompress-agent.jar=native=%command%/libtexcompress.so"'
echo '  %command%'
echo ""
echo "Step 3 — You should see in the log:"
echo "  [TexCompress] Native library loaded: ..."
echo "  [TexCompress] Patching glTexImage2D in com/badlogic/gdx/backends/lwjgl/LwjglGL20"
echo "  [TexCompress] Using format 0x9274 (RGB) / 0x9278 (RGBA)"
