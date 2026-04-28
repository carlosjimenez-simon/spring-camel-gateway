#!/bin/bash

# Configuración de variables
IMAGE_NAME="simonmovilidad/camel-gateway"
TAG="0.0.1"
FULL_IMAGE_PATH="$IMAGE_NAME:$TAG"

echo "🚀 Iniciando proceso de despliegue para $FULL_IMAGE_PATH..."

# 1. Asegurarse de que estamos en el directorio correcto
cd "$(dirname "$0")"

# 2. Verificar si Docker está corriendo
if ! docker info > /dev/null 2>&1; then
    echo "❌ Error: Docker no parece estar corriendo. Por favor inícialo."
    exit 1
fi

# 3. Crear y usar un constructor de buildx que soporte multi-plataforma si no existe
# Esto es vital para que tu Mac cree imágenes compatibles con AWS
BUILDER_NAME="simon-builder"
if ! docker buildx inspect $BUILDER_NAME > /dev/null 2>&1; then
    echo "🔧 Creando nuevo constructor multi-plataforma..."
    docker buildx create --name $BUILDER_NAME --use
fi

# 4. Login en Docker Hub (Opcional si ya estás logueado)
echo "🔑 Verificando sesión en Docker Hub..."
# docker login  # Descomenta esta línea si no has hecho login previamente

# 5. Construir y Publicar (Build & Push)
# --platform linux/amd64 asegura que funcione en Amazon Linux 2023
echo "📦 Construyendo imagen multi-plataforma (linux/amd64) y publicando..."
docker buildx build \
  --platform linux/amd64 \
  -t $FULL_IMAGE_PATH \
  -t "$IMAGE_NAME:latest" \
  --push .

if [ $? -eq 0 ]; then
    echo "✅ ¡Éxito! La imagen ha sido publicada en: https://hub.docker.com/r/$IMAGE_NAME"
    echo "🐳 Para probarla localmente (aunque sea amd64):"
    echo "docker pull $FULL_IMAGE_PATH"
else
    echo "❌ Hubo un error en la construcción o el push."
    exit 1
fi