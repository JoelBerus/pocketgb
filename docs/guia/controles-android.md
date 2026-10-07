# Controles táctiles en Android

Esta guía explica cómo se ve y se comporta la cruceta de PocketGB en Android, qué hacen las diagonales y cómo ajustar el tamaño, la separación y la ubicación de los controles. Todo se hace desde la propia app, sin red ni cuentas.

## Los dos estilos de cruceta

En **Ajustes › Controles › Cruceta** eliges cómo se dibuja:

- **Game Boy**: una cruz de una sola pieza dentro de un disco, como en la consola. Al tocar una dirección se ilumina solo ese brazo; el centro de la cruz nunca se ilumina.
- **Flechas separadas**: cuatro botones redondos puestos en rombo (arriba, derecha, abajo, izquierda), cada uno con su flecha. Al tocar una dirección se ilumina solo ese botón; si pulsas una diagonal se iluminan dos.

El color sale del tema de la app: los botones sin pulsar son de un tono oscuro y el que pulsas se vuelve verde claro, con un contraste de al menos 3 a 1 entre uno y otro. Con el **contraste alto** del sistema los controles se dibujan sólidos y con borde claro.

La **opacidad** (Ajustes › Controles › Opacidad) solo cambia cuánto se transparentan los controles sin pulsar. Lo pulsado se ve siempre con claridad. Si los controles quedan sobre una escena muy clara y con poca opacidad, sube la opacidad al 70 % o más.

## Diagonales

En **Ajustes › Controles › Diagonales** decides cuánto cuentan las diagonales (arriba y derecha a la vez, etc.):

| Opción | Qué hace |
|---|---|
| **Normales** | Ocho sectores de 45°. Las diagonales ocupan la mitad del giro del dedo. |
| **Reducidas** (por defecto) | La diagonal solo cuenta si el dedo apunta claramente a la esquina (cerca de los 45°). Pulsar arriba enciende solo arriba aunque el dedo tiemble un poco. |
| **Desactivadas** | Solo cuatro direcciones: arriba, abajo, izquierda y derecha. |

Además, en todas las opciones:

- Hay una **zona muerta** en el centro (el 30 % del radio): mientras el dedo esté ahí no se pulsa nada.
- Una vez que una dirección está activa se **mantiene un poco más** al acercarte al centro o a otra dirección, para que un pequeño temblor no la haga parpadear.
- La **háptica** (la vibración corta) suena solo cuando se activa una dirección nueva, no en cada cambio de sector.

El ajuste de diagonales es de la cruceta táctil. El stick de un mando físico sigue como siempre.

## Tamaño, separación y ubicación

Hay dos niveles de tamaño:

- **Tamaño general** (Ajustes › Controles › Tamaño): Pequeño, Normal o Grande para todos los controles a la vez.
- **Tamaño de un control** y su ubicación: en el editor.

Para entrar al editor, abre el menú del juego y elige **Personalizar controles**. El juego queda en pausa mientras editas.

1. **Mover**: arrastra un control con el dedo. Se pega suavemente al borde (a 8 dp) para que quede alineado.
2. **Tamaño**: toca un control para elegirlo (queda con un contorno de rayas) y usa **−** y **+** para cambiar su tamaño de 10 en 10 %, entre 60 % y 160 %.
3. **Separación de las flechas**: si el estilo es *Flechas separadas* y tienes elegida la cruceta, aparece una segunda fila con **−** y **+** para acercar o separar las cuatro flechas, de 70 % a 150 % (100 % es la distribución normal). La zona táctil crece o se encoge con las flechas, así que siempre puedes pulsarlas por completo.
4. **Restablecer** devuelve todos los controles de esa orientación a su sitio y tamaño de fábrica, y la separación a 100 %.
5. **Listo** guarda y vuelve al menú de pausa.

Vertical y horizontal se guardan por separado: lo que ajustes en vertical no cambia la disposición en horizontal. En **Ajustes › Controles › Disposición** también puedes restablecer cada orientación sin abrir un juego.

Consejos:

- Si quieres la cruceta más cómoda para el pulgar, sube su tamaño y ponla un poco más al centro; no hace falta tocar los demás controles.
- Con *Flechas separadas* y la separación al máximo, el grupo ocupa bastante pantalla; en horizontal conviene bajar un poco el tamaño de la cruceta.
- La barra del editor puede quedar sobre la cruceta; arrastra desde una parte visible del control.

## Accesibilidad

- Con **TalkBack**, cada control es un elemento propio con su nombre («Cruceta», «Botón A»…) y un área táctil de al menos 48 dp. La cruceta ofrece las acciones Arriba, Abajo, Izquierda y Derecha. El tamaño y la separación no cambian esto.
- Con **fuente grande** las tres opciones de Diagonales se muestran en lista, cada una con su explicación, y no se cortan.
- Con **contraste alto** los controles son sólidos y con borde claro.

## Si algo no va como esperas

- *Al pulsar arriba se encienden dos direcciones*: pasa a **Reducidas** o **Desactivadas** en Ajustes › Controles › Diagonales.
- *No se enciende nada aunque toco la cruceta*: el dedo está en la zona muerta del centro; desplázalo hacia el brazo o la flecha que quieras.
- *Los controles están muy pequeños o fuera de sitio*: abre Personalizar controles y pulsa **Restablecer**.
