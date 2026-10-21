#!/bin/bash
# Compila y ejecuta MicroFS Browser en Linux/macOS
# Requiere JDK 8 o superior

echo "Compilando MicroFS Browser..."

mkdir -p bin

javac -encoding UTF-8 -d bin -sourcepath src src/microfs/*.java

if [ $? -eq 0 ]; then
    echo "Compilación exitosa!"
    echo ""
    echo "Ejecutando MicroFS Browser..."
    echo "NOTA: para abrir dispositivos físicos (/dev/sdX) ejecuta con sudo"
    echo ""
    java -cp bin microfs.MicroFSBrowser
else
    echo "Error de compilación"
fi
