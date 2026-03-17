import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class EscuchadorLogin extends Thread {

    private final int port;
    private final ServidorMonstruos server;

    public EscuchadorLogin(int port, ServidorMonstruos server) {
        this.port = port;
        this.server = server;
    }

    @Override
    public void run() {
        try (ServerSocket listenSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = listenSocket.accept();
                new LoginConnection(clientSocket, server).start();
            }
        } catch (IOException e) {
            System.out.println("Error en escuchadorLogin: " + e.getMessage());
        }
    }
}

class LoginConnection extends Thread {

    private final Socket clientSocket;
    private final ServidorMonstruos server;

    public LoginConnection(Socket clientSocket, ServidorMonstruos server) {
        this.clientSocket = clientSocket;
        this.server = server;
    }

    @Override
    public void run() {
        try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {

            // Recibe nombre del jugador para registro/login.
            String playerName = in.readUTF();
            server.registerPlayer(playerName);

            // Respuesta basica con puertos y topico para jugar.
                out.writeUTF("OK|TOPIC=" + ServidorMonstruos.TOPIC_NAME
                    + "|LOGIN_PORT=" + ServidorMonstruos.LOGIN_PORT
                    + "|HIT_PORT=" + ServidorMonstruos.HIT_PORT);

        } catch (EOFException e) {
            System.out.println("Login EOF: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("Login IO: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("No se pudo cerrar socket login: " + e.getMessage());
            }
        }
    }
}
