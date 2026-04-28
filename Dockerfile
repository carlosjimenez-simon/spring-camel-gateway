# Etapa 1: Compilación (Build)
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Copiar el pom y descargar dependencias (esto optimiza el cache de Docker)
COPY pom.xml .
RUN mvn dependency:go-offline

# Copiar el código fuente y compilar el JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Etapa 2: Imagen de ejecución (Runtime)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Crear un usuario no-root por seguridad
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copiar el JAR desde la etapa de compilación
# El nombre del JAR debe coincidir con artifactId-version del pom.xml
COPY --from=build /app/target/spring-camel-gateway-0.0.1.jar app.jar

# Puerto configurado en tu application.yml
EXPOSE 8080
EXPOSE 9000

# Parámetros de JVM recomendados para contenedores
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]