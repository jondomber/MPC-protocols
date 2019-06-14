public interface Connection {
    void run();
    void send(byte[] s);
    String receive();
    void close();
}
