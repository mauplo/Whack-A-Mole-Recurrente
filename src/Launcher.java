public class Launcher {

    // Ajusta este valor para abrir 2 o mas jugadores en la misma maquina.
    private static final int NUM_JUGADORES = 3;
    // Pequena separacion para que no se encimen los dialogs de login al iniciar.
    private static final long DELAY_ENTRE_JUGADORES_MS = 300;

    public static void main(String[] args) {
        if (NUM_JUGADORES < 2) {
            System.out.println("NUM_JUGADORES debe ser >= 2 para demostrar modo multijugador.");
            return;
        }

        System.out.println("Lanzando " + NUM_JUGADORES + " jugadores. Asegurate de haber iniciado ServidorMonstruos primero.");

        for (int i = 1; i <= NUM_JUGADORES; i++) {
            System.out.println("Iniciando jugador " + i + "...");
            JugadorMonstruo.main(new String[0]);

            try {
                Thread.sleep(DELAY_ENTRE_JUGADORES_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Launcher interrumpido: " + e.getMessage());
                return;
            }
        }
    }
}
