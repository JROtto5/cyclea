#!/bin/bash
# Cyclea direct build — no Gradle/Loom required.
#
# Minecraft 26.2's client jar ships already de-obfuscated with official
# (mojmap) names, and the game runs mods in that same namespace. So we can
# compile straight against the on-disk jars with javac and package the result
# — no mappings download, no Loom. Adjust MCROOT if your instance differs.
set -e
MCROOT="${MCROOT:-$HOME/.minecraft}"
JAVA_BIN="${JAVA_BIN:-$MCROOT/runtime/java-runtime-epsilon/linux/java-runtime-epsilon/bin}"
MCVER="${MCVER:-26.2}"

MC="$MCROOT/versions/$MCVER/$MCVER.jar"
LIBS=$(find "$MCROOT/libraries" -name "*.jar" | grep -v natives | tr '\n' ':')
FAPI=$(ls "$MCROOT"/.fabric/processedMods/*.jar | tr '\n' ':')
CP="$MC:$LIBS:$FAPI"

rm -rf out && mkdir -p out
"$JAVA_BIN/javac" --release 21 -cp "$CP" -d out $(find src/main/java -name "*.java")
cp -r src/main/resources/* out/
sed -i "s/\${version}/4.3.0/" out/fabric.mod.json
"$JAVA_BIN/jar" --create --file cyclea-4.3.0.jar -C out .
echo "built: cyclea-4.3.0.jar  (copy into $MCROOT/mods/)"
