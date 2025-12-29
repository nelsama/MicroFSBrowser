#!/bin/bash
# Script para compilar y ejecutar MicroFS Browser en Linux/Mac
# Requiere JDK 8 o superior

echo "Compilando MicroFS Browser..."

mkdir -p bin

javac -d bin -sourcepath src src/microfs/MicroFSBrowser.java

if [ $? -eq 0 ]; then
    echo "Compilación exitosa!"
    echo ""
    echo "Ejecutando MicroFS Browser..."
    echo "NOTA: Para acceder a dispositivos físicos, ejecuta con sudo"
    echo ""
    java -cp bin microfs.MicroFSBrowser
else
    echo "Error de compilación"
fi
