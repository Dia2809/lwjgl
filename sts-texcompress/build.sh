#!/usr/bin/env bash
# Build script for the Slay the Spire texture compression mod.
# Produces:
#   libtexcompress.so        — native compressor
#   texcompress-agent.jar    — Java agent

set -e
cd "$(dirname "$0")"

# ---------- 1. Compile native library ----------
echo "==> Compiling libtexcompress.so ..."
JAVA_HOME_NATIVE=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
gcc -O2 -shared -fPIC -std=c99 \
    -I"$JAVA_HOME_NATIVE/include" \
    -I"$JAVA_HOME_NATIVE/include/linux" \
    native/texture_compress.c \
    -o libtexcompress.so \
    -ldl
echo "    OK: libtexcompress.so"

# ---------- 2. Compile Java agent ----------
echo "==> Compiling Java sources ..."

# Use Java 17 — must match the JVM that runs Slay the Spire.
# Override by setting JAVA_HOME before calling this script.
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi
JAVAC="$JAVA_HOME/bin/javac"
JAR_CMD="$JAVA_HOME/bin/jar"
if [ ! -x "$JAVAC" ]; then
    echo "ERROR: javac not found at $JAVAC"
    echo "       Install with: sudo apt install openjdk-17-jdk"
    exit 1
fi
echo "    Using JDK: $JAVA_HOME"

# ASM jar — honour explicit env var, then search common locations
if [ -n "$ASM_JAR" ] && [ ! -f "$ASM_JAR" ]; then
    echo "WARNING: ASM_JAR env var set but file not found: $ASM_JAR"
    ASM_JAR=""
fi
if [ -z "$ASM_JAR" ]; then
for candidate in \
    /opt/gradle-*/lib/asm-[0-9]*.jar \
    /opt/apache-maven-*/lib/asm-[0-9]*.jar \
    ~/snap/steam/common/.local/share/Steam/steamapps/common/SlayTheSpire/lib/asm-all*.jar \
    ~/snap/steam/common/.local/share/Steam/steamapps/common/SlayTheSpire/lib/asm*.jar \
    ~/.steam/steam/steamapps/common/SlayTheSpire/lib/asm-all*.jar \
    ~/.steam/steam/steamapps/common/SlayTheSpire/lib/asm*.jar \
    /usr/share/java/asm.jar \
    /usr/share/java/asm-all.jar; do
    if [ -f "$candidate" ]; then
        ASM_JAR="$candidate"
        break
    fi
done
fi

# Verify the found ASM jar is new enough (needs ASM 7+ for Opcodes.ASM9).
# Old system asm.jar from libasm-java on Ubuntu <22.04 is ASM 5.x and won't compile.
# Detection: extract Opcodes.class to /tmp and check for the literal "ASM9" in its bytes.
if [ -n "$ASM_JAR" ]; then
    if ! (cd /tmp && "$JAVA_HOME/bin/jar" xf "$ASM_JAR" org/objectweb/asm/Opcodes.class 2>/dev/null \
          && strings org/objectweb/asm/Opcodes.class 2>/dev/null | grep -q "ASM9"; \
          rc=$?; rm -f org/objectweb/asm/Opcodes.class; exit $rc) 2>/dev/null; then
        echo "WARNING: ASM jar too old (needs ASM 7+): $ASM_JAR — will download ASM 9.7"
        ASM_JAR=""
    fi
fi

if [ -z "$ASM_JAR" ]; then
    ASM_CACHE="$HOME/.cache/asm-9.7.jar"
    if [ ! -f "$ASM_CACHE" ]; then
        mkdir -p "$(dirname "$ASM_CACHE")"
        echo "    Downloading ASM 9.7 from Maven Central ..."
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar" \
                 -o "$ASM_CACHE"
        elif command -v wget >/dev/null 2>&1; then
            wget -q "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar" \
                 -O "$ASM_CACHE"
        else
            echo "ERROR: ASM 9.7+ jar not found and no curl/wget to download it."
            echo "       Install with: sudo apt install libasm-java  (Ubuntu 22.04+)"
            echo "       Or manually place ASM 9.x jar at: $ASM_CACHE"
            exit 1
        fi
        echo "    Downloaded: $ASM_CACHE"
    fi
    ASM_JAR="$ASM_CACHE"
fi
echo "    Using ASM: $ASM_JAR"

mkdir -p build/classes
"$JAVAC" --release 8 -cp "$ASM_JAR" \
         -d build/classes \
         src/main/java/com/texcompress/NativeCompressor.java \
         src/main/java/com/texcompress/TextureCompressAgent.java \
         src/main/java/com/texcompress/Patcher.java

# ---------- 3. Package fat agent jar (bundle ASM so it is self-contained) ----------
echo "==> Packaging texcompress-agent.jar (fat jar, ASM bundled) ..."
cp -r src/main/java/META-INF build/classes/

# Extract ASM classes into the build directory so they get bundled
mkdir -p build/asm-extract
(cd build/asm-extract && jar xf "$ASM_JAR")
# Copy only the org/objectweb/asm hierarchy — skip META-INF to avoid conflicts
cp -rn build/asm-extract/org build/classes/ 2>/dev/null || true

"$JAR_CMD" cfm texcompress-agent.jar build/classes/META-INF/MANIFEST.MF \
    -C build/classes .

# ---------- 4. Package fat patcher jar (Main-Class: com.texcompress.Patcher) ----------
echo "==> Packaging patcher.jar (fat jar, ASM bundled) ..."
mkdir -p build/patcher-stage
# Copy all compiled classes (including ASM) into patcher staging area
cp -r build/classes/. build/patcher-stage/
# Remove the agent META-INF so it does not override the patcher manifest
rm -rf build/patcher-stage/META-INF
mkdir -p build/patcher-stage/META-INF
cp src/main/java/META-INF-patcher/MANIFEST.MF build/patcher-stage/META-INF/MANIFEST.MF

"$JAR_CMD" cfm patcher.jar build/patcher-stage/META-INF/MANIFEST.MF \
    -C build/patcher-stage .

echo ""
echo "=== Build complete ==="
echo ""
echo "Files produced:"
echo "  $(pwd)/libtexcompress.so"
echo "  $(pwd)/texcompress-agent.jar"
echo "  $(pwd)/patcher.jar"
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
