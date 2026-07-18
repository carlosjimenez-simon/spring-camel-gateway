@echo off
:: Configuración de caracteres para que los emojis se vean bien en la consola de Windows
chcp 65001 > nul

:: Configuración de variables
set IMAGE_NAME=simonmovilidad/camel-gateway
set TAG=0.0.1
set FULL_IMAGE_PATH=%IMAGE_NAME%:%TAG%

echo 🚀 Iniciando proceso de despliegue para %FULL_IMAGE_PATH%...

:: 1. Asegurarse de que estamos en el directorio donde está guardado el script
cd /d "%~dp0"

:: 2. Verificar si Docker está corriendo
docker info >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ❌ Error: Docker no parece estar corriendo. Por favor inícialo.
    pause
    exit /b 1
)

:: 3. Crear y usar un constructor de buildx que soporte multi-plataforma si no existe
set BUILDER_NAME=simon-builder
docker buildx inspect %BUILDER_NAME% >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo 🔧 Creando nuevo constructor multi-plataforma...
    docker buildx create --name %BUILDER_NAME% --use
)

:: 4. Login en Docker Hub (Opcional si ya estás logueado)
echo 🔑 Verificando sesión en Docker Hub...
:: docker login

:: 5. Construir y Publicar (Build & Push)
echo 📦 Construyendo imagen multi-plataforma (linux/amd64) y publicando...
docker buildx build ^
  --platform linux/amd64 ^
  -t %FULL_IMAGE_PATH% ^
  -t "%IMAGE_NAME%:latest" ^
  --push .

if %ERRORLEVEL% EQU 0 (
    echo ✅ ¡Éxito! La imagen ha sido publicada en: https://hub.docker.com/r/%IMAGE_NAME%
    echo 🐳 Para probarla localmente (aunque sea amd64^):
    echo docker pull %FULL_IMAGE_PATH%
) else (
    echo ❌ Hubo un error en la construcción o el push.
    pause
    exit /b 1
)

pause