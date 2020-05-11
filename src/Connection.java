public interface Connection {
    void send(byte[] s);
    String receive();
    void close();
}
