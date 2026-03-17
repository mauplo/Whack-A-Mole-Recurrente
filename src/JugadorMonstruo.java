import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageListener;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ItemEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

public class JugadorMonstruo {

    private static final String SERVER_HOST = "localhost";
    private static final int LOGIN_PORT = 33000;
    private static final int HIT_PORT = 36900;
    private static final String TOPIC_NAME = "PegaleAlMonstruo";
    // Escala visual solicitada.
    private static final int UI_SCALE = 2;

    private String playerName;

    private JFrame frame;
    private JLabel statusLabel;
    private JCheckBox[] boxes;

    private volatile int currentMonsterCell = -1;
    private volatile boolean monsterAvailable = false;
    private volatile boolean updatingUI = false;

    // Canal TCP persistente para enviar golpes sin abrir/cerrar socket en cada clic.
    private Socket hitSocket;
    private DataInputStream hitIn;
    private DataOutputStream hitOut;

    private Timer monsterTimeoutTimer;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new JugadorMonstruo().start());
    }

    private void start() {
        playerName = askPlayerName();
        if (playerName == null) {
            return;
        }

        if (!doLogin()) {
            showScaledMessageDialog("No fue posible registrarse en el servidor.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!connectHitChannel()) {
            showScaledMessageDialog("No fue posible conectar el canal de golpes.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        buildUI();
        startTopicConsumer();
    }

    private String askPlayerName() {
        JPanel loginPanel = new JPanel(new BorderLayout(8 * UI_SCALE, 8 * UI_SCALE));
        loginPanel.setPreferredSize(new Dimension(420 * UI_SCALE, 90 * UI_SCALE));

        JLabel prompt = new JLabel("Ingresa tu nombre:");
        prompt.setFont(new Font("SansSerif", Font.BOLD, 14 * UI_SCALE));

        JTextField nameField = new JTextField();
        nameField.setFont(new Font("SansSerif", Font.PLAIN, 14 * UI_SCALE));

        loginPanel.add(prompt, BorderLayout.NORTH);
        loginPanel.add(nameField, BorderLayout.CENTER);

        int option = JOptionPane.showConfirmDialog(
                null,
                loginPanel,
                "Login",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (option != JOptionPane.OK_OPTION) {
            return null;
        }

        String name = nameField.getText();
        if (name == null) {
            return null;
        }
        name = name.trim();
        if (name.isEmpty()) {
            showScaledMessageDialog("El nombre no puede estar vacio.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return name;
    }

    private void showScaledMessageDialog(String message, String title, int messageType) {
        JLabel label = new JLabel(message);
        label.setFont(new Font("SansSerif", Font.PLAIN, 13 * UI_SCALE));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(420 * UI_SCALE, 80 * UI_SCALE));
        panel.add(label, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(null, panel, title, messageType);
    }

    private boolean doLogin() {
        Socket socket = null;
        try {
            socket = new Socket(SERVER_HOST, LOGIN_PORT);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Envia nombre para registro/login del jugador.
            out.writeUTF(playerName);
            String response = in.readUTF();
            System.out.println("Respuesta login: " + response);
            return response.startsWith("OK");

        } catch (IOException e) {
            System.out.println("Error en login TCP: " + e.getMessage());
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.out.println("Error cerrando socket login cliente: " + e.getMessage());
                }
            }
        }
    }

    private void buildUI() {
        frame = new JFrame("Pegale al monstruo - " + playerName);
        // Cierra solo esta ventana para no afectar a otros jugadores abiertos en la misma JVM.
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeHitChannel();
            }
        });
        frame.setLayout(new BorderLayout());

        statusLabel = new JLabel("Esperando monstruo...", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 14 * UI_SCALE));
        frame.add(statusLabel, BorderLayout.NORTH);

        JPanel board = new JPanel(new GridLayout(3, 3, 8 * UI_SCALE, 8 * UI_SCALE));
        boxes = new JCheckBox[9];

        for (int i = 0; i < boxes.length; i++) {
            final int cell = i + 1;
            JCheckBox box = new JCheckBox("Casilla " + cell, false);
            box.setHorizontalAlignment(SwingConstants.CENTER);
            box.setFont(new Font("SansSerif", Font.PLAIN, 12 * UI_SCALE));

            // Solo cuenta como golpe si el usuario desmarca la casilla activa a tiempo.
            box.addItemListener(e -> {
                if (updatingUI) {
                    return;
                }
                if (e.getStateChange() == ItemEvent.DESELECTED
                        && monsterAvailable
                        && currentMonsterCell == cell) {
                    monsterAvailable = false;
                    sendHit();
                    clearBoard();
                    statusLabel.setText("Golpe enviado...");
                }
            });

            boxes[i] = box;
            board.add(box);
        }

        frame.add(board, BorderLayout.CENTER);
        frame.setSize(460 * UI_SCALE, 320 * UI_SCALE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private boolean connectHitChannel() {
        try {
            hitSocket = new Socket(SERVER_HOST, HIT_PORT);
            hitOut = new DataOutputStream(hitSocket.getOutputStream());
            hitIn = new DataInputStream(hitSocket.getInputStream());
            return true;
        } catch (IOException e) {
            System.out.println("Error conectando canal de golpes TCP: " + e.getMessage());
            closeHitChannel();
            return false;
        }
    }

    private void startTopicConsumer() {
        try {
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(ActiveMQConnection.DEFAULT_BROKER_URL);
            Connection connection = connectionFactory.createConnection();
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Destination destination = session.createTopic(TOPIC_NAME);

            MessageConsumer consumer = session.createConsumer(destination);
            consumer.setMessageListener(new GameMessageListener());

            statusLabel.setText("Conectado. Esperando mensajes del juego...");
        } catch (JMSException e) {
            System.out.println("Error en consumidor JMS: " + e.getMessage());
            statusLabel.setText("Error conectando al topico.");
        }
    }

    private void sendHit() {
        try {
            if (hitSocket == null || hitSocket.isClosed()) {
                System.out.println("Canal de golpes no disponible.");
                return;
            }

            // Reporta al servidor quien golpeo al monstruo por el socket reutilizable.
            hitOut.writeUTF(playerName);
            hitOut.flush();
            String ack = hitIn.readUTF();
            System.out.println("Respuesta golpe: " + ack);

        } catch (IOException e) {
            System.out.println("Error enviando golpe TCP: " + e.getMessage());
        }
    }

    private void closeHitChannel() {
        try {
            if (hitIn != null) {
                hitIn.close();
            }
        } catch (IOException e) {
            System.out.println("Error cerrando input de golpes: " + e.getMessage());
        }
        try {
            if (hitOut != null) {
                hitOut.close();
            }
        } catch (IOException e) {
            System.out.println("Error cerrando output de golpes: " + e.getMessage());
        }
        try {
            if (hitSocket != null) {
                hitSocket.close();
            }
        } catch (IOException e) {
            System.out.println("Error cerrando socket de golpes: " + e.getMessage());
        }
    }

    private void handleTopicMessage(String text) {
        if (text == null) {
            return;
        }

        if (text.startsWith("MONSTRUO:")) {
            int cell = Integer.parseInt(text.substring("MONSTRUO:".length()));
            showMonster(cell);
            return;
        }

        if (text.startsWith("GANADOR:")) {
            String winner = text.substring("GANADOR:".length());
            showWinner(winner);
            return;
        }

        if ("INICIO".equals(text)) {
            clearBoard();
            statusLabel.setText("Nuevo juego iniciado.");
        }
    }

    private void showMonster(int cell) {
        clearBoard();

        currentMonsterCell = cell;
        monsterAvailable = true;

        updatingUI = true;
        boxes[cell - 1].setSelected(true);
        updatingUI = false;

        statusLabel.setText("Monstruo en casilla " + cell + " (haz clic para desmarcar)");

        // Si no lo golpean en 3 segundos, se desactiva automaticamente.
        if (monsterTimeoutTimer != null && monsterTimeoutTimer.isRunning()) {
            monsterTimeoutTimer.stop();
        }

        monsterTimeoutTimer = new Timer(3000, e -> {
            if (monsterAvailable && currentMonsterCell == cell) {
                monsterAvailable = false;
                clearBoard();
                statusLabel.setText("Se fue el monstruo. Esperando siguiente...");
            }
        });
        monsterTimeoutTimer.setRepeats(false);
        monsterTimeoutTimer.start();
    }

    private void showWinner(String winner) {
        monsterAvailable = false;
        clearBoard();
        statusLabel.setText("¡Ha ganado " + winner + "!, comienza nuevo juego");

        // Mantiene visible el mensaje de ganador por 2 segundos.
        Timer winnerTimer = new Timer(2000, e -> {
            clearBoard();
            statusLabel.setText("Esperando monstruo...");
        });
        winnerTimer.setRepeats(false);
        winnerTimer.start();
    }

    private void clearBoard() {
        updatingUI = true;
        for (JCheckBox box : boxes) {
            box.setSelected(false);
        }
        updatingUI = false;
        currentMonsterCell = -1;
    }

    private class GameMessageListener implements MessageListener {
        @Override
        public void onMessage(Message message) {
            TextMessage textMessage = (TextMessage) message;
            try {
                String text = textMessage.getText();
                SwingUtilities.invokeLater(() -> handleTopicMessage(text));
            } catch (JMSException e) {
                System.out.println("Error leyendo mensaje JMS: " + e.getMessage());
            }
        }
    }
}
