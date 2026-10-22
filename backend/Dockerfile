# LINKR backend per Render (Web Service, runtime Docker, Root Directory = backend). Vedi docs/DEPLOY.md.
# Nessun segreto nell'immagine: database, JWT e CORS arrivano dalle variabili d'ambiente del servizio.

# --- 1. Build: Maven 3.9 con JDK 21 (l'immagine ufficiale include mvn, quindi il wrapper non deve scaricare nulla) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Prima le sole dipendenze: se pom.xml non cambia, Docker riusa questo livello.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src src
# I test girano sul database di test, non durante la build dell'immagine.
RUN mvn -B -q -DskipTests package

# --- 2. Runtime: solo JRE 21 e il jar, utente non privilegiato ---
FROM eclipse-temurin:21-jre
RUN groupadd --system linkr && useradd --system --gid linkr --create-home linkr \
    && mkdir -p /data/uploads && chown -R linkr:linkr /data
WORKDIR /app
COPY --from=build --chown=linkr:linkr /build/target/backend-0.0.1-SNAPSHOT.jar app.jar
USER linkr

# Valori predefiniti NON segreti; si possono sovrascrivere dalle variabili del servizio su Render.
#  - JAVA_OPTS: heap al 65% della memoria del container (Render Free: 512 MB), GC seriale e compilatore leggero
#    (meno RAM e meno CPU all'avvio), nessuna dipendenza da un terminale.
#  - SPRING_JPA_SHOW_SQL: il file di configurazione lo accende per lo sviluppo; in produzione sarebbe solo rumore nei log.
#  - LINKR_UPLOAD_DIR: fuori dall'applicazione. Senza disco persistente e' effimera (si svuota a ogni riavvio).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1" \
    SPRING_JPA_SHOW_SQL=false \
    SERVER_FORWARD_HEADERS_STRATEGY=framework \
    LINKR_UPLOAD_DIR=/data/uploads

# La porta la assegna Render con la variabile PORT (letta da server.port=${PORT:8080}); in locale resta 8080.
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
