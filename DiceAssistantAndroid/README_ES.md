# Dice Assistant (Android)

Primera versión experimental de una app Android que lee la pantalla, detecta cuatro dados blancos con puntos oscuros y muestra la suma en una burbuja flotante.

## Qué hace esta versión

- Solicita el permiso de Android para capturar la pantalla (`MediaProjection`).
- Solicita permiso para mostrarse encima de otras aplicaciones.
- Analiza la pantalla en el propio dispositivo, aproximadamente cada 180 ms.
- Busca cuatro caras cuadradas claras en la zona central y cuenta sus puntos oscuros.
- Exige dos fotogramas consecutivos con el mismo resultado antes de mostrarlo.
- Muestra una burbuja movible con el total y el desglose (por ejemplo, `SUMA 14` y `3 + 4 + 1 + 6`).
- No guarda capturas ni las envía por Internet.

## Importante: estado del entregable

Este entorno de trabajo no tiene instalado Android SDK ni Gradle, por lo que no puedo compilar ni verificar aquí un archivo APK. Se entrega el proyecto fuente de Android Studio, listo para abrir y compilar en un equipo con Android Studio y Android SDK instalados. No se debe considerar esta versión como una APK ya probada en un teléfono.

## Compilar la APK

1. Instala Android Studio en un ordenador.
2. Abre Android Studio y selecciona **Open**.
3. Selecciona la carpeta `DiceAssistantAndroid` (la carpeta que contiene `settings.gradle.kts`).
4. Acepta la sincronización de Gradle y permite instalar los componentes del SDK que solicite. El proyecto usa Android Gradle Plugin 8.13.2, Gradle 8.13, JDK 17 y compile SDK 36.
5. En Android Studio, selecciona **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. La APK de depuración se generará en `app/build/outputs/apk/debug/app-debug.apk`.
7. Copia la APK al móvil e instálala. Android puede pedir que autorices la instalación desde esa fuente.

## Primer uso en el móvil

1. Abre Dice Assistant.
2. Pulsa **Activar detección**.
3. Autoriza **Mostrar sobre otras aplicaciones**.
4. Acepta el diálogo de Android para compartir/capturar la pantalla.
5. Vuelve al juego. Cuando reconozca cuatro dados, aparecerá la burbuja.
6. Arrastra la burbuja para cambiarla de posición.
7. Para terminar, vuelve a Dice Assistant o utiliza **Detener** en la notificación persistente.

## Alcance de reconocimiento

El detector está ajustado a la captura proporcionada: cuatro dados blancos, cuadrados, con puntos oscuros y dispuestos en una cuadrícula 2×2 sobre fondo oscuro. Puede requerir ajustes si el juego cambia el diseño, el tamaño de los dados, los colores, la perspectiva o la ubicación. También hay que probarlo en el teléfono real: los fabricantes pueden aplicar restricciones de batería y ventanas flotantes.

## Estructura

- `MainActivity.kt`: interfaz, permisos y comienzo/parada de la captura.
- `CaptureService.kt`: servicio de primer plano, captura de pantalla y burbuja flotante.
- `DiceDetector.kt`: detección local de las caras y conteo de puntos.

## Prueba con la captura facilitada

El código Kotlin de `DiceDetector` se ejecutó sobre `55599.jpg` en una prueba JVM con un adaptador de imagen y devolvió `3, 4, 1, 6` y suma `14`. Esta prueba valida el algoritmo de reconocimiento sobre esa captura concreta; no sustituye una compilación Android ni una prueba en el teléfono.

## Generar la APK en GitHub (opcional)

El proyecto incluye `.github/workflows/build-apk.yml`. Al subir el proyecto a un repositorio GitHub y ejecutar el workflow **Build Android APK**, GitHub Actions compilará la APK de depuración y la publicará como artefacto descargable de esa ejecución.
