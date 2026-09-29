# M7 · Controles translúcidos pulidos + mandos + avance rápido + save states

🍎 Solo Mac. Spec: [04](../04-ios-spec.md) §Controles translúcidos, §Mandos físicos, §Save states.

**Criterios de aceptación**
- [ ] En horizontal, opacidad 0.30 en reposo y 0.60 al pulsar. El slider de Ajustes cambia la opacidad en vivo.
- [ ] Multitoque: A+B simultáneos; deslizar B→A sin levantar el dedo; rodar el pulgar por las 8 direcciones del D-pad; nunca se generan direcciones opuestas (test unitario de `ControlsLayout.direction(for:)` con ángulos límite).
- [ ] Los bordes no abren el Centro de Control ni el de notificaciones durante el juego.
- [ ] Mando Bluetooth: se conecta, oculta la superposición y los botones mapean bien.
- [ ] Avance rápido ×2/×4 sin desincronizar el audio al soltarlo.
- [ ] 4 slots de save state + "auto". Cargar un estado no corrompe la SRAM (primero se hace backup).
- [ ] Editor de disposición: mover el botón A, reiniciar la app y sigue en su sitio.
