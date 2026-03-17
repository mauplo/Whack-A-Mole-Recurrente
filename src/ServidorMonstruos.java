import jakarta.jms.Connection;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class ServidorMonstruos {

    public static final String BROKER_URL = ActiveMQConnection.DEFAULT_BROKER_URL;
    public static final String TOPIC_NAME = "PegaleAlMonstruo";
    public static final int LOGIN_PORT = 33000;
    public static final int HIT_PORT = 36900;
    public static final int SCORE_TO_WIN = 5;

    private final Map<String, Integer> scoreBoard = new ConcurrentHashMap<>();
    private final Random random = new Random();

    private Connection jmsConnection;
    private Session jmsSession;
    private MessageProducer producer;

    // Controla si el monstruo actual todavia acepta golpes.
    private volatile boolean monsterActive = false;
    // Controla pausa entre anuncio de ganador y nuevo juego.
    private volatile boolean gamePaused = false;

    public static void main(String[] args) {
        new ServidorMonstruos().start();
    }

    public void start() {
        try {
            setupTopicProducer();

            // Arranca escuchadores TCP de login y golpes.
            new EscuchadorLogin(LOGIN_PORT, this).start();
            new EscuchadorGolpes(HIT_PORT, this).start();

            System.out.println("Servidor de monstruos iniciado.");
            System.out.println("Topic: " + TOPIC_NAME);
            System.out.println("Login TCP en localhost:" + LOGIN_PORT);
            System.out.println("Golpes TCP en localhost:" + HIT_PORT);

            publish("INICIO");

            // Bucle principal: envia monstruos cada 2 segundos.
            while (true) {
                if (gamePaused) {
                    // Pausa simple antes de reiniciar marcadores y comenzar nuevo juego.
                    Thread.sleep(4000);
                    resetScoresForNewGame();
                    publish("INICIO");
                    gamePaused = false;
                    continue;
                }

                if (!gamePaused) {
                    int cell = random.nextInt(9) + 1;
                    monsterActive = true;
                    publish("MONSTRUO:" + cell);
                    System.out.println("Monstruo enviado a casilla " + cell);
                    Thread.sleep(2000);
                    monsterActive = false;
                }
            }
        } catch (Exception e) {
            System.out.println("Error en servidorMonstruos: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeJms();
        }
    }

    private void setupTopicProducer() throws JMSException {
        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(BROKER_URL);
        jmsConnection = connectionFactory.createConnection();
        jmsConnection.start();

        jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = jmsSession.createTopic(TOPIC_NAME);
        producer = jmsSession.createProducer(destination);
    }

    private synchronized void publish(String text) {
        try {
            TextMessage message = jmsSession.createTextMessage();
            message.setText(text);
            producer.send(message);
            System.out.println("[TOPIC] " + text);
        } catch (JMSException e) {
            System.out.println("No se pudo publicar mensaje: " + e.getMessage());
        }
    }

    public void registerPlayer(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return;
        }
        // Si ya existe, conserva su puntaje actual.
        scoreBoard.putIfAbsent(playerName.trim(), 0);
        System.out.println("Jugador registrado/login: " + playerName + " | score=" + scoreBoard.get(playerName));
    }

    public synchronized boolean tryHit(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return false;
        }

        String name = playerName.trim();

        // Un golpe solo vale si hay monstruo activo y no hay pausa por ganador.
        if (!monsterActive || gamePaused) {
            return false;
        }

        // Si no estaba registrado, lo registramos en caliente con score 0.
        scoreBoard.putIfAbsent(name, 0);

        // Aceptamos solo el primer golpe del monstruo actual.
        monsterActive = false;

        int newScore = scoreBoard.get(name) + 1;
        scoreBoard.put(name, newScore);
        System.out.println("Golpe valido de " + name + ". Nuevo score=" + newScore);

        if (newScore >= SCORE_TO_WIN) {
            handleWinner(name);
        }

        return true;
    }

    private void handleWinner(String winnerName) {
        // Publica al ganador de inmediato; el loop principal hara una pausa simple y reiniciara.
        gamePaused = true;
        monsterActive = false;
        publish("GANADOR:" + winnerName);
    }

    private void resetScoresForNewGame() {
        for (Map.Entry<String, Integer> entry : scoreBoard.entrySet()) {
            scoreBoard.put(entry.getKey(), 0);
        }
        System.out.println("Marcadores reiniciados para nuevo juego.");
    }

    private void closeJms() {
        try {
            if (producer != null) {
                producer.close();
            }
            if (jmsSession != null) {
                jmsSession.close();
            }
            if (jmsConnection != null) {
                jmsConnection.close();
            }
        } catch (JMSException e) {
            System.out.println("Error cerrando JMS: " + e.getMessage());
        }
    }
}
