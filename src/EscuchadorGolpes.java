import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class EscuchadorGolpes extends Thread {

    private final int port;
    private final ServidorMonstruos server;

    public EscuchadorGolpes(int port, ServidorMonstruos server) {
        this.port = port;
        this.server = server;
    }

    @Override
    public void run() {
        try (ServerSocket listenSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = listenSocket.accept();
                new HitConnection(clientSocket, server).start();
            }
        } catch (IOException e) {
            System.out.println("Error en escuchadorGolpes: " + e.getMessage());
        }
    }
}

class HitConnection extends Thread {

    private final Socket clientSocket;
    private final ServidorMonstruos server;

    public HitConnection(Socket clientSocket, ServidorMonstruos server) {
        this.clientSocket = clientSocket;
        this.server = server;
    }

    @Override
    public void run() {
        try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {

            // Reutiliza la misma conexion para recibir multiples golpes del mismo jugador.
            while (true) {
                String playerName = in.readUTF();
                boolean accepted = server.tryHit(playerName);

                // Respuesta simple para que el cliente sepa si conto el golpe.
                out.writeUTF(accepted ? "HIT_OK" : "HIT_REJECTED");
                out.flush();
            }

        } catch (EOFException e) {
            System.out.println("Cliente de golpes desconectado.");
        } catch (IOException e) {
            System.out.println("Golpes IO: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("No se pudo cerrar socket golpes: " + e.getMessage());
            }
        }
    }
}
