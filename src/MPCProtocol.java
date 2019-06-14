public interface MPCProtocol {
    public long[] runProtocol(Gate[] circuit);
    public void setup();
    public void stop();
}
