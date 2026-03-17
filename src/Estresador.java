import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.Locale;

import java.io.DataInputStream;
import java.io.DataOutputStream;

public class Estresador {

    // Diez configuraciones de carga: 50, 100, ..., 500.
    private static final int[] N_VALUES = {50, 100, 150, 200, 250, 300, 350, 400, 450, 500};
    // Numero de repeticiones por configuracion.
    private static final int R = 10;

    private static final String HOST = "localhost";
    private static final int LOGIN_PORT = 33000;
    private static final int HIT_PORT = 36900;

    // Indices de la matriz de mediciones [jugador][metrica][repeticion].
    private static final int IDX_CONEXION_EXITOSA = 0;
    private static final int IDX_TIEMPO_CONEXION_MS = 1;
    private static final int IDX_TIEMPO_PRIMER_GOLPE_MS = 2;

    // Matriz final de metricas [10][6]:
    // N, %exitoConexion, mediaConexion, desvConexion, mediaPrimerGolpe, desvPrimerGolpe
    private static final double[][] metricas = new double[10][6];

    public static void main(String[] args) {
        System.out.println("Iniciando estresador. Asegurate de tener ServidorMonstruos levantado.");

        for (int configIndex = 0; configIndex < N_VALUES.length; configIndex++) {
            int n = N_VALUES[configIndex];
            System.out.println("\nConfiguracion N=" + n + " (R=" + R + ")");

            double[][][] mediciones = new double[n][3][R];
            ejecutarConfiguracion(n, mediciones);
            calcularMetricasDeConfiguracion(configIndex, n, mediciones);
        }

        try {
            exportarMetricasCsv("metricas_estres.csv", metricas);
            System.out.println("CSV generado: metricas_estres.csv");
        } catch (IOException e) {
            System.out.println("No se pudo escribir CSV: " + e.getMessage());
        }
    }

    private static void ejecutarConfiguracion(int n, double[][][] mediciones) {
        for (int rep = 0; rep < R; rep++) {
            Thread[] workersConexion = new Thread[n];

            for (int jugador = 0; jugador < n; jugador++) {
                final int j = jugador;
                final int r = rep;
                workersConexion[j] = new Thread(() -> medirConexionJugador(n, j, r, mediciones),
                        "stress-conn-N" + n + "-J" + j + "-R" + r);
                workersConexion[j].start();
            }

            for (Thread worker : workersConexion) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            Thread[] workersGolpe = new Thread[n];
            for (int jugador = 0; jugador < n; jugador++) {
                if (mediciones[jugador][IDX_CONEXION_EXITOSA][rep] > 0.0) {
                    final int j = jugador;
                    final int r = rep;
                    workersGolpe[j] = new Thread(() -> medirPrimerGolpeJugador(n, j, r, mediciones),
                            "stress-hit-N" + n + "-J" + j + "-R" + r);
                    workersGolpe[j].start();
                }
            }

            for (Thread worker : workersGolpe) {
                if (worker == null) {
                    continue;
                }
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            System.out.println("  Repeticion " + (rep + 1) + "/" + R + " completada.");
        }
    }

    private static void medirConexionJugador(int n, int jugador, int rep, double[][][] mediciones) {
        String playerName = "estres_N" + n + "_J" + jugador + "_R" + rep;

        boolean conexionExitosa = false;
        double tiempoConexionMs = -1.0;
        // Si no conecta, este valor debe quedar negativo para distinguir el fracaso.
        mediciones[jugador][IDX_TIEMPO_PRIMER_GOLPE_MS][rep] = -1.0;

        double elapsedMs;

        long t0 = System.nanoTime();
        try (Socket loginSocket = new Socket(HOST, LOGIN_PORT);
             DataOutputStream out = new DataOutputStream(loginSocket.getOutputStream());
             DataInputStream in = new DataInputStream(loginSocket.getInputStream())) {

            out.writeUTF(playerName);
            out.flush();
            String response = in.readUTF();

            long t1 = System.nanoTime();
            elapsedMs = (t1 - t0) / 1_000_000.0;

            if (response != null && response.startsWith("OK")) {
                conexionExitosa = true;
                tiempoConexionMs = elapsedMs;
            } else {
                tiempoConexionMs = -Math.max(1.0, elapsedMs);
            }

        } catch (IOException e) {
            long t1 = System.nanoTime();
            elapsedMs = (t1 - t0) / 1_000_000.0;
            tiempoConexionMs = -Math.max(1.0, elapsedMs);
        }

        mediciones[jugador][IDX_CONEXION_EXITOSA][rep] = conexionExitosa ? 1.0 : 0.0;
        mediciones[jugador][IDX_TIEMPO_CONEXION_MS][rep] = tiempoConexionMs;
    }

    private static void medirPrimerGolpeJugador(int n, int jugador, int rep, double[][][] mediciones) {
        String playerName = "estres_N" + n + "_J" + jugador + "_R" + rep;

        double tiempoPrimerGolpeMs;
        long g0 = System.nanoTime();
        double elapsedHitMs;

        try (Socket hitSocket = new Socket(HOST, HIT_PORT);
             DataOutputStream hitOut = new DataOutputStream(hitSocket.getOutputStream());
             DataInputStream hitIn = new DataInputStream(hitSocket.getInputStream())) {

            // Medicion requerida: tiempo entre enviar primer golpe y recibir ACK.
            hitOut.writeUTF(playerName);
            hitOut.flush();
            String ack = hitIn.readUTF();

            long g1 = System.nanoTime();
            elapsedHitMs = (g1 - g0) / 1_000_000.0;

            if (ack != null && !ack.isEmpty()) {
                tiempoPrimerGolpeMs = elapsedHitMs;
            } else {
                tiempoPrimerGolpeMs = -Math.max(1.0, elapsedHitMs);
            }

        } catch (IOException e) {
            long g1 = System.nanoTime();
            elapsedHitMs = (g1 - g0) / 1_000_000.0;
            tiempoPrimerGolpeMs = -Math.max(1.0, elapsedHitMs);
        }

        mediciones[jugador][IDX_TIEMPO_PRIMER_GOLPE_MS][rep] = tiempoPrimerGolpeMs;
    }

    private static void calcularMetricasDeConfiguracion(int configIndex, int n, double[][][] mediciones) {
        int totalIntentos = n * R;
        int exitosConexion = 0;

        int countConn = 0;
        double sumConn = 0.0;

        int countHit = 0;
        double sumHit = 0.0;
        double sumDiffSqConn = 0.0;
        double sumDiffSqHit = 0.0;

        double tConn;
        double tHit;

        for (int j = 0; j < n; j++) {
            for (int r = 0; r < R; r++) {
                if (mediciones[j][IDX_CONEXION_EXITOSA][r] > 0.0) {
                    exitosConexion++;
                }

                tConn = mediciones[j][IDX_TIEMPO_CONEXION_MS][r];
                if (tConn >= 0.0) {
                    countConn++;
                    sumConn += tConn;
                }

                tHit = mediciones[j][IDX_TIEMPO_PRIMER_GOLPE_MS][r];
                if (tHit >= 0.0) {
                    countHit++;
                    sumHit += tHit;
                }
            }
        }

        double porcentajeExitoConexion = totalIntentos == 0
                ? 0.0
                : (100.0 * exitosConexion / totalIntentos);

        double mediaConn = countConn > 0 ? (sumConn / countConn) : -1.0;
        double mediaHit = countHit > 0 ? (sumHit / countHit) : -1.0;

        // Segunda pasada: suma de desviaciones al cuadrado.
        for (int j = 0; j < n; j++) {
            for (int r = 0; r < R; r++) {
                tConn = mediciones[j][IDX_TIEMPO_CONEXION_MS][r];
                if (tConn >= 0.0 && countConn > 1) {
                    double diffConn = tConn - mediaConn;
                    sumDiffSqConn += diffConn * diffConn;
                }

                tHit = mediciones[j][IDX_TIEMPO_PRIMER_GOLPE_MS][r];
                if (tHit >= 0.0 && countHit > 1) {
                    double diffHit = tHit - mediaHit;
                    sumDiffSqHit += diffHit * diffHit;
                }
            }
        }

        double desvConn = -1.0;
        if (countConn == 1) {
            desvConn = 0.0;
        } else if (countConn > 1) {
            double varConn = sumDiffSqConn / (countConn - 1);
            desvConn = Math.sqrt(varConn);
        }

        double desvHit = -1.0;
        if (countHit == 1) {
            desvHit = 0.0;
        } else if (countHit > 1) {
            double varHit = sumDiffSqHit / (countHit - 1);
            desvHit = Math.sqrt(varHit);
        }

        metricas[configIndex][0] = n;
        metricas[configIndex][1] = porcentajeExitoConexion;
        metricas[configIndex][2] = mediaConn;
        metricas[configIndex][3] = desvConn;
        metricas[configIndex][4] = mediaHit;
        metricas[configIndex][5] = desvHit;

        System.out.printf(Locale.US,
                "  N=%d | exitoConexion=%.2f%% | conn(ms)=%.3f +- %.3f | hit(ms)=%.3f +- %.3f%n",
                n, porcentajeExitoConexion, mediaConn, desvConn, mediaHit, desvHit);
    }

    private static void exportarMetricasCsv(String path, double[][] metricas) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
            writer.write("N,porcentaje_exito_conexion,media_conexion_ms,desv_conexion_ms,media_primer_golpe_ms,desv_primer_golpe_ms");
            writer.newLine();

            for (double[] fila : metricas) {
                writer.write(String.format(
                        Locale.US,
                        "%.0f,%.6f,%.6f,%.6f,%.6f,%.6f",
                        fila[0], fila[1], fila[2], fila[3], fila[4], fila[5]
                ));
                writer.newLine();
            }
        }
    }
}
