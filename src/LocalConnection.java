import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LocalConnection extends Thread implements Connection {
    private Queue<String> queue = new ConcurrentLinkedQueue<>();
    private boolean running = true;

    public synchronized void send(byte[] s) {
        queue.add(new String(s, StandardCharsets.UTF_8));
        notify();
    }
    public synchronized String receive() {
        while(queue.isEmpty() && running) {
            try {
                wait(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        // If this is the case we are done and return null!
        if(!running)
            return null;
        return queue.remove();
    }
    public void close() {
        running = false;
    }
}
